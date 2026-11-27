package dev.fastsearch.android;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import dev.fastsearch.core.FileEntry;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Shared folder chooser for Files, a pane, and Paste; lists only directories from FileIndex. */
final class FolderPathDialog {
    private static final Comparator<FileEntry> ORDER = Comparator
            .comparing((FileEntry entry) -> entry.name, String.CASE_INSENSITIVE_ORDER).thenComparing(FileEntry::path);
    private final Context context;
    private final FileIndex index;
    private final UiTheme theme;
    private final UnaryOperator<String> normalize;
    private final Consumer<String> opened;
    private final Predicate<String> allowed;
    private final boolean showHidden;
    private final String current;
    private final AlertDialog dialog;
    private final EditText path;
    private final TextView status;
    private final LinearLayout content;
    private final Button mode, up;
    private Button overview;
    private final ListView list;
    private final FolderTree tree;
    private final boolean allowRootListing;
    private final List<FolderTree.Node> initialBranch;
    private final Rows adapter = new Rows();
    private final Map<FolderTree.Node, CancellationSignal> treeReads = new IdentityHashMap<>();
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1,
            task -> new Thread(task, "folder-path"));
    private final Handler ui = new Handler(Looper.getMainLooper());
    private List<FileEntry> listedDirectories = Collections.emptyList(), suggestions = Collections.emptyList();
    private List<FolderTree.Row> treeRows = Collections.emptyList();
    private CancellationSignal cancellation;
    private ScheduledFuture<?> pending;
    private String listedFolder, parent, openError = "";
    private int revision;
    private boolean typed, closed, committing;

    FolderPathDialog(Context context, FileIndex index, String initial, UnaryOperator<String> normalize,
                     Consumer<String> opened, Runnable dismissed) {
        this(context, index, "Go to folder", initial, normalize, opened, dismissed,
                defaultRoots(index, normalize), value -> true, true, true);
    }

    FolderPathDialog(Context context, FileIndex index, String title, String initial, UnaryOperator<String> normalize,
                     Consumer<String> opened, Runnable dismissed, Map<String, String> locations,
                     Predicate<String> allowed, boolean showHidden, boolean allowTyped) {
        this(context,index,title,initial,normalize,opened,dismissed,locations,allowed,showHidden,allowTyped,true);
    }

    FolderPathDialog(Context context, FileIndex index, String title, String initial, UnaryOperator<String> normalize,
                     Consumer<String> opened, Runnable dismissed, Map<String, String> locations,
                     Predicate<String> allowed, boolean showHidden, boolean allowTyped, boolean allowRootListing) {
        this.allowRootListing=allowRootListing;
        this.context = context;
        this.index = index;
        this.theme = UiTheme.resolve(context);
        this.normalize = normalize;
        this.opened = opened;
        this.allowed = allowed;
        this.showHidden = showHidden;
        Map<String, String> roots = new LinkedHashMap<>();
        for (Map.Entry<String, String> location : locations.entrySet()) {
            String folder = normalize(location.getKey());
            if (folder != null && allowed.test(folder)) roots.putIfAbsent(folder, location.getValue());
        }
        String start = normalize(initial);
        if (start == null || !allowed.test(start)) {
            start = roots.isEmpty() ? normalize(Environment.getExternalStorageDirectory().getAbsolutePath())
                    : roots.keySet().iterator().next();
        }
        current = start;
        tree = new FolderTree(roots);
        initialBranch = current == null || !allowed.test(current) ? Collections.emptyList() : tree.discloseCurrent(current);
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), 0, dp(14), 0);
        content.setFocusableInTouchMode(true);
        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView location = text(current == null ? "Choose a folder" : current, 13);
        location.setSingleLine(); location.setEllipsize(TextUtils.TruncateAt.START);
        location.setPadding(dp(5), 0, dp(5), 0);
        location.setContentDescription("Current folder: " + (current == null ? "unset" : current));
        toolbar.addView(location, new LinearLayout.LayoutParams(0, dp(48), 1));
        if (allowTyped) {
            mode = new Button(context); mode.setText("Type path"); mode.setAllCaps(false);
            mode.setOnClickListener(v -> displayMode(!typed));
            toolbar.addView(mode, new LinearLayout.LayoutParams(-2, dp(48)));
        } else mode = null;
        content.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));

        path = new EditText(context);
        path.setHint("Absolute directory path");
        path.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        path.setSingleLine(true);
        path.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        path.setVisibility(View.GONE);
        content.addView(path, new LinearLayout.LayoutParams(-1, dp(48)));
        up = new Button(context);
        up.setText("Parent folder"); up.setAllCaps(false); up.setVisibility(View.GONE);
        up.setOnClickListener(v -> { if (parent != null) browseTyped(parent); });
        content.addView(up, new LinearLayout.LayoutParams(-2, dp(48)));
        status = text("", 12); status.setTextColor(theme.muted);
        status.setPadding(dp(5), dp(4), dp(5), dp(4)); status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status, new LinearLayout.LayoutParams(-1, -2));

        list = new ListView(context);
        list.setItemsCanFocus(true); list.setDividerHeight(1);
        int available = context.getResources().getDisplayMetrics().heightPixels;
        content.addView(list, new LinearLayout.LayoutParams(-1, Math.max(dp(96), Math.min(dp(360), available * 4 / 9))));
        list.setAdapter(adapter);

        dialog = new AlertDialog.Builder(context).setTitle(title).setView(content)
                .setPositiveButton("Use current folder", null).setNegativeButton("Cancel", null).create();
        dialog.setOnDismissListener(ignored -> {
            closed = true;
            cancelRequest();
            cancelTreeReads();
            worker.shutdownNow();
            ui.removeCallbacksAndMessages(null);
            dismissed.run();
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (typed) go();
                else if (current != null) request(current, "", true);
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(current != null && allowed.test(current));
        });
        path.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_GO) return false;
            go();
            return true;
        });
        path.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { if (typed && !committing) suggest(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        content.requestFocus();
    }

    static Map<String, String> defaultRoots(FileIndex index, UnaryOperator<String> normalize) {
        LinkedHashMap<String, String> locations = new LinkedHashMap<>();
        String shared = normalize.apply(Environment.getExternalStorageDirectory().getAbsolutePath());
        locations.put(shared, "Internal storage");
        for (String directory : index.roots()) {
            String folder = normalize.apply(directory);
            locations.putIfAbsent(folder, "/".equals(folder) ? "Device root" : new File(folder).getName());
        }
        return locations;
    }

    void offerStorageOverview(Runnable action) {
        overview = new Button(context);
        overview.setText("Storage overview");
        overview.setAllCaps(false);
        overview.setOnClickListener(view -> { dismiss();action.run(); });
        content.addView(overview,1,new LinearLayout.LayoutParams(-1,dp(48)));
    }

    void show() {
        dialog.show();
        renderTree();
        // The deepest folder and its siblings become usable first, while ancestors fill in behind.
        for (int i = initialBranch.size() - 1; i >= 0; i--) if (initialBranch.get(i).expanded) readBranch(initialBranch.get(i));
    }

    void dismiss() { dialog.dismiss(); }

    private int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private TextView text(String label, int size) {
        TextView view = new TextView(context);
        view.setText(label); view.setTextSize(size); view.setTextColor(theme.ink);
        view.setTypeface(context.getResources().getFont(R.font.inter));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private RippleDrawable ripple(int fill) {
        return new RippleDrawable(ColorStateList.valueOf(theme.line),
                AppSheet.shape(fill, dp(7)), AppSheet.shape(Color.WHITE, dp(7)));
    }

    private String normalize(String input) {
        if (input == null || !input.startsWith("/") || input.indexOf(0) >= 0 || input.length() > 4096) return null;
        try {
            String result = normalize.apply(input);
            return result != null && result.startsWith("/") && result.indexOf(0) < 0 ? result : null;
        } catch (RuntimeException badPath) { return null; }
    }

    private void displayMode(boolean useTyped) {
        if (committing) return;
        typed = useTyped; openError = "";
        cancelRequest();
        mode.setText(typed ? "Folder tree" : "Type path");
        path.setVisibility(typed ? View.VISIBLE : View.GONE);
        up.setVisibility(typed ? View.VISIBLE : View.GONE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(typed ? "Go" : "Use current folder");
        if (typed) {
            String value = path.getText().toString();
            if (value.isEmpty() && current != null) browseTyped(current);
            else suggest(value);
            path.requestFocus();
            path.getContext().getSystemService(InputMethodManager.class).showSoftInput(path, InputMethodManager.SHOW_IMPLICIT);
        } else {
            hideKeyboard();
            renderTree();
            restartTreeReads();
        }
        if (overview != null) overview.setVisibility(useTyped ? View.GONE : View.VISIBLE);
    }

    private void hideKeyboard() {
        path.getContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(path.getWindowToken(), 0);
        path.clearFocus();
    }

    private void browseTyped(String folder) {
        path.setText(folder.endsWith("/") ? folder : folder + "/");
        path.setSelection(path.length());
        hideKeyboard();
    }

    private void toggle(FolderTree.Node node) {
        if (committing || !node.canExpand()) return;
        node.expanded = !node.expanded;
        openError = "";
        if (node.expanded) readOpen(node);
        else cancelBranch(node);
        renderTree();
    }

    private void readOpen(FolderTree.Node node) {
        if (!node.expanded) return;
        readBranch(node);
        for (FolderTree.Node child : node.children.values()) if (child.expanded) readOpen(child);
    }

    private void restartTreeReads() {
        for (FolderTree.Node root : tree.roots) readOpen(root);
    }

    private void cancelTreeReads() {
        for (Map.Entry<FolderTree.Node, CancellationSignal> item : treeReads.entrySet()) {
            item.getValue().cancel();
            item.getKey().loading = false;
        }
        treeReads.clear();
    }

    private void cancelBranch(FolderTree.Node node) {
        CancellationSignal read = treeReads.remove(node);
        if (read != null) { read.cancel(); node.loading = false; }
        for (FolderTree.Node child : node.children.values()) if (child.expanded) cancelBranch(child);
    }

    private void readBranch(FolderTree.Node node) {
        if (!node.canExpand() || node.loaded || node.loading || !allowed.test(node.path)) return;
        CancellationSignal reading = new CancellationSignal();
        treeReads.put(node, reading); node.loading = true; node.failed = false;
        worker.schedule(() -> {
            try {
                if (reading.isCanceled()) return;
                List<FileEntry> entries = directories(node.path, reading);
                ArrayList<String> folders = new ArrayList<>(entries.size());
                for (FileEntry entry : entries) folders.add(entry.path());
                ui.post(() -> {
                    if (closed || !treeReads.remove(node, reading)) return;
                    node.read(folders);
                    if (!typed) renderTree();
                });
            } catch (OperationCanceledException ignored) {
            } catch (IOException | SecurityException error) {
                ui.post(() -> {
                    if (closed || !treeReads.remove(node, reading)) return;
                    node.loading = false; node.failed = true;
                    if (!typed) renderTree();
                });
            }
        }, 0, TimeUnit.MILLISECONDS);
    }

    private List<FileEntry> directories(String folder, CancellationSignal reading) throws IOException {
        List<FileEntry> children = index.listFolder(folder, reading,allowRootListing);
        ArrayList<FileEntry> found = new ArrayList<>();
        for (FileEntry entry : children) {
            reading.throwIfCanceled();
            if (entry.directory && allowed.test(entry.path()) && (showHidden || !entry.name.startsWith("."))) found.add(entry);
        }
        found.sort(ORDER);
        return found;
    }

    private void renderTree() {
        if (typed) return;
        treeRows = tree.rows();
        if (!committing) setStatus(!openError.isEmpty() ? openError : treeRows.size() >= FolderTree.MAX_ROWS
                ? "Collapse a branch to show more folders."
                : treeRows.isEmpty() ? "No readable locations" : "");
        adapter.notifyDataSetChanged();
    }

    private void suggest(String input) {
        path.setError(null);
        String entered = normalize(input);
        if (entered == null || !allowed.test(entered)) {
            cancelRequest();
            listedFolder = parent = null; up.setEnabled(false);
            showDirectories(Collections.emptyList(), "Enter an absolute directory path");
            return;
        }
        int slash = input.lastIndexOf('/');
        String folder = normalize(input.substring(0, slash + 1));
        if (folder == null || !allowed.test(folder)) {
            cancelRequest(); listedFolder = parent = null; up.setEnabled(false);
            showDirectories(Collections.emptyList(), "Choose an accessible folder");
            return;
        }
        String prefix = input.substring(slash + 1).toLowerCase(Locale.ROOT);
        parent = new File(input.endsWith("/") ? folder : entered).getParent();
        up.setEnabled(parent != null && allowed.test(parent));
        if (folder.equals(listedFolder)) {
            cancelRequest();
            showSuggestions(prefix);
        } else {
            showDirectories(Collections.emptyList(), "Reading folders…");
            request(folder, prefix, false);
        }
    }

    private void go() {
        String folder = normalize(path.getText().toString());
        if (folder == null || !allowed.test(folder)) {
            path.setError("Enter an accessible absolute directory path");
            return;
        }
        request(folder, "", true);
    }

    private void request(String folder, String prefix, boolean open) {
        if (closed || committing || !allowed.test(folder)) return;
        cancelRequest();
        if (open) {
            // Validation uses the same reader as before; expansion alone never confirms a destination.
            committing = true; openError = "";
            cancelTreeReads();
            path.setEnabled(false);
            setStatus("Opening folder…");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        }
        int ticket = revision;
        CancellationSignal reading = new CancellationSignal();
        cancellation = reading;
        pending = worker.schedule(() -> {
            try {
                List<FileEntry> found = open ? validate(folder, reading) : directories(folder, reading);
                ui.post(() -> {
                    if (ticket != revision || closed) return;
                    if (open) {
                        dialog.dismiss();
                        opened.accept(folder);
                    } else {
                        listedFolder = folder; listedDirectories = found;
                        showSuggestions(prefix);
                    }
                });
            } catch (OperationCanceledException ignored) {
            } catch (IOException | SecurityException error) {
                ui.post(() -> {
                    if (closed || ticket != revision) return;
                    listedFolder = null;
                    if (open) {
                        committing = false; openError = "Could not open this folder. Choose another location.";
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        path.setEnabled(true);
                        if (typed) path.setError("Could not open this folder");
                        setStatus(openError);
                        if (!typed) restartTreeReads();
                    } else showDirectories(Collections.emptyList(), "Could not read this folder");
                });
            }
        }, open ? 0 : 140, TimeUnit.MILLISECONDS);
    }

    private List<FileEntry> validate(String folder, CancellationSignal reading) throws IOException {
        index.listFolder(folder, reading,allowRootListing);
        return Collections.emptyList();
    }

    private void showSuggestions(String prefix) {
        if (prefix.isEmpty()) {
            showDirectories(listedDirectories, listedDirectories.isEmpty() ? "No subfolders" : "");
            return;
        }
        ArrayList<FileEntry> matching = new ArrayList<>();
        for (FileEntry entry : listedDirectories) if (entry.name.toLowerCase(Locale.ROOT).startsWith(prefix)) matching.add(entry);
        showDirectories(matching, matching.isEmpty() ? "No matching folders" : "");
    }

    private void showDirectories(List<FileEntry> found, String message) {
        suggestions = found;
        adapter.notifyDataSetChanged();
        setStatus(message);
    }

    private void setStatus(String message) {
        status.setText(message);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void cancelRequest() {
        revision++;
        if (cancellation != null) cancellation.cancel();
        if (pending != null) pending.cancel(true);
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return typed ? suggestions.size() : treeRows.size(); }
        @Override public Object getItem(int position) { return typed ? suggestions.get(position) : treeRows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row;
            ImageButton caret;
            TextView label;
            if (convert == null) {
                row = new LinearLayout(context);
                row.setGravity(Gravity.CENTER_VERTICAL);
                caret = new ImageButton(context);
                caret.setImageResource(R.drawable.ic_chevron_right);
                caret.setImageTintList(ColorStateList.valueOf(theme.muted));
                caret.setBackground(ripple(Color.TRANSPARENT));
                caret.setPadding(dp(14), dp(14), dp(14), dp(14));
                row.addView(caret, new LinearLayout.LayoutParams(dp(48), dp(48)));
                label = text("", 14);
                label.setMinHeight(dp(48)); label.setSingleLine(true); label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                label.setPadding(dp(3), 0, dp(5), 0);
                row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            } else {
                row = (LinearLayout) convert;
                caret = (ImageButton) row.getChildAt(0);
                label = (TextView) row.getChildAt(1);
            }
            FolderTree.Row item = typed ? null : treeRows.get(position);
            FolderTree.Node node = item == null ? null : item.node;
            row.setPaddingRelative(dp(item == null ? 0 : Math.min(5, item.depth) * 12), 0, 0, 0);
            caret.setVisibility(node != null && node.canExpand() ? View.VISIBLE : View.INVISIBLE);
            label.setFocusable(true);
            if(typed||node!=null){
                android.graphics.drawable.Drawable folder=context.getDrawable(UiTheme.fileIcon(context,2)).mutate();
                folder.setBounds(0,0,dp(16),dp(16));
                label.setCompoundDrawablesRelative(folder,null,null,null);label.setCompoundDrawablePadding(dp(8));
            }else label.setCompoundDrawablesRelative(null,null,null,null);
            if (typed) {
                FileEntry entry = suggestions.get(position);
                style(label, false, entry.name);
                label.setContentDescription("Browse " + entry.name);
                label.setOnClickListener(v -> { if (!committing) browseTyped(entry.path()); });
            } else if (node == null) {
                style(label, false, item.status);
                label.setTextColor(theme.muted); label.setContentDescription(null);
                label.setOnClickListener(null); label.setClickable(false); label.setFocusable(false);
            } else {
                boolean currentFolder = node.path.equals(current);
                style(label, currentFolder, node.name);
                label.setContentDescription((currentFolder ? "Current folder: " : "Choose folder: ") + node.name);
                label.setOnClickListener(v -> request(node.path, "", true));
                caret.setRotation(node.expanded ? 90f : 0f);
                caret.setOnClickListener(v -> toggle(node));
                caret.setContentDescription((node.expanded ? "Collapse " : "Expand ") + node.name);
            }
            return row;
        }

        private void style(TextView label, boolean selected, String value) {
            label.setText(value); label.setTextColor(theme.ink);
            label.setTypeface(context.getResources().getFont(selected ? R.font.inter_medium : R.font.inter), Typeface.NORMAL);
            label.setBackground(ripple(selected ? theme.selected : Color.TRANSPARENT));
            label.setSelected(selected);
        }
    }
}
