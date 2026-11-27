package dev.fastsearch.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A separate explicitly opened screen. No DEX is ever passed to an Android class loader. */
public final class DexViewerActivity extends Activity {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(3), task -> new Thread(task, "dex-inspector"),
            new ThreadPoolExecutor.AbortPolicy());
    static { WORKER.allowCoreThreadTimeOut(true); }
    private static final int CREATE_SMALI = 923;
    private static final Handler EXPORT_MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor EXPORT = new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> new Thread(task,"smali-export"),new ThreadPoolExecutor.AbortPolicy());
    static { EXPORT.allowCoreThreadTimeOut(true); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private UiTheme theme;
    private TextView heading, status;
    private FrameLayout content;
    private EditText search;
    private ListView classList;
    private ClassAdapter adapter;
    private List<DexInspector.Item> classes = Collections.emptyList();
    private final CancellationSignal signal = new CancellationSignal();
    private Future<?> work;
    private long generation;
    private boolean showingCode, destroyed;
    private String restoreItem;
    private Button findCode, dexPicker, saveCode;
    private List<String> dexEntries = Collections.emptyList();
    private String selectedDex;
    private AlertDialog dexDialog;
    private byte[] saveBytes;
    private String saveDigest, exportKey, exportDigest;
    private Uri exportDestination;
    private SmaliCodePane codePane;
    private Bundle restoreFind;
    private final Runnable refreshResults = this::filterClasses;

    static Intent intent(Context context, Uri uri, String name, String path, String identity, boolean root, boolean apk) {
        return new Intent(context, DexViewerActivity.class).setData(uri)
                .putExtra("name", name).putExtra("path", path).putExtra("identity", identity)
                .putExtra("root", root).putExtra("apk", apk);
    }

    @Override protected void onCreate(Bundle saved) {
        theme = UiTheme.apply(this);
        super.onCreate(saved);
        theme.bars(this);
        LinearLayout screen = column();
        screen.setBackgroundColor(theme.surface);
        screen.setFitsSystemWindows(true);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(theme.chrome);
        Button back = new Button(this);
        back.setText("‹"); back.setTextSize(28); back.setAllCaps(false); back.setTextColor(theme.accent);
        back.setContentDescription("Back"); back.setBackground(null); back.setMinWidth(dp(52)); back.setMinHeight(dp(52));
        back.setOnClickListener(v -> handleBack());
        bar.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        heading = label("DEX classes", 20, theme.ink);
        heading.setTypeface(getResources().getFont(R.font.inter_semibold));
        heading.setMaxLines(1); heading.setEllipsize(TextUtils.TruncateAt.END);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        bar.addView(heading,new LinearLayout.LayoutParams(0, -2, 1));
        findCode = new Button(this);
        findCode.setAllCaps(false); findCode.setTextColor(theme.accent); findCode.setBackground(null);
        findCode.setText("Find"); findCode.setContentDescription("Find in Smali");
        findCode.setMinWidth(dp(54)); findCode.setMinHeight(dp(48));
        findCode.setVisibility(View.GONE);
        findCode.setOnClickListener(v -> {
            if (codePane == null) return;
            if (codePane.isFinding()) codePane.closeFind();
            else codePane.openFind(true);
            updateFindButton();
        });
        bar.addView(findCode,new LinearLayout.LayoutParams(-2,dp(56)));
        dexPicker=toolbarAction("DEX files","Filter by DEX file",this::showDexPicker);
        bar.addView(dexPicker,new LinearLayout.LayoutParams(-2,dp(56)));
        saveCode=toolbarAction("Save","Save Smali as",this::chooseSmaliDestination);
        bar.addView(saveCode,new LinearLayout.LayoutParams(-2,dp(56)));
        screen.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        search = new EditText(this);
        search.setSingleLine(); search.setHint("Find a class"); search.setTextSize(16);
        search.setTextColor(theme.ink); search.setHintTextColor(theme.muted);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        LinearLayout.LayoutParams searchLayout = new LinearLayout.LayoutParams(-1, dp(53)); searchLayout.setMargins(dp(16), 0, dp(16), 0);
        screen.addView(search, searchLayout);
        search.setEnabled(false);
        status = label("Reading classes…", 13, theme.muted);
        status.setPadding(dp(16),dp(8),dp(16),dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        screen.addView(status, new LinearLayout.LayoutParams(-1,-2));
        content = new FrameLayout(this);
        screen.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        classList = new ListView(this);
        classList.setDividerHeight(dp(1)); classList.setFastScrollEnabled(true);
        adapter = new ClassAdapter();
        classList.setAdapter(adapter); classList.setOnItemClickListener((p, v, pos, id) -> openClass(adapter.items.get(pos)));
        if (saved != null) {
            search.setText(saved.getString("query", ""));
            restoreItem = saved.getString("class");
            restoreFind = saved.getBundle("smaliFind");
            selectedDex = saved.getString("dexScope");
            exportKey = saved.getString("exportKey"); exportDigest = saved.getString("exportDigest");
            exportDestination = saved.getParcelable("exportDestination");
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!search.isEnabled()) return;
                main.removeCallbacks(refreshResults); main.postDelayed(refreshResults, 125);
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        bar.setFocusableInTouchMode(true); bar.requestFocus();
        setContentView(screen);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        startIndex();
    }

    private void startIndex() {
        showSpinner();
        submit(() -> {
            Intent source = getIntent();
            Uri uri = source.getData();
            String identity = source.getStringExtra("identity"), path = source.getStringExtra("path");
            if (isFinishing() || identity == null || (uri == null && (path == null || path.isEmpty()))
                    || (uri != null && (!"content".equals(uri.getScheme()) || !(getPackageName()+".files").equals(uri.getAuthority()))))
                throw new IOException("Reopen the file in Anything before browsing its DEX classes.");
            DexInspector.Index indexed;
            try (ParcelFileDescriptor file = uri == null ? SafeFileAccess.open(getApplicationContext(), path, source.getBooleanExtra("root", false))
                    : getContentResolver().openFileDescriptor(uri, "r", signal)) {
                if (file == null || !identity.equals(RootFileProvider.identity(Os.fstat(file.getFileDescriptor()))))
                    throw new IOException("The file changed. Reopen it before inspecting.");
                try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                        ParcelFileDescriptor.dup(file.getFileDescriptor()))) {
                    int budget = (int) Math.min(DexInspector.MAX_ALL_BYTES, Runtime.getRuntime().maxMemory() / 5);
                    indexed = DexInspector.index(input.getChannel(), source.getBooleanExtra("apk", false), budget);
                }
                if (!identity.equals(RootFileProvider.identity(Os.fstat(file.getFileDescriptor()))))
                    throw new IOException("The file changed during inspection. Reopen it and try again.");
            }
            return () -> {
                classes = indexed.items;
                dexEntries = DexEntryPicker.ordered(indexed.entries);
                if (!dexEntries.contains(selectedDex)) selectedDex=null;
                showClassList();
                if (restoreItem != null) {
                    String last = restoreItem; restoreItem = null;
                    for (DexInspector.Item item : classes) if (item.key().equals(last)) { openClass(item); return; }
                    restoreFind = null;
                    if (exportKey != null) rejectExport("The selected class is no longer available.");
                }
            };
        });
    }

    private void showClassList() {
        showingCode = false;
        selectedKey = null;
        if (codePane != null) { codePane.closeFind(); codePane = null; }
        findCode.setVisibility(View.GONE);
        saveCode.setVisibility(View.GONE);
        saveBytes=null; saveDigest=null;
        dexPicker.setVisibility(dexEntries.size()>1?View.VISIBLE:View.GONE);
        updateDexPicker();
        heading.setText("DEX classes");
        search.setVisibility(View.VISIBLE);
        search.setEnabled(true);
        content.removeAllViews();
        content.addView(classList,new FrameLayout.LayoutParams(-1,-1));
        filterClasses();
    }

    private void filterClasses() {
        if (showingCode || destroyed) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT).replace('/', '.');
        List<DexInspector.Item> full = classes;
        String dexScope=selectedDex;
        if (query.isEmpty() && dexScope==null) { cancelWork(); showFiltered(full); return; }
        submit(() -> {
            List<DexInspector.Item> filtered = new ArrayList<>();
            for (DexInspector.Item item : full) {
                if (Thread.currentThread().isInterrupted()) return () -> {};
                if ((dexScope==null || dexScope.equals(item.dex))
                        && (item.match.contains(query) || item.dex.toLowerCase(Locale.ROOT).contains(query))) filtered.add(item);
            }
            return () -> showFiltered(filtered);
        });
    }
    private void showFiltered(List<DexInspector.Item> rows) {
        if (showingCode) return;
        adapter.items = rows; adapter.notifyDataSetChanged();
        status.setText(rows.isEmpty() ? (classes.isEmpty() ? "No classes are defined." : "No matching classes.")
                : java.text.NumberFormat.getIntegerInstance().format(rows.size()) + (rows.size() == 1 ? " class" : " classes") + " · Read-only Smali");
    }

    private String selectedKey;
    private void openClass(DexInspector.Item item) {
        main.removeCallbacks(refreshResults);
        showingCode = true;
        selectedKey = item.key();
        heading.setText("Smali");
        dexPicker.setVisibility(View.GONE);
        saveCode.setVisibility(View.GONE);
        search.setVisibility(View.GONE);
        status.setText(item.name + " · " + item.dex);
        showSpinner();
        submit(() -> {
            DexInspector.Disassembly disassembly = DexInspector.disassemble(item);
            CharSequence code = colorize(disassembly.text);
            byte[] bytes;
            try { bytes=disassembly.complete ? SmaliExportPlan.bytes(disassembly.text) : null; }
            catch(IOException notExportable) { bytes=null; }
            final byte[] snapshot=bytes;
            String digest=snapshot==null?null:SmaliExportPlan.digest(snapshot);
            return () -> showCode(code,snapshot,digest);
        });
    }

    private void showCode(CharSequence code,byte[] bytes,String digest) {
        content.removeAllViews();
        codePane = new SmaliCodePane(this,theme,code);
        content.addView(codePane,new FrameLayout.LayoutParams(-1,-1));
        codePane.restoreFind(restoreFind);
        restoreFind = null;
        findCode.setVisibility(View.VISIBLE);
        updateFindButton();
        saveBytes=bytes; saveDigest=digest;
        if (exportKey!=null && (!exportKey.equals(selectedKey) || exportDigest==null || !exportDigest.equals(saveDigest)))
            rejectExport("The selected Smali changed. Open it again before saving.");
        updateSaveButton();
        finishExportIfReady();
    }

    private Button toolbarAction(String label,String contentDescription,Runnable click) {
        Button button=new Button(this);
        button.setAllCaps(false); button.setTextColor(theme.accent); button.setBackground(null);
        button.setText(label); button.setContentDescription(contentDescription);
        button.setMaxWidth(dp(120)); button.setSingleLine(); button.setEllipsize(TextUtils.TruncateAt.END);
        button.setMinWidth(dp(48)); button.setMinHeight(dp(48)); button.setVisibility(View.GONE);
        button.setOnClickListener(v -> click.run());
        return button;
    }

    private void updateDexPicker() {
        dexPicker.setText(selectedDex==null?"DEX files":selectedDex);
        dexPicker.setContentDescription(selectedDex==null?"Filter by DEX file: All DEX files":"Filter by DEX file: "+selectedDex);
    }

    private void showDexPicker() {
        if(showingCode || dexEntries.size()<2 || dexDialog!=null) return;
        String[] labels=new String[dexEntries.size()+1];
        labels[0]="All DEX files";
        for(int i=0;i<dexEntries.size();i++)labels[i+1]=dexEntries.get(i);
        dexDialog=new AlertDialog.Builder(this).setTitle("DEX entries")
                .setSingleChoiceItems(labels,selectedDex==null?0:dexEntries.indexOf(selectedDex)+1,(dialog,which)->{
                    selectedDex=which==0?null:labels[which];
                    dialog.dismiss();
                    updateDexPicker(); main.removeCallbacks(refreshResults); filterClasses();
                }).setNegativeButton("Cancel",null).create();
        dexDialog.setOnDismissListener(dialog -> dexDialog=null);
        dexDialog.show();
    }

    private void chooseSmaliDestination() {
        if(saveBytes==null || saveDigest==null || selectedKey==null || exportKey!=null) return;
        Intent create=SmaliDocumentExport.picker(this,selectedKey.substring(selectedKey.indexOf('/')+1));
        if (create==null) { noteExport("Android's local Files picker is unavailable."); return; }
        exportKey=selectedKey; exportDigest=saveDigest; updateSaveButton();
        try { startActivityForResult(create,CREATE_SMALI); }
        catch (android.content.ActivityNotFoundException | SecurityException unavailable) { rejectExport("Android's local Files picker is unavailable."); }
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=CREATE_SMALI || exportKey==null) return;
        if(result!=RESULT_OK) { clearExport(); return; }
        if(data==null || data.getData()==null || (data.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0) {
            rejectExport("Android did not return a writable new document."); return;
        }
        exportDestination=data.getData();
        finishExportIfReady();
    }

    private void finishExportIfReady() {
        if(exportDestination==null || saveBytes==null || !java.util.Objects.equals(exportKey,selectedKey)) return;
        if(exportDigest==null || !exportDigest.equals(saveDigest)) {
            rejectExport("The selected Smali changed. Open it again before saving."); return;
        }
        Uri uri=exportDestination;
        byte[] contents=saveBytes;
        clearExport();
        Context context=getApplicationContext();
        try { EXPORT.execute(() -> writeSmali(context,uri,contents)); }
        catch(RejectedExecutionException busy) { noteExport("Another Smali save is finishing. Try again."); }
    }

    private static void writeSmali(Context context,Uri destination,byte[] contents) {
        String response;
        try {
            SmaliDocumentExport.write(context,destination,contents);
            response="Smali saved";
        } catch (Exception failure) {
            response=failure instanceof IOException && failure.getMessage()!=null ? failure.getMessage()
                    : "Could not save Smali. The picker may have created an empty or partial document.";
        }
        String message=response;
        EXPORT_MAIN.post(() -> Toast.makeText(context,message,Toast.LENGTH_LONG).show());
    }

    private void clearExport() { exportDestination=null; exportKey=null; exportDigest=null; updateSaveButton(); }
    private void rejectExport(String message) { clearExport(); noteExport(message); }
    private void noteExport(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    private void updateSaveButton() {
        saveCode.setVisibility(saveBytes!=null && showingCode?View.VISIBLE:View.GONE);
        saveCode.setEnabled(exportKey==null);
    }

    private void updateFindButton() {
        boolean opened = codePane != null && codePane.isFinding();
        findCode.setText(opened ? "Close find" : "Find");
        findCode.setContentDescription(opened ? "Close find" : "Find in Smali");
    }

    private CharSequence colorize(String code) {
        SpannableString colors = new SpannableString(code);
        int directives = theme.dark ? 0xFF84B6FF : 0xFF1354AA, comments = theme.dark ? 0xFF98BFA1 : 0xFF416D4D;
        int count = 0;
        for (int line = 0; line < code.length() && count < 3500;) {
            int end = code.indexOf('\n', line);
            if (end < 0) end = code.length();
            int token = line;
            while (token < end && code.charAt(token) <= ' ') token++;
            if (token < end && (code.charAt(token) == '.' || code.charAt(token) == '#')) {
                int until = token + 1;
                if (code.charAt(token) == '.') while (until < end && code.charAt(until) > ' ') until++;
                else until = end;
                colors.setSpan(new ForegroundColorSpan(code.charAt(token) == '.' ? directives : comments),
                        token, until, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                count++;
            }
            line = end + 1;
        }
        return colors;
    }

    private void showSpinner() {
        content.removeAllViews();
        ProgressBar progress = new ProgressBar(this);
        content.addView(progress, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); return c; }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); return view;
    }

    private interface Task { Runnable run() throws Exception; }
    private void submit(Task task) {
        cancelWork();
        long turn = generation;
        try {
            work = WORKER.submit(() -> {
                Runnable callback;
                try { callback = task.run(); }
                catch (Exception | StackOverflowError | OutOfMemoryError failure) {
                    String message = failure instanceof IOException && failure.getMessage() != null
                            ? failure.getMessage() : failure instanceof OutOfMemoryError
                            ? "This DEX needs more memory to inspect." : "Could not inspect this DEX data.";
                    callback = () -> showError(message);
                }
                Runnable result = callback;
                if (!Thread.currentThread().isInterrupted())
                    main.post(() -> { if (!destroyed && !isFinishing() && generation == turn) result.run(); });
            });
        } catch (RejectedExecutionException busy) { showError("The previous inspection is still finishing. Reopen this viewer to retry."); }
    }

    private void showError(String message) {
        if(exportKey!=null)rejectExport("Smali could not be reopened. Nothing was written.");
        status.setText(showingCode ? "Smali is unavailable" : "DEX inspection unavailable");
        content.removeAllViews();
        TextView explain = label(message, 15, theme.muted);
        explain.setPadding(dp(16),dp(16),dp(16),dp(16));
        content.addView(explain, new FrameLayout.LayoutParams(-1,-2));
    }

    private void cancelWork() {
        generation++;
        if (work != null) { work.cancel(true); work = null; }
        WORKER.purge();
    }
    // Android 13+ uses the registered platform callback; this handles Android 8-12.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { handleBack(); }
    private void handleBack() {
        if (codePane != null && codePane.isFinding()) {
            codePane.closeFind(); updateFindButton();
        } else if (showingCode) { cancelWork(); showClassList(); }
        else finish();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("query", search.getText().toString()); state.putString("class", selectedKey);
        state.putString("dexScope",selectedDex);
        state.putString("exportKey",exportKey); state.putString("exportDigest",exportDigest);
        state.putParcelable("exportDestination",exportDestination);
        if (codePane != null) state.putBundle("smaliFind",codePane.saveFind());
        else if (restoreFind != null) state.putBundle("smaliFind",restoreFind);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        destroyed = true; signal.cancel(); cancelWork(); main.removeCallbacksAndMessages(null);
        if(dexDialog!=null) dexDialog.dismiss();
        saveBytes=null;
        classes = Collections.emptyList();
        super.onDestroy();
    }

    private final class ClassAdapter extends BaseAdapter {
        List<DexInspector.Item> items = Collections.emptyList();
        @Override public int getCount() { return items.size(); }
        @Override public DexInspector.Item getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View existing, ViewGroup parent) {
            LinearLayout row;
            if (existing instanceof LinearLayout) row = (LinearLayout) existing;
            else {
                row = column(); row.setPadding(dp(16),dp(9),dp(16),dp(9)); row.setMinimumHeight(dp(54));
                TextView name = label("", 15, theme.ink); name.setSingleLine(); name.setEllipsize(TextUtils.TruncateAt.MIDDLE); row.addView(name);
                row.addView(label("", 12, theme.muted));
            }
            DexInspector.Item item = getItem(position);
            ((TextView) row.getChildAt(0)).setText(item.name);
            ((TextView) row.getChildAt(1)).setText(item.dex);
            return row;
        }
    }
}
