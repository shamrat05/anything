package dev.fastsearch.android;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.view.Menu;
import android.widget.ProgressBar;
import android.widget.TextView;
import dev.fastsearch.core.FileEntry;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Two independently navigable folders and identity-checked, no-replace transfers. */
final class DualPaneManager {
    static final int PREVIEW_REQUEST = 22;
    private static final int HISTORY_LIMIT = 24;
    private static final Comparator<FileEntry> ORDER = Comparator.comparing((FileEntry e) -> !e.directory)
            .thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER).thenComparing(e -> e.name);

    interface FileIntent { void launch(FileEntry entry, boolean share, boolean external); }

    static AppSheet open(Activity activity, FileIndex index, String scope, String internal,
                         Map<String, String> removable, boolean showHidden, Bundle previous,
                         Consumer<Bundle> remember, Consumer<Intent> preview, Consumer<AppSheet> dismissed,
                         FileIntent fileIntent, Consumer<FileEntry> properties, Consumer<String> copyPath, AppOwnerBadges appOwnerBadges) {
        return new DualPaneManager(activity, index, scope, internal, removable, showHidden,
                previous == null ? new Bundle() : previous, remember, preview, dismissed,fileIntent,properties,copyPath,appOwnerBadges).sheet;
    }

    private final Activity activity;
    private final FileIndex index;
    private final boolean useRoot, showHidden;
    private final AppSheet sheet;
    private final UiTheme theme;
    private final Consumer<Bundle> remember;
    private final Consumer<Intent> preview;
    private final FileIntent fileIntent;
    private final Consumer<FileEntry> properties;
    private final Consumer<String> copyPath;
    private final AppOwnerBadges appOwnerBadges;
    private final boolean clipboardPresentAtOpen;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final Map<String, String> locations = new LinkedHashMap<>();
    private final FileOperationService.Listener listener = this::operationChanged;
    private final Pane left, right;
    private final TextView state, activeLabel;
    private final LinearLayout transferActions;
    private final ProgressBar progress;
    private final Button copy, move, delete, stop, destination, sync;
    private final ImageButton back, forward, up;
    private Pane active;
    private AppSheet confirmation;
    private FolderPathDialog destinationPicker;
    private MenuDialog paneMenu;
    private final FileHoldActions fileActions;
    private final int[] fileScreen=new int[2];
    private final android.graphics.Rect fileVisible=new android.graphics.Rect();
    private boolean closed, preparing, opening, ownTransfer, ownDeletion, observedRunning;
    private String notice = "", transferTarget;

    private DualPaneManager(Activity activity, FileIndex index, String scope, String internal,
                            Map<String, String> removable, boolean showHidden, Bundle saved,
                            Consumer<Bundle> remember, Consumer<Intent> preview, Consumer<AppSheet> dismissed,
                         FileIntent fileIntent, Consumer<FileEntry> properties, Consumer<String> copyPath, AppOwnerBadges appOwnerBadges) {
        this.activity = activity;
        this.index = index;
        this.useRoot = index.isRootEnabled();
        this.showHidden = showHidden;
        this.remember = remember;
        this.preview = preview;
        this.fileIntent=fileIntent;this.properties=properties;this.copyPath=copyPath;this.appOwnerBadges=appOwnerBadges;
        clipboardPresentAtOpen=FileClipboard.count(activity)>0;
        locations.put(internal, "Internal storage");
        for (Map.Entry<String, String> volume : removable.entrySet()) locations.put(volume.getValue(), volume.getKey());
        for (String path : index.roots()) {
            if (valid(path) && !"/".equals(path)) {
                String label=new File(path).getName();
                if(BuildConfig.DEBUG&&label.startsWith("IndexProbe-UI-")){
                    try{
                        String cache=activity.getCacheDir().getCanonicalPath();
                        if(path.startsWith(cache+"/"))label="Test files";
                    }catch(IOException ignored){}
                }
                locations.putIfAbsent(path,label);
            }
        }
        if (useRoot && valid(scope) && !permitted(scope)) {
            locations.put(scope, "/".equals(scope) ? "Device root" : "Current location");
        }
        sheet = new AppSheet(activity, "Two-pane Files", true);
        fileActions=new FileHoldActions(sheet.theme,this::fileTargetAt,new FileHoldActions.QuickMode(
                ()->activity.getSharedPreferences("ui",0).getBoolean(QuickPreviewCard.PREFERENCE,false),
                index::isRootEnabled,worker));
        fileActions.setFolderSizes(index.folderSizes());
        sheet.fileHoldActions=fileActions;
        theme = sheet.theme;
        sheet.content.setPadding(0, dp(4), 0, dp(5));
        left = new Pane("Left", saved.getBundle("left"), permitted(scope) ? scope : internal);
        right = new Pane("Right", saved.getBundle("right"), internal);
        active = saved.getBoolean("rightActive") ? right : left;

        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackground(AppSheet.shape(theme.chrome, dp(12)));
        back = icon(R.drawable.ic_back, "Previous folder", () -> active.back());
        forward = icon(R.drawable.ic_back, "Next folder", () -> active.forward());
        forward.setRotation(180f);
        for (ImageButton control : new ImageButton[]{back, forward}) {
            toolbar.addView(control, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        activeLabel = sheet.text("", 13, theme.muted);
        activeLabel.setSingleLine(); activeLabel.setEllipsize(TextUtils.TruncateAt.END);
        toolbar.addView(activeLabel, new LinearLayout.LayoutParams(0, dp(48), 1));
        sync = new Button(activity);
        sync.setAllCaps(false); sync.setText("Sync"); sync.setTextColor(theme.ink);
        sync.setMinWidth(0); sync.setMinimumWidth(0); sync.setPadding(0, 0, 0, 0);
        sync.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.line), null,
                AppSheet.shape(Color.WHITE, dp(8))));
        sync.setOnClickListener(view -> synchronizeOther());
        toolbar.addView(sync, new LinearLayout.LayoutParams(dp(48), dp(48)));
        up = icon(R.drawable.ic_back, "Parent folder", () -> {
            String path = active.upperPath();
            if (path != null) active.go(path);
        });
        up.setRotation(90f);
        toolbar.addView(up, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ImageButton options = icon(R.drawable.ic_more, "Active pane options", () -> { });
        options.setOnClickListener(this::showPaneMenu);
        toolbar.addView(options, new LinearLayout.LayoutParams(dp(48), dp(48)));
        sheet.actions.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout panels = new LinearLayout(activity);
        panels.setOrientation(LinearLayout.HORIZONTAL);
        panels.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        sheet.content.addView(panels, new LinearLayout.LayoutParams(-1, 0, 1));
        panels.addView(left.panel, new LinearLayout.LayoutParams(0, -1, 1));
        View divider = new View(activity);
        divider.setBackgroundColor(theme.line);
        divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dividerSize = new LinearLayout.LayoutParams(Math.max(1, dp(1)), -1);
        dividerSize.setMargins(dp(3), 0, dp(3), 0);
        panels.addView(divider, dividerSize);
        panels.addView(right.panel, new LinearLayout.LayoutParams(0, -1, 1));
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(theme.accent));
        progress.setIndeterminateTintList(ColorStateList.valueOf(theme.accent));
        progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sheet.content.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        state = sheet.text("", 13, theme.muted);
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        state.setPadding(dp(8), dp(5), dp(8), dp(5));
        sheet.content.addView(state, new LinearLayout.LayoutParams(-1, -2));
        transferActions = new LinearLayout(activity);
        transferActions.setGravity(Gravity.CENTER_VERTICAL);
        sheet.content.addView(transferActions, new LinearLayout.LayoutParams(-1, -2));
        copy = action("Copy", () -> prepare(false));
        move = action("Move", () -> prepare(true));
        destination = action("Choose destination", () -> {
            Pane source = source();
            if (source == null) return;
            Pane target = source == left ? right : left;
            if (active != target) target.focus();
            else chooseLocation(target);
        });
        delete = action("Delete",this::deleteSelection);
        delete.setTextColor(theme.dark ? 0xFFFF9AAA : 0xFFBD3046);
        stop = action("Stop transfer", () -> { if (ownTransfer && FileOperationService.getState().running) FileOperationService.cancel(activity); });
        sheet.setBackNavigation(() -> active.back());
        sheet.setOnDismissListener(d -> { close(); dismissed.accept(sheet); });
        FileOperationService.addListener(listener);
        left.load(left.path, false);
        right.load(right.path, false);
        render();
    }

    private int dp(float size) { return sheet.dp(size); }

    private static boolean valid(String path) {
        if ("/".equals(path)) return true;
        try { FileOperationWorker.requirePath(path); return true; } catch (IOException bad) { return false; }
    }

    private String boundary(String path) {
        if (!valid(path)) return null;
        String result = null;
        for (String root : locations.keySet()) {
            if ((path.equals(root) || path.startsWith("/".equals(root) ? "/" : root + "/"))
                    && (result == null || root.length() > result.length())) result = root;
        }
        return result;
    }

    private boolean permitted(String path) { return boundary(path) != null; }

    private void close() {
        if (closed) return;
        closed = true;
        FileOperationService.removeListener(listener);
        left.cancel();
        right.cancel();
        if (confirmation != null) confirmation.dismiss();
        if (destinationPicker != null) destinationPicker.dismiss();
        if (paneMenu != null) paneMenu.dismiss();
        fileActions.stop();sheet.fileHoldActions=null;
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        rememberState();
    }

    private void rememberState() {
        Bundle saved = new Bundle();
        saved.putBundle("left", left.snapshot());
        saved.putBundle("right", right.snapshot());
        saved.putBoolean("rightActive", active == right);
        remember.accept(saved);
    }

    private Button action(String name, Runnable run) {
        Button button = new Button(activity);
        button.setText(name); button.setAllCaps(false); button.setTextColor(theme.ink);
        button.setMinimumWidth(0); button.setMinWidth(0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.line),
                AppSheet.shape(theme.chrome, dp(12)), AppSheet.shape(Color.WHITE, dp(12))));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, dp(48), 1);
        layout.setMargins(dp(3), 0, dp(3), 0);
        transferActions.addView(button, layout);
        button.setOnClickListener(v -> run.run());
        return button;
    }

    private ImageButton icon(int image, String label, Runnable run) {
        ImageButton button = new ImageButton(activity);
        button.setContentDescription(label); button.setTooltipText(label);
        button.setImageResource(image); button.setImageTintList(ColorStateList.valueOf(theme.ink));
        button.setPadding(dp(13), dp(13), dp(13), dp(13));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.line), null, AppSheet.shape(Color.WHITE, dp(12))));
        button.setOnClickListener(v -> run.run());
        return button;
    }

    private Pane source() {
        if (!left.selected.isEmpty()) return left;
        if (!right.selected.isEmpty()) return right;
        return null;
    }

    private void render() {
        if (closed || active == null) return;
        Pane from = source(), to = from == left ? right : left;
        FileOperationService.State op = FileOperationService.getState();
        boolean choosing = from != null && (!to.ready || from.path.equals(to.path) || "/".equals(to.path));
        boolean busy = preparing || opening || op.running;
        boolean choices = from != null && !busy;
        copy.setVisibility(choices && !choosing ? View.VISIBLE : View.GONE);
        move.setVisibility(choices && !choosing ? View.VISIBLE : View.GONE);
        copy.setEnabled(choices && from.ready && !choosing);
        move.setEnabled(choices && from.ready && !choosing);
        destination.setVisibility(choices && choosing ? View.VISIBLE : View.GONE);
        delete.setVisibility(choices ? View.VISIBLE : View.GONE);
        delete.setEnabled(choices && from.ready);
        stop.setText(ownDeletion ? "Stop deletion" : "Stop transfer");
        stop.setVisibility(ownTransfer && op.running ? View.VISIBLE : View.GONE);
        transferActions.setVisibility(choices || ownTransfer && op.running ? View.VISIBLE : View.GONE);

        progress.setVisibility(op.running || preparing ? View.VISIBLE : View.GONE);
        if (op.running || preparing) {
            boolean indeterminate = preparing || op.total <= 0 || op.completed <= 0 || op.completed >= op.total;
            if (progress.isIndeterminate() != indeterminate) progress.setIndeterminate(indeterminate);
            if (!indeterminate) { progress.setMax(op.total); progress.setProgress(op.completed); }
        }
        String message = "";
        if (op.running) message = ownTransfer && transferTarget != null ? op.status + "  ·  To " + shortFolder(transferTarget) : op.status;
        else if (preparing) message = "Checking the selected items…";
        else if (opening) message = "Opening preview…";
        else if (!notice.isEmpty()) message = notice;
        else if (from != null) {
            String count = from.selected.size() + " selected";
            if (to.loading) message = count + "  ·  Checking " + to.label.toLowerCase(java.util.Locale.ROOT) + " folder…";
            else if (!to.ready) message = count + "  ·  Destination couldn’t be opened";
            else if (choosing) message = count + "  ·  Open a different destination folder";
            else message = from.label + " → " + to.label + "  ·  " + count;
        }
        if (!TextUtils.equals(state.getText(), message)) state.setText(message);
        state.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        if (from != null) {
            copy.setContentDescription("Copy from " + from.label + " to " + to.label);
            move.setContentDescription("Move from " + from.label + " to " + to.label);
            delete.setContentDescription("Delete selected items in " + from.label.toLowerCase(java.util.Locale.ROOT) + " pane");
        }
        activeLabel.setText(active.label);
        Pane other = active == left ? right : left;
        back.setEnabled(!preparing && !active.history.isEmpty());
        forward.setEnabled(!preparing && !active.future.isEmpty());
        up.setEnabled(!preparing && active.upperPath() != null);
        back.setAlpha(back.isEnabled() ? 1f : .42f);
        forward.setAlpha(forward.isEnabled() ? 1f : .42f);
        up.setAlpha(up.isEnabled() ? 1f : .42f);
        describe(back, "Previous folder in " + active.label + " pane");
        describe(forward, "Next folder in " + active.label + " pane");
        describe(up, "Parent folder in " + active.label + " pane");
        sync.setEnabled(!preparing && active.ready && !active.path.equals(other.path));
        sync.setAlpha(sync.isEnabled() ? 1f : .42f);
        describe(sync, "Show " + active.label + " folder in " + other.label + " pane");
        left.chrome(from); right.chrome(from);
    }

    private static void describe(View control, String label) {
        if (TextUtils.equals(control.getContentDescription(), label)) return;
        control.setContentDescription(label);
        control.setTooltipText(label);
    }

    private void synchronizeOther() {
        if (preparing || !active.ready) return;
        Pane other = active == left ? right : left;
        if (other.path.equals(active.path)) return;
        // This is navigation, not a transfer. Never discard the current source selection by syncing over it.
        if (source() == other) {
            notice = "Clear the other pane’s selection to sync its folder.";
            render();
            return;
        }
        other.navigate(active.path, false);
        notice = other.label + " now shows " + shortFolder(active.path);
        render();
    }

    private void showPaneMenu(View anchor) {
        if (preparing) return;
        if (paneMenu != null) paneMenu.dismiss();
        Pane current=active;
        Menu model=theme.popup(activity,anchor).getMenu();
        model.add("Storage locations").setIcon(R.drawable.ic_locations);
        model.add("Refresh").setIcon(R.drawable.ic_refresh);
        boolean selectable=!opening&&!FileOperationService.getState().running&&current.ready;
        String all=current.entries.size()>FileClipboard.MAX_ITEMS
                ?"Select all (limit "+FileClipboard.MAX_ITEMS+")":"Select all";
        model.add(all).setIcon(R.drawable.ic_select).setEnabled(selectable&&!current.entries.isEmpty()
                &&current.entries.size()<=FileClipboard.MAX_ITEMS);
        model.add("Delete selected").setIcon(R.drawable.ic_delete)
                .setEnabled(source()!=null&&!FileOperationService.getState().running);
        model.add("Clear selection").setIcon(R.drawable.ic_close)
                .setEnabled(source()!=null&&!FileOperationService.getState().running);
        MenuDialog next=new MenuDialog(activity,theme,anchor,model,"Active pane",choice->{
            if(all.equals(choice)){current.selectAll();return;}
            switch(choice){
                case "Storage locations":chooseLocation(current);break;
                case "Refresh":current.refresh();break;
                case "Clear selection":clearSelection();break;
                case "Delete selected":deleteSelection();break;
            }
        });
        paneMenu=next;next.setOnDismissListener(closing->{if(paneMenu==next)paneMenu=null;});next.show();
    }

    private void clearSelection() {
        if (preparing || FileOperationService.getState().running) return;
        left.selected.clear(); left.adapter.notifyDataSetChanged();
        right.selected.clear(); right.adapter.notifyDataSetChanged();
        notice = ""; render();
    }

    private String shortFolder(String path) {
        String label = locations.get(path);
        return label != null ? label : new File(path).getName();
    }

    private String displayPath(String path) {
        String base = boundary(path);
        if (base == null) return path;
        if (base.equals(path)) return locations.get(base);
        return locations.get(base) + " / " + path.substring(base.equals("/") ? 1 : base.length() + 1)
                .replace("/", " / ");
    }

    private void operationChanged(FileOperationService.State op) {
        if (closed) return;
        if (ownTransfer && op.running) observedRunning = true;
        if (!op.running && ownTransfer && observedRunning) {
            ownTransfer = false; observedRunning = false;
            boolean deletion=ownDeletion;ownDeletion=false;
            boolean failed = op.error != null && !op.error.isEmpty();
            notice = !failed && transferTarget != null && !op.cancelled ? op.status + " to " + shortFolder(transferTarget)
                    : !failed ? op.status : deletion || op.completed == 0 ? op.error : op.status + "\n" + op.error;
            transferTarget = null;
            if (!failed || !deletion && op.completed > 0) { left.selected.clear(); right.selected.clear(); }
            left.reload(); right.reload();
        }
        render();
    }

    private void deleteSelection() {
        Pane from=source();
        if(closed||preparing||opening||FileOperationService.getState().running
                ||from==null||!from.ready||confirmation!=null&&confirmation.isShowing())return;
        confirmDelete(new ArrayList<>(from.selected.values()));
    }

    private void confirmDelete(List<FileEntry> captured){
        if(closed||preparing||opening||FileOperationService.getState().running
                ||confirmation!=null&&confirmation.isShowing())return;
        confirmation=FileDeletion.confirm(activity,captured,useRoot,worker,()->{
            ownTransfer=true;ownDeletion=true;observedRunning=false;transferTarget=null;notice="";
            render();
        },()->!closed&&(!useRoot||index.isRootEnabled()));
    }

    private void prepare(boolean moveFiles) {
        Pane from = source(), to = from == left ? right : left;
        if (closed || preparing || opening || FileOperationService.getState().running || from == null
                || !from.ready || !to.ready || from.path.equals(to.path) || "/".equals(to.path)) return;
        prepare(from,to,new ArrayList<>(from.selected.values()),moveFiles);
    }

    private void prepare(Pane from, Pane to, List<FileEntry> picked, boolean moveFiles) {
        if (closed || preparing || opening || FileOperationService.getState().running
                || !from.ready || !to.ready || from.path.equals(to.path) || "/".equals(to.path)) return;
        String target = to.path, sourcePath = from.path;
        preparing = true; notice = ""; render();
        try {
            worker.execute(() -> {
                try {
                    FileClipboard.Snapshot snapshot = FileClipboard.capture(activity.getApplicationContext(), picked, moveFiles, useRoot);
                    main.post(() -> {
                        if (closed) return;
                        preparing = false;
                        confirm(snapshot, picked, sourcePath, target, moveFiles);
                        render();
                    });
                } catch (Exception error) {
                    main.post(() -> {
                        if (closed) return;
                        preparing = false; notice = errorText(error, "Could not prepare those items."); render();
                    });
                }
            });
        } catch (RejectedExecutionException ending) {
            preparing = false; notice = "File access is busy."; render();
        }
    }

    private void confirm(FileClipboard.Snapshot snapshot, List<FileEntry> picked, String sourcePath,
                         String target, boolean moveFiles) {
        if (FileOperationService.getState().running) { notice = "Another file operation is running."; return; }
        String count = picked.size() == 1 ? "1 item" : picked.size() + " items";
        confirmation = new AppSheet(activity, (moveFiles ? "Move " : "Copy ") + count + "?");
        AppSheet prompt = confirmation;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(3, picked.size()); i++) {
            if (i > 0) names.append('\n');
            names.append(picked.get(i).name);
        }
        if (picked.size() > 3) names.append("\n+").append(picked.size() - 3).append(" more");
        TextView itemNames = prompt.text(names.toString(), 14, theme.ink);
        itemNames.setMaxLines(4); itemNames.setEllipsize(TextUtils.TruncateAt.END);
        prompt.content.addView(itemNames, new LinearLayout.LayoutParams(-1, -2));
        addConfirmationPath(prompt, "From", sourcePath);
        addConfirmationPath(prompt, "To", target);
        String safety = moveFiles ? "Existing files are never replaced. Use Copy to transfer between volumes."
                : "Existing files are never replaced.";
        TextView safetyLabel = prompt.text(safety, 12, theme.muted);
        safetyLabel.setPadding(0, dp(12), 0, 0);
        prompt.content.addView(safetyLabel, new LinearLayout.LayoutParams(-1, -2));
        prompt.action("Cancel", false, false, null);
        prompt.action(moveFiles ? "Move here" : "Copy here", true, false, () -> {
            if (closed) return;
            try {
                if (useRoot && !index.isRootEnabled()) throw new IOException("Root file access changed.");
                ownTransfer = true; observedRunning = false; transferTarget = target;
                FileOperationService.transfer(activity, target, snapshot);
                notice = "";
            } catch (IOException error) {
                ownTransfer = false; transferTarget = null; notice = errorText(error, "Could not start the transfer.");
            }
            render();
        });
        prompt.setOnDismissListener(d -> { if (confirmation == prompt) confirmation = null; });
        prompt.show();
    }

    private void addConfirmationPath(AppSheet prompt, String label, String path) {
        TextView heading = prompt.text(label, 12, theme.muted);
        heading.setPadding(0, dp(10), 0, 0);
        TextView folder = prompt.text(path, 13, theme.ink);
        folder.setTextIsSelectable(true);
        prompt.content.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        prompt.content.addView(folder, new LinearLayout.LayoutParams(-1, -2));
    }

    private static String errorText(Exception error, String fallback) {
        return error instanceof IOException && error.getMessage() != null ? error.getMessage() : fallback;
    }

    private void showFileMenu(Pane pane, View anchor, FileEntry entry) {
        if(closed||opening||!pane.ready)return;
        View row=anchor.getParent() instanceof LinearLayout?(View)anchor.getParent():anchor;
        if(pane.selected.containsKey(entry.path())){
            showPaneSelection(pane,row);
        }else fileActions.begin(fileTarget(pane,row,entry));
    }

    private void showPaneSelection(Pane pane,View row){
        if(paneMenu!=null)paneMenu.dismiss();
        Pane peer=pane==left?right:left;
        List<FileEntry> captured=new ArrayList<>(pane.selected.values());
        boolean ready=!captured.isEmpty()&&!preparing&&!opening&&!FileOperationService.getState().running;
        boolean transfer=ready&&peer.ready&&!pane.path.equals(peer.path)&&!"/".equals(peer.path);
        Menu model=theme.popup(activity,row).getMenu();
        model.add("Copy to other pane").setIcon(R.drawable.ic_copy).setEnabled(transfer);
        model.add("Move to other pane").setIcon(R.drawable.ic_cut).setEnabled(transfer);
        model.add("Rename").setIcon(R.drawable.ic_rename).setEnabled(ready);
        model.add("Delete selected").setIcon(R.drawable.ic_delete).setEnabled(ready);
        model.add("Clear selection").setIcon(R.drawable.ic_close).setEnabled(ready);
        MenuDialog next=new MenuDialog(activity,theme,row,model,
                captured.size()==1?"1 item selected":captured.size()+" items selected",2,action->{
            if(closed||preparing||opening||FileOperationService.getState().running)return;
            pane.focus();
            switch(action){
                case "Copy to other pane":case "Move to other pane":
                    prepare(pane,peer,captured,action.startsWith("Move"));break;
                case "Rename":confirmRename(captured);break;
                case "Delete selected":confirmDelete(captured);break;
                case "Clear selection":clearSelection();break;
            }
        });
        paneMenu=next;next.setOnDismissListener(closing->{if(paneMenu==next)paneMenu=null;});next.show();
    }

    private void confirmRename(List<FileEntry> entries){
        if(closed||preparing||opening||FileOperationService.getState().running
                ||confirmation!=null&&confirmation.isShowing())return;
        confirmation=FileRename.show(activity,entries,useRoot,worker,()->{
            ownTransfer=true;ownDeletion=false;observedRunning=FileOperationService.getState().running;
            transferTarget=null;notice="";render();
        });
    }

    private FileHoldActions.Target fileTargetAt(float x,float y){
        for(Pane pane:new Pane[]{left,right}){
            int position=FileHoldActions.position(pane.list,x,y,fileScreen,fileVisible);
            if(!pane.ready||position<0||position>=pane.entries.size())continue;
            View row=pane.list.getChildAt(position-pane.list.getFirstVisiblePosition());
            if(row!=null)return fileTarget(pane,row,pane.entries.get(position));
        }
        return null;
    }

    private FileHoldActions.Target fileTarget(Pane pane,View anchor,FileEntry entry){
        Pane peer=pane==left?right:left;
        boolean transfer=peer.ready&&!pane.path.equals(peer.path)&&!"/".equals(peer.path)
                &&!preparing&&!FileOperationService.getState().running;
        FileHoldActions.Target target=new FileHoldActions.Target(anchor,entry,true,transfer,action->{
            if(closed)return;
            pane.focus();
            switch(action){
                case "Open":if(entry.directory)pane.go(entry.path());else openFile(pane,entry);break;
                case "Share":fileIntent.launch(entry,true,false);break;
                case "Open with":fileIntent.launch(entry,false,true);break;
                case "Copy to other pane":case "Move to other pane":
                    prepare(pane,peer,java.util.Collections.singletonList(entry),action.startsWith("Move"));break;
                case "Rename":confirmRename(java.util.Collections.singletonList(entry));break;
                case "Delete":confirmDelete(java.util.Collections.singletonList(entry));break;
                case "Properties":properties.accept(entry);break;
                case "Copy path":copyPath.accept(entry.path());break;
                case "Select items":if(!pane.selected.containsKey(entry.path()))pane.toggle(entry);break;
            }
        });
        List<FileEntry> shown=pane.entries;
        target.withPreviewOrder(shown.size(),shown::get,e->fileTarget(pane,anchor,e));
        String destination=pane.path;
        if(!entry.directory&&clipboardPresentAtOpen&&destination.equals(entry.parent)&&!"/".equals(destination)){
            target.withQuickPaste(destination,()->{
                if(closed||!pane.ready||!destination.equals(pane.path))return;
                if(confirmation!=null&&confirmation.isShowing())return;
                confirmation=QuickPasteConfirmation.show(activity,destination,
                        ()->!closed&&index.isRootEnabled(),()->{
                            ownTransfer=true;ownDeletion=false;observedRunning=FileOperationService.getState().running;
                            transferTarget=destination;notice="";render();
                        },failure->{notice=failure;render();});
            });
        }
        return target;
    }

    private void openFile(Pane pane, FileEntry entry) {
        if (opening || preparing || !pane.ready) return;
        opening = true; notice = ""; render();
        List<FileEntry> contents = pane.entries;
        Pane peer=pane==left?right:left;
        String peerTarget=peer.ready?peer.path:null;
        try {
            worker.execute(() -> {
                PreviewSession session = PreviewSession.create(contents, entry.path(), useRoot);
                activity.runOnUiThread(() -> {
                    if (closed || activity.isFinishing() || activity.isDestroyed()) {
                        PreviewSession.release(session.id);
                        return;
                    }
                    opening = false;
                    try {
                        Intent intent = FileViewerActivity.intent(activity, null, entry.name, entry.path(), entry.size)
                                .putExtra(PreviewSession.EXTRA, session.id);
                        if(peerTarget!=null)intent.putExtra(FileViewerActivity.EXTRA_OTHER_PANE,peerTarget);
                        preview.accept(intent);
                        sheet.dismiss();
                    } catch (RuntimeException error) {
                        PreviewSession.release(session.id); notice = "Preview could not open."; render();
                    }
                });
            });
        } catch (RejectedExecutionException ending) { opening = false; notice = "File access is busy."; render(); }
    }

    private void chooseLocation(Pane pane) {
        if (closed || preparing || destinationPicker != null) return;
        destinationPicker = new FolderPathDialog(activity, index, pane.label + " pane folder",
                pane.path, value -> value, pane::go, () -> destinationPicker = null,
                locations, this::permitted, showHidden, false);
        destinationPicker.show();
    }

    private final class Pane {
        final String label;
        final LinearLayout panel, header;
        final TextView role, pathLabel, empty;
        final ListView list;
        final Button retry;
        final BrowserRows adapter = new BrowserRows();
        final Map<String, FileEntry> selected = new LinkedHashMap<>();
        final ArrayList<String> history = new ArrayList<>(), future = new ArrayList<>();
        String path;
        List<FileEntry> entries = Collections.emptyList();
        CancellationSignal cancellation;
        Future<?> request;
        boolean ready, loading;

        Pane(String label, Bundle saved, String initial) {
            this.label = label;
            path = saved != null && permitted(saved.getString("path")) ? saved.getString("path") : initial;
            if (saved != null && path.equals(saved.getString("path"))) {
                restoreHistory(history, saved.getStringArrayList("history"));
                restoreHistory(future, saved.getStringArrayList("future"));
            }

            panel = new LinearLayout(activity);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setLayoutDirection(activity.getResources().getConfiguration().getLayoutDirection());
            header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.VERTICAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(7), dp(6), dp(7), dp(6)); header.setMinimumHeight(dp(50));
            header.setFocusable(true);
            header.setOnClickListener(v -> focus());
            header.setOnLongClickListener(v -> { focus(); chooseLocation(this); return true; });
            role = sheet.text("", 12, theme.muted); role.setSingleLine();
            role.setTypeface(activity.getResources().getFont(R.font.inter_medium));
            role.setEllipsize(TextUtils.TruncateAt.END);
            pathLabel = sheet.text("", 12, theme.ink);
            pathLabel.setSingleLine(); pathLabel.setEllipsize(TextUtils.TruncateAt.START);
            for (TextView labelView : new TextView[]{role, pathLabel}) {
                labelView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                header.addView(labelView, new LinearLayout.LayoutParams(-1, -2));
            }
            panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout emptyBody = new LinearLayout(activity);
            emptyBody.setOrientation(LinearLayout.VERTICAL); emptyBody.setGravity(Gravity.CENTER);
            emptyBody.setPadding(dp(12), dp(8), dp(12), dp(8));
            empty = sheet.text("", 14, theme.muted); empty.setGravity(Gravity.CENTER);
            emptyBody.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            retry = new Button(activity); retry.setText("Retry"); retry.setAllCaps(false);
            retry.setMinHeight(dp(48)); retry.setMinWidth(dp(72)); retry.setTextColor(theme.ink); retry.setOnClickListener(v -> refresh());
            emptyBody.addView(retry, new LinearLayout.LayoutParams(-2, -2));
            panel.addView(emptyBody, new LinearLayout.LayoutParams(-1, 0, 1));
            list = new ListView(activity);
            list.setAdapter(adapter); list.setItemsCanFocus(true);
            list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener(){
                @Override public void onScrollStateChanged(android.widget.AbsListView view,int state){}
                @Override public void onScroll(android.widget.AbsListView view,int first,int visible,int total){appOwnerBadges.viewportChanged();}
            });
            list.setDivider(new android.graphics.drawable.ColorDrawable(theme.line));
            list.setDividerHeight(1);
            panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            list.setEmptyView(emptyBody);
        }

        Bundle snapshot() {
            Bundle result = new Bundle();
            result.putString("path", path);
            result.putStringArrayList("history", new ArrayList<>(history));
            result.putStringArrayList("future", new ArrayList<>(future));
            return result;
        }

        void focus() { if (active != this) { active = this; rememberState(); render(); } }

        private void restoreHistory(ArrayList<String> stack, ArrayList<String> saved) {
            if (saved == null) return;
            for (String location : saved) if (permitted(location)) pushHistory(stack, location);
        }

        private void pushHistory(ArrayList<String> stack, String at) {
            if (stack.size() >= HISTORY_LIMIT) stack.remove(0);
            stack.add(at);
        }

        private String upperPath() {
            String base = boundary(path);
            String upper = new File(path).getParent();
            return base != null && !path.equals(base) && permitted(upper) ? upper : null;
        }

        boolean back() { return traverse(history, future); }
        boolean forward() { return traverse(future, history); }

        private boolean traverse(ArrayList<String> from, ArrayList<String> to) {
            if (from.isEmpty() || preparing) return false;
            focus(); notice = "";
            pushHistory(to, path);
            load(from.remove(from.size() - 1), false);
            return true;
        }

        void go(String next) { navigate(next, true); }

        void navigate(String next, boolean selectPane) {
            if (preparing || !permitted(next) || next.equals(path)) return;
            if (selectPane) focus();
            pushHistory(history, path); future.clear(); notice = ""; load(next, false);
        }

        void refresh() { notice = ""; reload(); }
        void reload() { if (!preparing && !closed) load(path, true); }

        void chrome(Pane from) {
            boolean current = active == this;
            String meaning = from == null ? label : label + " · " + (from == this ? "Source" : "Destination");
            role.setText(meaning);
            role.setTextColor(current ? theme.accent : theme.muted);
            pathLabel.setText(displayPath(path));
            header.setContentDescription(meaning + ", " + displayPath(path));
            if (header.isSelected() != current || header.getBackground() == null) {
                header.setSelected(current);
                header.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.line),
                        AppSheet.shape(current ? theme.selected : theme.chrome, dp(9)),
                        AppSheet.shape(Color.WHITE, dp(9))));
            }
        }

        void cancel() {
            if (cancellation != null) cancellation.cancel();
            if (request != null) request.cancel(true);
        }

        void load(String next, boolean keepSelected) {
            cancel();
            CancellationSignal signal = new CancellationSignal();
            cancellation = signal; path = next; ready = false; loading = true;
            rememberState();
            if (!keepSelected) selected.clear();
            entries = Collections.emptyList(); empty.setText("Opening folder…");
            retry.setVisibility(View.GONE); adapter.notifyDataSetChanged(); render();
            try {
                request = worker.submit(() -> {
                    List<FileEntry> delivered = Collections.emptyList();
                    String error = "";
                    try {
                        if (!permitted(next) || useRoot && !index.isRootEnabled()) throw new IOException("This location is no longer available.");
                        List<FileEntry> children = index.listFolder(next, signal);
                        children.removeIf(entry -> !showHidden && entry.hidden);
                        children.sort(ORDER); signal.throwIfCanceled(); delivered = children;
                    } catch (OperationCanceledException abandoned) { return; }
                    catch (Exception problem) { error = errorText(problem, "This folder could not be read."); }
                    List<FileEntry> result = delivered;
                    String problem = error;
                    main.post(() -> {
                        if (closed || signal.isCanceled() || cancellation != signal) return;
                        entries = result; ready = problem.isEmpty(); loading = false;
                        if (keepSelected && !selected.isEmpty()) {
                            Map<String, FileEntry> remaining = new LinkedHashMap<>();
                            for (FileEntry entry : entries) if (selected.containsKey(entry.path())) remaining.put(entry.path(), entry);
                            selected.clear(); selected.putAll(remaining);
                        }
                        empty.setText(ready ? "No items in this folder" : problem);
                        retry.setVisibility(ready ? View.GONE : View.VISIBLE); adapter.notifyDataSetChanged(); render();
                    });
                });
            } catch (RejectedExecutionException ending) {
                loading = false; empty.setText("File access is busy."); retry.setVisibility(View.VISIBLE); render();
            }
        }

        void selectAll() {
            if (!ready || preparing || opening || FileOperationService.getState().running
                    || entries.isEmpty() || entries.size() > FileClipboard.MAX_ITEMS) return;
            focus(); Pane other = this == left ? right : left;
            other.selected.clear(); other.adapter.notifyDataSetChanged();
            selected.clear();
            for (FileEntry entry : entries) selected.put(entry.path(), entry);
            notice = ""; adapter.notifyDataSetChanged(); render();
        }

        private void toggle(FileEntry entry) {
            if (!ready || preparing || opening || FileOperationService.getState().running) return;
            focus(); Pane other = this == left ? right : left;
            if (selected.remove(entry.path()) == null) {
                if (selected.size() >= FileClipboard.MAX_ITEMS) { notice = "Select no more than " + FileClipboard.MAX_ITEMS + " items."; render(); return; }
                other.selected.clear(); other.adapter.notifyDataSetChanged();
                selected.put(entry.path(), entry);
            }
            notice = ""; adapter.notifyDataSetChanged(); render();
        }

        private final class BrowserRows extends BaseAdapter {
            private final ColorStateList choiceTint = new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{theme.accent, theme.muted});

            @Override public int getCount() { return entries.size(); }
            @Override public FileEntry getItem(int position) { return entries.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout row, item;
                TextView title;
                ImageView picture, badge, ownerMark;
                CheckBox checked;
                if (recycled == null) {
                    row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
                    item = new LinearLayout(activity); item.setGravity(Gravity.CENTER_VERTICAL);
                    item.setPadding(dp(5), 0, dp(2), 0); item.setMinimumHeight(dp(48));
                    item.setClipChildren(false);
                    android.widget.FrameLayout artwork=new android.widget.FrameLayout(activity);
                    artwork.setClipChildren(false);
                    picture = new ImageView(activity);
                    picture.setId(R.id.file_icon);
                    picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    artwork.addView(picture,new android.widget.FrameLayout.LayoutParams(-1,-1));
                    badge=new ImageView(activity);
                    badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    badge.setImageDrawable(FileSelectionMark.create(activity,theme));
                    android.widget.FrameLayout.LayoutParams badgeLayout=new android.widget.FrameLayout.LayoutParams(dp(9),dp(9),Gravity.LEFT|Gravity.TOP);
                    badgeLayout.leftMargin=dp(13);badgeLayout.topMargin=-dp(2);
                    artwork.addView(badge,badgeLayout);
                    ownerMark=AppOwnerBadges.overlay(activity);
                    android.widget.FrameLayout.LayoutParams ownerLayout=new android.widget.FrameLayout.LayoutParams(dp(9),dp(9),Gravity.LEFT|Gravity.TOP);
                    ownerLayout.leftMargin=dp(12);ownerLayout.topMargin=dp(12);artwork.addView(ownerMark,ownerLayout);
                    item.addView(artwork, new LinearLayout.LayoutParams(dp(16), dp(16)));
                    title = sheet.text("", 14, theme.ink);
                    title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    title.setPadding(dp(5), 0, 0, 0);
                    title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    item.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
                    checked = new CheckBox(activity);
                    checked.setId(R.id.file_selection_mark);
                    checked.setButtonTintList(choiceTint);
                    row.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
                    row.addView(checked, new LinearLayout.LayoutParams(dp(48), dp(48)));
                    row.setTag(FileSelectionMark.outline(theme,dp(1),dp(5)));
                } else {
                    row = (LinearLayout) recycled; item = (LinearLayout) row.getChildAt(0);
                    checked = (CheckBox) row.getChildAt(1);
                    android.widget.FrameLayout artwork=(android.widget.FrameLayout)item.getChildAt(0);
                    picture = (ImageView) artwork.getChildAt(0);badge=(ImageView)artwork.getChildAt(1);ownerMark=(ImageView)artwork.getChildAt(2);
                    title = (TextView) item.getChildAt(1);
                }
                FileEntry entry = getItem(position);
                boolean picked = selected.containsKey(entry.path());
                row.setBackgroundColor(picked ? theme.selected : Color.TRANSPARENT);
                row.setForeground(picked ? (android.graphics.drawable.Drawable)row.getTag() : null);
                row.setActivated(picked);
                badge.setVisibility(picked?View.VISIBLE:View.INVISIBLE);
                int type=FileTypes.category(entry);
                picture.setImageResource(UiTheme.fileIcon(activity,entry));
                picture.setImageTintList(UiTheme.fileTint(activity,type));
                title.setText(entry.name);
                String description=(entry.directory?"Open folder ":"Preview ")+entry.name;
                item.setContentDescription(description);
                appOwnerBadges.bind(item,entry,value->{
                    AppOwnerBadges.present(ownerMark,theme,value);
                    item.setContentDescription(AppOwnerBadges.describe(description,value));
                });
                item.setFocusable(true);
                item.setOnClickListener(view -> { focus(); if (entry.directory) go(entry.path()); else openFile(Pane.this, entry); });
                item.setOnLongClickListener(view -> { showFileMenu(Pane.this,view,entry); return true; });
                checked.setChecked(picked);
                checked.setContentDescription((picked?"Deselect ":"Select ") + entry.name);
                checked.setOnClickListener(view -> { toggle(entry); checked.setChecked(selected.containsKey(entry.path())); });
                checked.setOnLongClickListener(view -> { showFileMenu(Pane.this,view,entry); return true; });
                return row;
            }
        }
    }
}
