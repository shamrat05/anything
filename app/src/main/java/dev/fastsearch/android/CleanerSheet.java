package dev.fastsearch.android;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import dev.fastsearch.core.FileEntry;
import dev.fastsearch.core.DuplicateFinder;
import java.io.File;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Read-only shared scan, with a separate confirmed action for generated thumbnails. */
final class CleanerSheet {
    enum Mode {
        DUPLICATES("Exact duplicates",R.drawable.ic_copy,
                "Compares file contents. Keep at least one from each exact match group."),
        TEMP_CACHE("Temp & cache",R.drawable.ic_file,
                "Looks for temporary names and shared cache folders. Apps may still rely on these files."),
        DOWNLOADS("Downloads",R.drawable.ic_folder,
                "Reviews older installers and unfinished downloads. Check the reason before touching a file."),
        BACKUPS("Backups",R.drawable.ic_document,
                "Looks for backup copies. A backup may be your only recoverable copy."),
        ARCHIVES("Archives",R.drawable.ic_archive,
                "Looks for archive files. There may be original files inside."),
        SOCIAL("Social media",R.drawable.ic_photo,
                "Looks for media in shared messaging and social folders. It may still be in use."),
        CUSTOM("File types",R.drawable.ic_subtype,
                "Finds files of the types you specify. A match is not a cleanup recommendation.");

        final String label, hint;
        final int icon;
        Mode(String label,int icon,String hint){this.label=label;this.icon=icon;this.hint=hint;}
    }

    interface Scanner {
        Result scan(Request request,BooleanSupplier cancelled,Progress progress)throws Exception;
    }
    interface Progress { void changed(String phase,long visited); }

    static final class Request {
        final String scope, extensions;
        final Mode mode;
        final long minimumAgeMillis, minimumBytes;
        final List<String> excludedFolders, sharedRoots;
        Request(String scope,Mode mode,long minimumAgeMillis,long minimumBytes,String extensions,
                Collection<String> excludedFolders,Collection<String> sharedRoots) {
            this.scope=scope;this.mode=mode;this.minimumAgeMillis=minimumAgeMillis;this.minimumBytes=minimumBytes;
            this.extensions=extensions;
            this.excludedFolders=Collections.unmodifiableList(new ArrayList<>(excludedFolders));
            this.sharedRoots=Collections.unmodifiableList(new ArrayList<>(sharedRoots));
        }
    }
    static final class Result {
        final List<Row> rows;
        final List<DuplicateReview.Group> duplicateGroups;
        final long visited, skipped;
        final boolean limited;
        Result(List<Row> rows,long visited,long skipped,boolean limited){
            this(rows,Collections.emptyList(),visited,skipped,limited);
        }
        Result(List<Row> rows,List<DuplicateReview.Group> duplicateGroups,long visited,long skipped,boolean limited){
            this.rows=Collections.unmodifiableList(new ArrayList<>(rows));
            this.duplicateGroups=Collections.unmodifiableList(new ArrayList<>(duplicateGroups));
            this.visited=visited;this.skipped=skipped;this.limited=limited;
        }
    }
    static final class Row {
        final FileEntry entry;
        final String reason, group;
        final boolean suggestedKeep,suggestedReview;
        final DuplicateReview.Group review;
        final DuplicateFinder.Member member;
        Row(FileEntry entry,String reason,String group,boolean suggestedKeep) {
            this(entry,reason,group,suggestedKeep,null,null,false);
        }
        Row(FileEntry entry,String reason,boolean suggestedReview){
            this(entry,reason,null,false,null,null,suggestedReview);
        }
        Row(FileEntry entry,String reason,String group,boolean suggestedKeep,DuplicateReview.Group review,DuplicateFinder.Member member) {
            this(entry,reason,group,suggestedKeep,review,member,false);
        }
        private Row(FileEntry entry,String reason,String group,boolean suggestedKeep,DuplicateReview.Group review,
                    DuplicateFinder.Member member,boolean suggestedReview) {
            this.entry=entry;this.reason=reason;this.group=group;this.suggestedKeep=suggestedKeep;
            this.review=review;this.member=member;this.suggestedReview=suggestedReview;
        }
    }

    static AppSheet create(Activity activity,FileIndex index,Map<String,String> sharedVolumes,
                           Scanner scanner,Consumer<AppSheet> dismissed) {
        return create(activity,index,sharedVolumes,scanner,dismissed,null);
    }

    static AppSheet create(Activity activity,FileIndex index,Map<String,String> sharedVolumes,
                           Scanner scanner,Consumer<AppSheet> dismissed,Consumer<String> reveal) {
        return new CleanerSheet(activity,index,sharedVolumes,scanner,dismissed,reveal).sheet;
    }

    static AppSheet create(Activity activity,FileIndex index,Map<String,String> sharedVolumes,
                           Scanner scanner,Consumer<AppSheet> dismissed,Consumer<String> reveal,Mode firstMode) {
        CleanerSheet panel=new CleanerSheet(activity,index,sharedVolumes,scanner,dismissed,reveal);
        panel.select(firstMode);
        return panel.sheet;
    }

    private static final long DAY=24L*60*60*1000;
    private static final long MIB=1024L*1024;
    private static final long[] AGES={0,7*DAY,30*DAY,90*DAY,365*DAY};
    private static final long[] SIZES={0,MIB,10*MIB,100*MIB,1024*MIB};
    private final AppSheet sheet;
    private final Activity activity;
    private final FileIndex index;
    private final Scanner scanner;
    private final Consumer<String> reveal;
    private final Map<String,String> roots;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor worker=new ScheduledThreadPoolExecutor(1,r->new Thread(r,"cleaner-review"));
    private final TextView scopeLabel, modeHint, status;
    private final Button pick, configure, run, safeCleanup, selectExtras, clearExtras;
    private final Button suggestedOnly;
    private EditText resultSearch;
    private Button shownSelection, verifySelection;
    private boolean onlySelected;
    private String resultQuery="";
    private final Runnable resultFilter=()->{
        if(!this.closed&&resultSearch!=null)applyResultFilter();
    };
    private int suggestedCount;
    private long suggestedBytes;
    private boolean filteringSuggestions;
    private final TextView selectionStatus;
    private final LinearLayout duplicateActions;
    final DuplicateReview duplicateReview=new DuplicateReview();
    private final ProgressBar spinner;
    private final EdgeFastScroll.List files;
    private final Rows adapter=new Rows();
    private final Map<Mode,Button> chips=new LinkedHashMap<>();
    private List<Row> rows=Collections.emptyList();
    private List<Object> display=Collections.emptyList();
    private List<String> excluded=Collections.emptyList();
    private Mode mode=Mode.DUPLICATES;
    private String scope, extensions="";
    private int age, size;
    private volatile boolean closed;
    private boolean scanning;
    private volatile int revision;
    private Future<?> task;
    private FolderPathDialog picker;
    private AppSheet secondary;

    private CleanerSheet(Activity activity,FileIndex index,Map<String,String> sharedVolumes,
                         Scanner scanner,Consumer<AppSheet> dismissed,Consumer<String> reveal) {
        this.reveal=reveal;
        this.activity=activity;this.index=index;this.scanner=scanner;
        roots=validatedVolumes(sharedVolumes);
        sheet=new AppSheet(activity,"Cleaner",true);
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        sheet.setOnDismissListener(dialog->{
            close();
            if(dismissed!=null)dismissed.accept(sheet);
        });

        TextView readOnly=sheet.text("Scanning never changes your files. Cleanup is separate and asks first.",13,sheet.theme.muted);
        readOnly.setPadding(dp(5),0,dp(5),dp(8));
        sheet.content.addView(readOnly,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout scopePanel=new LinearLayout(activity);
        scopePanel.setOrientation(LinearLayout.VERTICAL);
        scopePanel.setPadding(dp(10),dp(3),dp(10),dp(3));
        scopePanel.setBackground(AppSheet.shape(sheet.theme.chrome,dp(15)));
        pick=button("Choose a folder (optional)",R.drawable.ic_folder,this::chooseScope);
        scopePanel.addView(pick,new LinearLayout.LayoutParams(-1,-2));
        scopeLabel=sheet.text("",12,sheet.theme.muted);
        scopeLabel.setMaxLines(2);scopeLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        scopeLabel.setPadding(dp(4),0,dp(4),dp(7));
        scopePanel.addView(scopeLabel,new LinearLayout.LayoutParams(-1,-2));
        sheet.content.addView(scopePanel,new LinearLayout.LayoutParams(-1,-2));

        HorizontalScrollView horizontal=new HorizontalScrollView(activity);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout modes=new LinearLayout(activity);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setPadding(0,dp(7),0,dp(4));
        for(Mode value:Mode.values()){
            Button chip=button(value.label,value.icon,()->select(value));chips.put(value,chip);
            LinearLayout.LayoutParams item=new LinearLayout.LayoutParams(-2,-2);item.setMarginEnd(dp(5));
            modes.addView(chip,item);
        }
        horizontal.addView(modes,new FrameLayout.LayoutParams(-2,-2));
        sheet.content.addView(horizontal,new LinearLayout.LayoutParams(-1,-2));

        modeHint=sheet.text(mode.hint,13,sheet.theme.muted);modeHint.setPadding(dp(5),0,dp(5),dp(5));
        sheet.content.addView(modeHint,new LinearLayout.LayoutParams(-1,-2));
        configure=button("Filters",R.drawable.ic_options,this::chooseFilters);
        sheet.content.addView(configure,new LinearLayout.LayoutParams(-1,-2));
        safeCleanup=button("Safe cleanup · Thumbnail cache",R.drawable.ic_cleaner,this::reviewCache);
        sheet.content.addView(safeCleanup,new LinearLayout.LayoutParams(-1,-2));
        suggestedOnly=button("Review suggestions",R.drawable.ic_select,this::toggleSuggestions);
        suggestedOnly.setVisibility(View.GONE);
        sheet.content.addView(suggestedOnly,new LinearLayout.LayoutParams(-1,-2));

        status=sheet.text("",13,sheet.theme.muted);
        status.setPadding(dp(5),dp(6),dp(5),dp(6));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        sheet.content.addView(status,new LinearLayout.LayoutParams(-1,-2));
        resultSearch=new EditText(activity);
        resultSearch.setSingleLine();resultSearch.setTextSize(14);resultSearch.setHint("Filter scan results");
        resultSearch.setContentDescription("Filter scan results");resultSearch.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        resultSearch.setVisibility(View.GONE);
        resultSearch.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                ui.removeCallbacks(resultFilter);ui.postDelayed(resultFilter,125);
            }
            public void afterTextChanged(android.text.Editable editable){}
        });
        sheet.content.addView(resultSearch,new LinearLayout.LayoutParams(-1,dp(48)));
        duplicateActions=new LinearLayout(activity);duplicateActions.setOrientation(LinearLayout.VERTICAL);
        duplicateActions.setPadding(dp(4),0,dp(4),dp(3));
        selectionStatus=sheet.text("",13,sheet.theme.muted);
        selectionStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        duplicateActions.addView(selectionStatus,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout buttons=new LinearLayout(activity);
        selectExtras=button("Select extras",R.drawable.ic_select,()->{
            if(resultQuery.isEmpty())duplicateReview.selectExtras();
            else duplicateReview.selectExtras(member->matchesResult(member.entry));
            duplicateChoicesChanged();
        });
        clearExtras=button("Clear selection",R.drawable.ic_close,()->{duplicateReview.clear();duplicateChoicesChanged();});
        buttons.addView(selectExtras,new LinearLayout.LayoutParams(0,dp(48),1));
        buttons.addView(clearExtras,new LinearLayout.LayoutParams(0,dp(48),1));
        duplicateActions.addView(buttons,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout selectedButtons=new LinearLayout(activity);
        shownSelection=button("Show selected",R.drawable.ic_options,this::toggleSelectedOnly);
        verifySelection=button("Recheck selected",R.drawable.ic_check,this::reviewDuplicateSelection);
        selectedButtons.addView(shownSelection,new LinearLayout.LayoutParams(0,dp(48),1));
        selectedButtons.addView(verifySelection,new LinearLayout.LayoutParams(0,dp(48),1));
        duplicateActions.addView(selectedButtons,new LinearLayout.LayoutParams(-1,-2));
        duplicateActions.setVisibility(View.GONE);
        sheet.content.addView(duplicateActions,new LinearLayout.LayoutParams(-1,-2));

        spinner=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
        spinner.setIndeterminate(true);spinner.setVisibility(View.GONE);
        sheet.content.addView(spinner,new LinearLayout.LayoutParams(-1,dp(3)));

        files=new EdgeFastScroll.List(activity);
        files.setDivider(new android.graphics.drawable.ColorDrawable(sheet.theme.line));
        files.setDividerHeight(dp(1));files.setAdapter(adapter);
        files.labels=position->{
            if(position<0||position>=display.size())return "";
            Object item=display.get(position);return item instanceof Row?((Row)item).entry.name:((DuplicateReview.Group)item).label;
        };
        files.setOnItemClickListener((parent,view,position,rowId)->{
            Object item=display.get(position);
            if(item instanceof Row)review((Row)item);else reviewGroup((DuplicateReview.Group)item);
        });
        sheet.content.addView(files,new LinearLayout.LayoutParams(-1,0,1));

        run=sheet.action("Scan storage",true,false,null);
        run.setOnClickListener(v->{if(scanning)cancelScan("Scan canceled. No files changed.");else startScan();});
        updateChoices();
        status.setText(scanner==null?"Cleaner is unavailable in this build.":readyMessage());
    }

    private void chooseScope() {
        if(scanning||closed||picker!=null||roots.isEmpty())return;
        String start=scope==null?roots.keySet().iterator().next():scope;
        picker=new FolderPathDialog(activity,index,"Choose Cleaner scope",start,CleanerSheet::normalize,
                this::choose,()->picker=null,roots,path->allowedScope(roots.keySet(),path),
                false,true,false);
        picker.show();
    }

    private void choose(String value) {
        String normalized=normalize(value);
        if(!allowedScope(roots.keySet(),normalized)||closed||scanning)return;
        if(!normalized.equals(scope)){
            scope=normalized;
            excluded=Collections.emptyList();
            invalidate("Ready to scan the selected folder.");
        }
                updateChoices();
    }

    private void select(Mode next) {
        if(scanning||next==null||next==mode)return;
        mode=next;
        modeHint.setText(mode.hint);
        invalidate(readyMessage());
        updateChoices();
    }

    private void chooseFilters() {
        if(scanning||closed||secondary!=null)return;
        AppSheet filters=new AppSheet(activity,"Cleaner filters");
        secondary=filters;filters.setOnDismissListener(ignored->{if(secondary==filters)secondary=null;});
        Spinner ages=choice(filters,"Minimum age",new String[]{"Any age","7 days","30 days","90 days","1 year"},age);
        Spinner sizes=choice(filters,"Minimum size",new String[]{"Any size","1 MiB","10 MiB","100 MiB","1 GiB"},size);
        EditText ext=field(filters,"File extensions","e.g. jpg, png, pdf",extensions,400,false);
        EditText excludedPaths=field(filters,"Exclude folders","One absolute folder path per line",
                android.text.TextUtils.join("\n",excluded),4096,true);
        excludedPaths.setEnabled(!roots.isEmpty());
        Button apply=filters.action("Apply filters",true,false,null);
        apply.setOnClickListener(view->{
            String types=ext.getText().toString().trim();
            if(!validExtensions(types)){ext.setError("Enter extensions separated by commas or semicolons.");return;}
            List<String> blocked=new ArrayList<>();
            for(String line:excludedPaths.getText().toString().split("[\r\n]")){
                if(line.trim().isEmpty())continue;
                String value=normalize(line.trim());
                if(!allowedScope(roots.keySet(),value)||roots.containsKey(value)
                        ||scope!=null&&(!isInside(value,scope)||scope.equals(value))){
                    excludedPaths.setError("Exclude a folder inside the current scan scope, not an entire storage volume.");return;
                }
                if(!blocked.contains(value)){
                    if(blocked.size()==24){excludedPaths.setError("Use up to 24 excluded folders.");return;}
                    blocked.add(value);
                }
            }
            age=ages.getSelectedItemPosition();size=sizes.getSelectedItemPosition();extensions=types;excluded=blocked;
            filters.dismiss();invalidate("Filters saved. Scan to see matching files.");
            updateChoices();
        });
        if(scope!=null)filters.action("Scan all accessible storage",false,false,()->{
            scope=null;excluded=Collections.emptyList();invalidate(readyMessage());updateChoices();filters.dismiss();
        });
        filters.action("Cancel",false,false,null);
        filters.show();
    }

    private void startScan() {
        if(scanning||closed||scanner==null||roots.isEmpty()||scope!=null&&!allowedScope(roots.keySet(),scope))return;
        if(mode==Mode.CUSTOM&&extensions.isEmpty()){status.setText("Enter extensions under Filters.");chooseFilters();return;}
        final int ticket=++revision;
        final Request request=new Request(scope,mode,AGES[age],SIZES[size],extensions,excluded,roots.keySet());
        scanning=true;
        invalidate(scope==null?"Reading accessible shared storage…":"Reading the selected shared folder…");
        updateChoices();
        AtomicLong lastNotice=new AtomicLong();
        task=worker.submit(()->{
            try {
                Result result=scanner.scan(request,()->closed||revision!=ticket||Thread.currentThread().isInterrupted(),
                        (phase,count)->{
                    long now=SystemClock.elapsedRealtime(), previous=lastNotice.get();
                    if(now-previous<220||!lastNotice.compareAndSet(previous,now))return;
                    ui.post(()->{if(!closed&&scanning&&revision==ticket)status.setText(
                            phase+(count>0?"  ·  "+number(count)+" checked":""));});
                });
                ui.post(()->finish(ticket,result));
            }catch(Exception|OutOfMemoryError failure){
                ui.post(()->{if(!closed&&scanning&&revision==ticket){
                    scanning=false;task=null;
                    status.setText("Couldn’t read this scope. Check Android’s file access or choose another folder.");
                    updateChoices();
                }});
            }
        });
    }

    private void finish(int ticket,Result result) {
        if(closed||!scanning||revision!=ticket)return;
        scanning=false;task=null;
        rows=result.rows;
        duplicateReview.replace(result.duplicateGroups);
        suggestedCount=0;suggestedBytes=0;filteringSuggestions=false;
        for(Row row:rows)if(row.suggestedReview){
            suggestedCount++;
            suggestedBytes=DuplicateReview.add(suggestedBytes,Math.max(0,row.entry.size));
        }
        resultSearch.setVisibility(rows.size()<2?View.GONE:View.VISIBLE);
        rebuildDisplay();updateSuggestionButton();
        if(!rows.isEmpty())files.setSelection(0);
        String summary=(rows.isEmpty()?"No matches":duplicateReview.active()
                ?number(duplicateReview.groupCount())+(duplicateReview.groupCount()==1?" exact group":" exact groups")+"  ·  "+number(rows.size())+" files"
                :number(rows.size())+(rows.size()==1?" file to review":" files to review"))
                +"  ·  "+number(result.visited)+" checked";
        if(result.skipped>0)summary+="  ·  "+number(result.skipped)+" skipped";
        if(result.limited)summary+=". Scan was limited.";
        status.setText(summary);
        updateChoices();
    }

    private void toggleSelectedOnly(){
        onlySelected=!onlySelected;rebuildDisplay();updateDuplicateChoices();files.setSelection(0);
    }

    private void toggleSuggestions(){
        filteringSuggestions=!filteringSuggestions;
        rebuildDisplay();updateSuggestionButton();files.setSelection(0);
    }

    private void rebuildDisplay(){
        List<Object> grouped=new ArrayList<>();
        DuplicateReview.Group last=null;
        for(Row row:rows){
            if(filteringSuggestions&&!row.suggestedReview||!matchesResult(row.entry)
                    ||onlySelected&&(row.review==null||!row.review.isSelected(row.member)))continue;
            if(row.review!=null&&row.review!=last){last=row.review;grouped.add(last);}
            grouped.add(row);
        }
        display=grouped;
        adapter.notifyDataSetChanged();
    }

    private boolean matchesResult(FileEntry entry){
        if(resultQuery.isEmpty())return true;
        return entry.name.toLowerCase(java.util.Locale.ROOT).contains(resultQuery)
                ||entry.parent.toLowerCase(java.util.Locale.ROOT).contains(resultQuery);
    }

    private void applyResultFilter(){
        resultQuery=resultSearch.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        rebuildDisplay();updateDuplicateChoices();files.setSelection(0);
    }

    private void updateSuggestionButton(){
        suggestedOnly.setVisibility(suggestedCount==0?View.GONE:View.VISIBLE);
        suggestedOnly.setText(filteringSuggestions?"Show all "+number(rows.size())+" results":
                "Review "+number(suggestedCount)+" suggestions · "+estimate(suggestedBytes));
    }

    private void cancelScan(String message) {
        revision++;scanning=false;
        if(task!=null){task.cancel(true);task=null;}
        worker.purge();
        invalidate(message);updateChoices();
    }

    private void invalidate(String message) {
        rows=Collections.emptyList();display=Collections.emptyList();
        suggestedCount=0;suggestedBytes=0;filteringSuggestions=false;updateSuggestionButton();
        onlySelected=false;resultQuery="";
        if(resultSearch!=null){
            resultSearch.setText("");resultSearch.setVisibility(View.GONE);
            ui.removeCallbacks(resultFilter);
        }
        duplicateReview.replace(Collections.emptyList());adapter.notifyDataSetChanged();status.setText(message);
    }

    private void updateChoices() {
        pick.setEnabled(!scanning&&!roots.isEmpty());
        pick.setText(scope==null?"Choose a folder (optional)":"Change folder");
        scopeLabel.setText(scope!=null?scope:roots.isEmpty()?"No shared storage discovered":
                "All accessible shared storage · "+TextUtils.join(", ",roots.values()));
        safeCleanup.setVisibility(mode==Mode.DUPLICATES?View.GONE:View.VISIBLE);
        safeCleanup.setEnabled(!scanning);
        updateDuplicateChoices();
        configure.setEnabled(!scanning);
        int count=(age>0?1:0)+(size>0?1:0)+(extensions.isEmpty()?0:1)+excluded.size();
        configure.setText("Filters"+(count==0?"":" ("+number(count)+")"));
        spinner.setVisibility(scanning?View.VISIBLE:View.GONE);
        run.setText(scanning?"Cancel scan":scope==null?"Scan storage":"Scan folder");
        run.setEnabled(scanning||!roots.isEmpty()&&scanner!=null);
        for(Map.Entry<Mode,Button> item:chips.entrySet()){
            Button chip=item.getValue();boolean selected=item.getKey()==mode;
            chip.setSelected(selected);chip.setEnabled(!scanning);
            chip.setBackground(background(selected?sheet.theme.selected:Color.TRANSPARENT));
        }
    }

    private String readyMessage(){
        return roots.isEmpty()?"No shared storage available. Check Android file access.":"Ready to scan. No files will be changed.";
    }

    private void reviewCache(){
        if(closed||scanning||secondary!=null)return;
        AppSheet confirm=new AppSheet(activity,"Safe cleanup");
        secondary=confirm;
        confirm.setOnDismissListener(ignored->{if(secondary==confirm)secondary=null;});
        confirm.content.addView(confirm.text("Only thumbnail copies created by Anything can be cleared here. Original files, downloads, backups and other apps are untouched. Thumbnails regenerate when needed.",14,confirm.theme.ink));
        TextView result=confirm.text("Measuring thumbnail cache…",13,confirm.theme.muted);
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        confirm.content.addView(result);
        confirm.action("Close",false,false,null);
        Button clear=confirm.action("Clear thumbnail cache",true,false,null);
        clear.setEnabled(false);
        worker.submit(()->{
            ThumbnailStore.CacheReview review=ThumbnailStore.reviewCache(activity);
            ui.post(()->{
                if(closed||secondary!=confirm)return;
                result.setText(review.entries.isEmpty()?(review.skipped==0?"No generated thumbnails to clear.":"Thumbnail cache could not be fully inspected."):
                        number(review.entries.size())+" generated thumbnails  ·  "+Formatter.formatShortFileSize(activity,review.bytes));
                clear.setEnabled(!review.entries.isEmpty());
                clear.setOnClickListener(v->{
                    clear.setEnabled(false);
                    result.setText("Clearing reviewed thumbnails…");
                    worker.submit(()->{
                        ThumbnailStore.CacheReceipt receipt=ThumbnailStore.clearReviewedCache(activity,review);
                        ui.post(()->{
                            if(closed||secondary!=confirm)return;
                            result.setText(number(receipt.removed)+" thumbnails cleared  ·  "
                                    +Formatter.formatShortFileSize(activity,receipt.bytes)+" removed"
                                    +(receipt.skipped==0?"":"  ·  "+number(receipt.skipped)+" skipped"));
                        });
                    });
                });
            });
        });
        confirm.show();
    }

    private void duplicateChoicesChanged(){
        if(duplicateReview.selectedCount()==0)onlySelected=false;
        rebuildDisplay();
        updateDuplicateChoices();
    }

    private void updateDuplicateChoices(){
        boolean visible=mode==Mode.DUPLICATES&&!scanning&&duplicateReview.active();
        duplicateActions.setVisibility(visible?View.VISIBLE:View.GONE);
        if(!visible)return;
        int selected=duplicateReview.selectedCount();
        long possible=duplicateReview.possibleBytes();
        int extras=0,hidden=0;
        for(Row row:rows)if(row.review!=null){
            boolean matches=matchesResult(row.entry);
            if(row.review.isSelected(row.member)&&!matches)hidden++;
            if(!onlySelected&&matches&&row.review.eligible(row.member)&&!row.review.isSelected(row.member))extras++;
        }
        selectExtras.setText(resultQuery.isEmpty()?"Select extras":"Select matching extras");
        selectExtras.setEnabled(extras>0);
        clearExtras.setEnabled(selected>0);
        shownSelection.setText(onlySelected?"Show all matches":"Show selected");
        shownSelection.setVisibility(selected>0?View.VISIBLE:View.GONE);
        verifySelection.setVisibility(selected>0?View.VISIBLE:View.GONE);
        verifySelection.setEnabled(selected<=DuplicateCleanupPreflight.MAX_PAIRS);
        selectionStatus.setText(selected==0
                ?(possible>0?estimate(possible)+" possible in eligible extras. ":"No eligible extras to select. ")
                    +"Review only. Nothing is deleted."
                :number(selected)+(selected==1?" copy":" copies")+" selected for review · "
                    +estimate(duplicateReview.selectedBytes())+" possible"
                    +(hidden==0?"":" · "+number(hidden)+" hidden by filter")
                    +(selected>DuplicateCleanupPreflight.MAX_PAIRS?". Recheck up to 32 at a time.":". Nothing is deleted."));
    }

    private void reviewDuplicateSelection(){
        if(closed||scanning||secondary!=null)return;
        List<DuplicateReview.Choice> selection=duplicateReview.snapshot();
        if(selection.isEmpty()||selection.size()>DuplicateCleanupPreflight.MAX_PAIRS)return;
        AppSheet review=new AppSheet(activity,"Recheck selected copies");
        secondary=review;
        java.util.concurrent.atomic.AtomicBoolean canceled=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Future<?>> verifying=new java.util.concurrent.atomic.AtomicReference<>();
        review.setOnDismissListener(ignored->{
            if(secondary==review)secondary=null;
            canceled.set(true);Future<?> task=verifying.getAndSet(null);if(task!=null)task.cancel(true);
        });
        TextView message=review.text("Rechecking each selected copy and its keeper…",14,review.theme.ink);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        review.content.addView(message);review.action("Close",true,false,null);review.show();
        verifying.set(worker.submit(()->{
            try{
                DuplicateCleanupPreflight.Report report=CleanerDuplicates.preflight(selection,
                        ()->closed||canceled.get()||Thread.currentThread().isInterrupted());
                ui.post(()->{
                    if(closed||secondary!=review)return;
                    int matched=0,skipped=0;
                    for(DuplicateCleanupPreflight.Check check:report.checks){
                        if(check.status==DuplicateCleanupPreflight.Status.MATCHED_AT_CHECK)matched++;
                        else skipped++;
                        String summary=check.status==DuplicateCleanupPreflight.Status.MATCHED_AT_CHECK?"Still matches its keeper"
                                :check.status==DuplicateCleanupPreflight.Status.STALE_OR_UNAVAILABLE?"Changed or couldn’t be accessed"
                                :check.status==DuplicateCleanupPreflight.Status.CONFLICTING_SELECTION?"Selection conflict":"Skipped by read limit";
                        review.content.addView(review.text(check.choice.target.entry.name+" · "+summary,13,review.theme.muted));
                    }
                    message.setText(number(matched)+" still match · "+number(skipped)+" need review. No files changed."
                            +(report.limitReached?" Recheck limit reached.":""));
                });
            }catch(Exception|OutOfMemoryError unavailable){ui.post(()->{
                if(!closed&&secondary==review)message.setText("Could not recheck this selection. No files changed.");
            });}
        }));
    }

    private String estimate(long bytes){return Formatter.formatShortFileSize(activity,bytes);}

    private String groupEstimate(DuplicateReview.Group group){
        if(!group.complete)return number(group.visible.size())+" of "+number(group.proof.members.size())
                +" copies shown · selection unavailable";
        int count=group.eligibleCount(),selected=group.selectedCount();
        return count==0?"No eligible extras · keep at least one"
                :estimate(group.possibleBytes())+" possible · keep one"
                +(selected==0?"":" · "+number(selected)+" selected");
    }

    private void reviewGroup(DuplicateReview.Group group){
        if(closed||secondary!=null||!duplicateReview.matches(group))return;
        AppSheet details=new AppSheet(activity,group.label);
        secondary=details;details.setOnDismissListener(ignored->{if(secondary==details)secondary=null;});
        details.content.addView(details.text(number(group.proof.members.size())+" byte-for-byte copies",16,details.theme.ink));
        details.content.addView(details.text(groupEstimate(group),14,details.theme.ink));
        details.content.addView(details.text("Keep: "+group.keeper().entry.path(),13,details.theme.muted));
        details.content.addView(details.text("Selection marks files to review. It never changes them. Protected folders, backups and files with multiple hard links are not selected. Possible space is a logical estimate, not guaranteed disk savings.",13,details.theme.muted));
        details.action("Done",!group.complete||group.eligibleCount()==0,false,null);
        if(group.complete&&group.eligibleCount()>0)details.action("Select extras in group",true,false,()->{
            if(duplicateReview.matches(group)){
                if(resultQuery.isEmpty())group.selectExtras();
                else for(DuplicateFinder.Member member:group.visible)
                    if(matchesResult(member.entry)&&group.eligible(member)&&!group.isSelected(member))group.toggle(member);
                duplicateChoicesChanged();
            }
        });
        details.show();
    }

    private String duplicateReason(Row row){
        if(row.review==null)return row.reason;
        if(row.review.keeper()==row.member)
            return row.member==row.review.proof.keep?(row.suggestedKeep?row.reason:"Suggested copy to keep.")
                    :"You chose this copy to keep for this review.";
        if(row.member.role==DuplicateFinder.Role.BACKUP_FOLDER)return "Backup folder · excluded from selection.";
        if(row.member.role==DuplicateFinder.Role.BACKUP_FILE)return "Backup filename · excluded from selection.";
        if(row.member.protectedCopy)return "Protected folder · excluded from selection.";
        if(row.member.evidence.linkCount!=1)return "Multiple hard links · excluded from selection.";
        return row.suggestedKeep?"This was the scanner’s suggested keeper. You chose a different copy to keep.":row.reason;
    }

    private void review(Row row) {
        if(closed||secondary!=null)return;
        AppSheet details=new AppSheet(activity,"Review file");
        secondary=details;details.setOnDismissListener(ignored->{if(secondary==details)secondary=null;});
        TextView title=details.text(row.entry.name,17,details.theme.ink);title.setTextIsSelectable(true);
        details.content.addView(title);
        TextView path=details.text(row.entry.path(),13,details.theme.muted);path.setTextIsSelectable(true);
        path.setPadding(0,dp(5),0,dp(12));details.content.addView(path);
        details.content.addView(details.text(fileSize(row.entry)+"  ·  "+duplicateReason(row),14,details.theme.ink));
        if(row.group!=null)details.content.addView(details.text(row.group+" · Keep at least one verified copy. Nothing is changed here.",13,details.theme.muted));
        else details.content.addView(details.text(row.suggestedReview?"Suggested to review. This does not mean the file is safe to remove."
                :"This file was flagged for review, not marked safe to remove.",13,details.theme.muted));
        DuplicateReview.Group group=row.review;
        if(group!=null&&group.complete&&group.keeper()!=row.member){
            Button keep=button("Keep this copy",R.drawable.ic_check,()->{
                if(duplicateReview.matches(group)&&group.chooseKeep(row.member)){
                    details.dismiss();duplicateChoicesChanged();
                }
            });
            details.content.addView(keep,new LinearLayout.LayoutParams(-1,dp(48)));
        }
        if(reveal!=null){
            details.action("Done",false,false,null);
            details.action("Show in Files",true,false,()->{
                sheet.dismiss();
                reveal.accept(row.entry.path());
            });
        }else details.action("Done",true,false,null);
        details.show();
    }

    private void close() {
        if(closed)return;
        closed=true;revision++;
        if(task!=null){task.cancel(true);task=null;}
        worker.shutdownNow();
        if(picker!=null){picker.dismiss();picker=null;}
        if(secondary!=null){secondary.dismiss();secondary=null;}
        ui.removeCallbacksAndMessages(null);
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount(){return display.size();}
        @Override public Object getItem(int position){return display.get(position);}
        @Override public long getItemId(int position){return position;}
        @Override public int getViewTypeCount(){return 2;}
        @Override public int getItemViewType(int position){return getItem(position) instanceof Row?0:1;}
        @Override public View getView(int position,View recycled,ViewGroup parent){
            Object item=getItem(position);
            if(item instanceof DuplicateReview.Group)return groupHeader((DuplicateReview.Group)item,recycled);
            LinearLayout row;
            if(recycled instanceof LinearLayout)row=(LinearLayout)recycled;
            else {
                row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(66));row.setPadding(dp(6),dp(7),dp(6),dp(7));
                ImageView icon=new ImageView(activity);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                icon.setPadding(dp(8),dp(8),dp(8),dp(8));
                icon.setBackground(AppSheet.shape(sheet.theme.selected,dp(12)));
                row.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));
                LinearLayout labels=new LinearLayout(activity);labels.setOrientation(LinearLayout.VERTICAL);
                labels.setPaddingRelative(dp(10),0,0,0);
                for(int i=0;i<3;i++){
                    TextView label=sheet.text("",i==0?14:12,i==0?sheet.theme.ink:sheet.theme.muted);
                    label.setSingleLine(true);label.setEllipsize(i==0?TextUtils.TruncateAt.MIDDLE:TextUtils.TruncateAt.START);
                    labels.addView(label,new LinearLayout.LayoutParams(-1,-2));
                }
                row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                CheckBox check=new CheckBox(activity);check.setFocusable(false);check.setFocusableInTouchMode(false);
                check.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},
                        new int[]{sheet.theme.accent,sheet.theme.muted}));
                row.addView(check,new LinearLayout.LayoutParams(dp(48),dp(48)));
            }
            Row entry=(Row)item;
            ((ImageView)row.getChildAt(0)).setImageResource(UiTheme.fileIcon(activity,entry.entry));
            LinearLayout labels=(LinearLayout)row.getChildAt(1);
            ((TextView)labels.getChildAt(0)).setText(entry.entry.name);
            DuplicateReview.Group group=entry.review;
            boolean eligible=group!=null&&group.eligible(entry.member);
            boolean selected=eligible&&group.isSelected(entry.member);
            String detail=entry.group==null?entry.reason:entry.group+"  ·  "+(entry.suggestedKeep?"Suggested keep":"Other exact copy");
            if(group!=null){
                if(group.keeper()==entry.member)detail=entry.member==group.proof.keep?"Suggested keep":"Keep for this review";
                else if(entry.member.role==DuplicateFinder.Role.BACKUP_FOLDER)detail="Backup · not selected";
                else if(entry.member.role==DuplicateFinder.Role.BACKUP_FILE)detail="Backup filename · not selected";
                else if(entry.member.protectedCopy)detail="Protected · not selected";
                else if(entry.member.evidence.linkCount!=1)detail="Multiple hard links · not selected";
                else detail=selected?"Selected for review":"Other exact copy";
            }
            ((TextView)labels.getChildAt(1)).setText(detail);
            ((TextView)labels.getChildAt(2)).setText(fileSize(entry.entry)+"  ·  "+entry.entry.parent);
            CheckBox check=(CheckBox)row.getChildAt(2);
            check.setChecked(selected);check.setVisibility(eligible?View.VISIBLE:View.GONE);
            check.setContentDescription((selected?"Deselect ":"Select ")+entry.entry.name+" for review");
            check.setOnClickListener(v->{if(group!=null&&duplicateReview.matches(group)&&group.toggle(entry.member))duplicateChoicesChanged();});
            row.setBackgroundColor(selected?sheet.theme.selected:Color.TRANSPARENT);
            return row;
        }

        private View groupHeader(DuplicateReview.Group group,View recycled){
            LinearLayout header=(LinearLayout)recycled;
            if(header==null){
                header=new LinearLayout(activity);header.setOrientation(LinearLayout.VERTICAL);
                header.setPadding(dp(9),dp(9),dp(9),dp(8));
                header.setBackground(AppSheet.shape(sheet.theme.chrome,dp(10)));
                TextView title=sheet.text("",14,sheet.theme.ink);
                title.setTypeface(activity.getResources().getFont(R.font.inter_semibold));
                if(android.os.Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);
                header.addView(title,new LinearLayout.LayoutParams(-1,-2));
                TextView bytes=sheet.text("",12,sheet.theme.muted);
                bytes.setPadding(0,dp(3),0,0);header.addView(bytes,new LinearLayout.LayoutParams(-1,-2));
            }
            ((TextView)header.getChildAt(0)).setText(group.label+"  ·  "+number(group.proof.members.size())+" copies");
            ((TextView)header.getChildAt(1)).setText(groupEstimate(group));
            return header;
        }
    }

    private EditText field(AppSheet panel,String title,String hint,String value,int cap,boolean multiline) {
        panel.content.addView(panel.text(title,13,panel.theme.muted));
        EditText input=new EditText(activity);input.setText(value);input.setTextSize(14);input.setHint(hint);
        input.setContentDescription(title);input.setSingleLine(!multiline);
        if(multiline){input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setMaxLines(4);}
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(cap)});
        panel.content.addView(input,new LinearLayout.LayoutParams(-1,-2));
        return input;
    }
    private Spinner choice(AppSheet panel,String title,String[] values,int value) {
        panel.content.addView(panel.text(title,13,panel.theme.muted));
        Spinner control=new Spinner(activity);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,values);
        control.setAdapter(adapter);control.setSelection(value);
        control.setContentDescription(title);
        panel.content.addView(control,new LinearLayout.LayoutParams(-1,dp(48)));return control;
    }
    private Button button(String title,int icon,Runnable action) {
        Button button=new Button(activity);
        button.setAllCaps(false);button.setText(title);button.setTextColor(sheet.theme.ink);button.setTextSize(13);
        button.setTypeface(activity.getResources().getFont(R.font.inter_medium));
        button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        button.setMinHeight(dp(48));button.setPadding(dp(8),dp(4),dp(8),dp(4));
        android.graphics.drawable.Drawable artwork=activity.getDrawable(icon);
        if(artwork!=null){
            artwork=artwork.mutate();artwork.setTint(sheet.theme.accent);artwork.setBounds(0,0,dp(17),dp(17));
            button.setCompoundDrawablesRelative(artwork,null,null,null);button.setCompoundDrawablePadding(dp(7));
        }
        button.setBackground(background(Color.TRANSPARENT));button.setOnClickListener(v->action.run());return button;
    }
    private RippleDrawable background(int fill) {
        return new RippleDrawable(ColorStateList.valueOf(sheet.theme.line),AppSheet.shape(fill,dp(10)),null);
    }
    private int dp(int value){return sheet.dp(value);}
    private String fileSize(FileEntry entry){return entry.size<0?"Size unknown":Formatter.formatShortFileSize(activity,entry.size);}
    private static String number(long value){return NumberFormat.getIntegerInstance().format(value);}

    static Map<String,String> validatedVolumes(Map<String,String> proposed) {
        Map<String,String> allowed=new LinkedHashMap<>();
        for(Map.Entry<String,String> entry:proposed.entrySet()){
            String path=normalize(entry.getKey());
            if(path!=null&&dev.fastsearch.core.CleanerRules.isSharedVolumeRoot(path))
                allowed.put(path,entry.getValue());
        }
        return Collections.unmodifiableMap(allowed);
    }

    static String normalize(String path) {
        if(path==null||!path.startsWith("/")||path.length()>4096||path.indexOf(0)>=0)return null;
        try {
            String normalized=new File(path).toPath().normalize().toString();
            if(isInside(normalized,"/sdcard"))return Environment.getExternalStorageDirectory().getAbsolutePath()
                    +normalized.substring("/sdcard".length());
            return normalized;
        }catch(RuntimeException invalid){return null;}
    }

    static boolean allowedScope(Collection<String> roots,String path) {
        if(path==null)return false;
        for(String root:roots)if(isInside(path,root)){
            String child=path.substring(root.length()).toLowerCase(java.util.Locale.ROOT);
            return !isInside(child,"/android/data")&&!isInside(child,"/android/obb")
                    &&!isInside(child,"/android/sandbox");
        }
        return false;
    }
    private static boolean isInside(String path,String dir){return path!=null&&(path.equals(dir)||path.startsWith(dir+"/"));}
    static boolean validExtensions(String value) {
        if(value==null||value.length()>400)return false;
        if(value.isEmpty())return true;
        String[] tokens=value.split("[,;]",-1);
        if(tokens.length>32)return false;
        for(String extension:tokens)if(!extension.trim().matches("[.]?[A-Za-z0-9]{1,32}"))return false;
        return true;
    }
}
