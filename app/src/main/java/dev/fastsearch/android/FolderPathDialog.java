package dev.fastsearch.android;

import android.app.AlertDialog;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import dev.fastsearch.core.FileEntry;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Directories in the same dialog as the editable path. */
final class FolderPathDialog {
    private final FileIndex index;
    private final UnaryOperator<String> normalize;
    private final Consumer<String> opened;
    private final AlertDialog dialog;
    private final EditText path;
    private final TextView status;
    private final Button up;
    private final ArrayAdapter<String> adapter;
    private final ArrayList<FileEntry> directories = new ArrayList<>();
    private List<FileEntry> listedDirectories = Collections.emptyList();
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1,
            task -> new Thread(task, "folder-path"));
    private final Handler ui = new Handler(Looper.getMainLooper());
    private CancellationSignal cancellation;
    private ScheduledFuture<?> pending;
    private String listedFolder, parent;
    private int revision;

    FolderPathDialog(Context context, FileIndex index, String initial, UnaryOperator<String> normalize,
                     Consumer<String> opened, Runnable dismissed) {
        this.index = index;
        this.normalize = normalize;
        this.opened = opened;
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        float density = context.getResources().getDisplayMetrics().density;

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(24 * density);
        content.setPadding(inset, 0, inset, 0);
        path = new EditText(context);
        path.setHint("Absolute directory path");
        path.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        path.setSingleLine(true);
        path.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        content.addView(path, new LinearLayout.LayoutParams(-1, -2));

        up = new Button(context);
        up.setText("Parent folder");
        up.setOnClickListener(v -> {
            if (parent != null) browse(parent);
        });
        content.addView(up, new LinearLayout.LayoutParams(-2, -2));
        status = new TextView(context);
        status.setPadding(0, Math.round(4 * density), 0, Math.round(4 * density));
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status, new LinearLayout.LayoutParams(-1, -2));

        ListView list = new ListView(context);
        int maxListHeight = Math.min(Math.round(240 * density), context.getResources().getDisplayMetrics().heightPixels / 4);
        content.addView(list, new LinearLayout.LayoutParams(-1, maxListHeight));
        adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1);
        adapter.setNotifyOnChange(false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((items, view, position, id) -> browse(directories.get(position).path()));

        dialog = new AlertDialog.Builder(context).setTitle("Go to folder").setView(content)
                .setPositiveButton("Go", null).setNegativeButton("Cancel", null).create();
        dialog.setOnDismissListener(ignored -> {
            cancelRequest();
            worker.shutdownNow();
            ui.removeCallbacksAndMessages(null);
            dismissed.run();
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> go()));
        path.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_GO) return false;
            go();
            return true;
        });
        path.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { suggest(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        browse(initial);
        path.requestFocus();
    }

    void show() { dialog.show(); }
    void dismiss() { dialog.dismiss(); }

    private void browse(String folder) {
        path.setText(folder.endsWith("/") ? folder : folder + "/");
        path.setSelection(path.length());
        path.getContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(path.getWindowToken(), 0);
    }

    private void suggest(String input) {
        path.setError(null);
        if (!valid(input)) {
            cancelRequest();
            listedFolder = parent = null;
            up.setEnabled(false);
            showDirectories(Collections.emptyList(), "Enter an absolute directory path");
            return;
        }
        int slash = input.lastIndexOf('/');
        String folder = normalize.apply(input.substring(0, slash + 1));
        String prefix = input.substring(slash + 1).toLowerCase(Locale.ROOT);
        parent = new File(input.endsWith("/") ? folder : normalize.apply(input)).getParent();
        up.setEnabled(parent != null);
        if (folder.equals(listedFolder)) {
            cancelRequest();
            showSuggestions(prefix);
        } else {
            showDirectories(Collections.emptyList(), "Reading folders...");
            request(folder, prefix, false);
        }
    }

    private void go() {
        String input = path.getText().toString();
        if (!valid(input)) {
            path.setError("Enter an absolute directory path");
            return;
        }
        String folder = normalize.apply(input);
        if (folder.equals(listedFolder)) finish(folder);
        else request(folder, "", true);
    }

    private void request(String folder, String prefix, boolean open) {
        cancelRequest();
        int ticket = revision;
        CancellationSignal reading = new CancellationSignal();
        cancellation = reading;
        pending = worker.schedule(() -> {
            try {
                List<FileEntry> entries = index.listFolder(folder, reading);
                ArrayList<FileEntry> found = new ArrayList<>();
                if (!open) {
                    for (FileEntry entry : entries) {
                        reading.throwIfCanceled();
                        if (entry.directory) found.add(entry);
                    }
                    found.sort(Comparator.comparing((FileEntry entry) -> entry.name, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(entry -> entry.path()));
                }
                ui.post(() -> {
                    if (ticket != revision || !dialog.isShowing()) return;
                    if (open) finish(folder);
                    else {
                        listedFolder = folder;
                        listedDirectories = found;
                        showSuggestions(prefix);
                    }
                });
            } catch (OperationCanceledException ignored) {
            } catch (IOException | SecurityException error) {
                ui.post(() -> {
                    if (ticket != revision || !dialog.isShowing()) return;
                    listedFolder = null;
                    if (open) path.setError("Could not open this folder");
                    else showDirectories(Collections.emptyList(), "Could not read this folder");
                });
            }
        }, open ? 0 : 150, TimeUnit.MILLISECONDS);
    }

    private void finish(String folder) {
        dialog.dismiss();
        opened.accept(folder);
    }

    private void showSuggestions(String prefix) {
        if (prefix.isEmpty()) {
            showDirectories(listedDirectories, listedDirectories.isEmpty() ? "No subfolders" : "");
            return;
        }
        ArrayList<FileEntry> matching = new ArrayList<>();
        for (FileEntry entry : listedDirectories)
            if (entry.name.toLowerCase(Locale.ROOT).startsWith(prefix)) matching.add(entry);
        showDirectories(matching, matching.isEmpty() ? "No matching folders" : "");
    }

    private void showDirectories(List<FileEntry> found, String message) {
        directories.clear();
        directories.addAll(found);
        adapter.clear();
        for (FileEntry entry : directories) adapter.add(entry.name);
        adapter.notifyDataSetChanged();
        status.setText(message);
        status.setVisibility(message.isEmpty() ? TextView.GONE : TextView.VISIBLE);
    }

    private void cancelRequest() {
        revision++;
        if (cancellation != null) cancellation.cancel();
        if (pending != null) pending.cancel(true);
    }

    private static boolean valid(String input) {
        return input.startsWith("/") && input.indexOf(0) < 0;
    }
}
