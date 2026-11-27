package dev.fastsearch.android;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Telephony.Sms;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Read-only, permission-gated device detail. No persistent storage or export of provider rows. */
final class DeviceDetails {
    private static final int PAGE_SIZE = 24, BODY_LIMIT = 8192;
    private static final String[] MESSAGE_COLUMNS = {Sms._ID, Sms.THREAD_ID, Sms.DATE, Sms.TYPE, Sms.BODY};
    static final String OLDER_BY_KEY = "(" + Sms.DATE + "<? OR (" + Sms.DATE + "=? AND " + Sms._ID + "<?))";
    static final String NEWER_BY_KEY = "(" + Sms.DATE + ">? OR (" + Sms.DATE + "=? AND " + Sms._ID + ">?))";

    static AppSheet open(Activity activity, DeviceSearch.Result result, Consumer<AppSheet> closed) {
        if ((result.domain == DeviceSearch.CONTACTS && DeviceSearch.hasContacts(activity))
                || (result.domain == DeviceSearch.SMS && BuildConfig.SMS_ENABLED && DeviceSearch.hasSms(activity))) {
            return new DeviceDetails(activity, result, closed).show();
        }
        Toast.makeText(activity, "Access is not enabled", Toast.LENGTH_SHORT).show();
        return null;
    }

    private final Activity activity;
    private final DeviceSearch.Result result;
    private final AppSheet sheet;
    private final ScrollView scroll;
    private final LinearLayout content;
    private final Button older, newer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> new Thread(r, "device-details"));
    private final CancellationSignal cancel = new CancellationSignal();
    private SmsPage page;
    private boolean busy, closed;

    private DeviceDetails(Activity activity, DeviceSearch.Result result, Consumer<AppSheet> onClose) {
        this.activity = activity;
        this.result = result;
        boolean sms = result.domain == DeviceSearch.SMS;
        sheet = new AppSheet(activity, sms ? "SMS conversation" : "Contact details", true);
        if (sheet.getWindow() != null) sheet.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        TextView name = sheet.text(result.title, 22, sheet.theme.ink);
        name.setPadding(sheet.dp(12), sheet.dp(8), sheet.dp(12), sheet.dp(6));
        name.setMaxLines(3);
        sheet.content.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView notice = sheet.text(sms ? "On-device · read only · SMS text" : "From your device contacts", 13, sheet.theme.muted);
        notice.setPadding(sheet.dp(12), 0, sheet.dp(12), sheet.dp(8));
        sheet.content.addView(notice, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(activity);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(sheet.dp(12), 0, sheet.dp(12), sheet.dp(8));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        sheet.content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        if (sms) {
            older = navigation("Older", -1);
            newer = navigation("Newer", 1);
        } else {
            older = newer = null;
            sheet.action("Open in Contacts", true, false, this::openContacts);
        }
        sheet.setOnDismissListener(dialog -> {
            closed = true;
            cancel.cancel();
            work.shutdownNow();
            main.removeCallbacksAndMessages(null);
            content.removeAllViews();
            page = null;
            onClose.accept(sheet);
        });
    }

    private AppSheet show() {
        sheet.show();
        if (result.domain == DeviceSearch.SMS) loadThread(0); else loadContact();
        return sheet;
    }

    private Button navigation(String title, int direction) {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setText(title);
        button.setTextColor(sheet.theme.ink);
        button.setContentDescription(title + " messages");
        button.setOnClickListener(v -> loadThread(direction));
        sheet.actions.addView(button, new LinearLayout.LayoutParams(0, sheet.dp(48), 1));
        button.setEnabled(false);
        return button;
    }

    private void loadContact() {
        replaceWithMessage("Loading contact details…");
        try {
            work.execute(() -> {
                Map<String, String> fields = new LinkedHashMap<>();
                try {
                    if (!DeviceSearch.hasContacts(activity)) throw new SecurityException();
                    ContentResolver resolver = activity.getContentResolver();
                    long contactId = resolvedContactId(resolver, result.uri, cancel);
                    // Carry only user-facing fields, not provider IDs, account metadata or notes.
                    String[] names = {ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE};
                    String[] labels = {"Phone", "Email", "Organization", "Address"};
                    String clause = ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + " IN (?,?,?,?)";
                    String[] columns = {ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1};
                    try (Cursor rows = query(resolver, ContactsContract.Data.CONTENT_URI, columns, clause,
                            new String[]{Long.toString(contactId), names[0], names[1], names[2], names[3]},
                            ContactsContract.Data.IS_PRIMARY + " DESC, " + ContactsContract.Data._ID + " ASC", 80, cancel)) {
                        for (int n = 0; rows != null && n < 80 && fields.size() < 40 && rows.moveToNext(); n++) {
                            cancel.throwIfCanceled();
                            String type = rows.getString(0), value = bound(rows.getString(1), 512).trim();
                            if (value.isEmpty()) continue;
                            for (int i = 0; i < names.length; i++) {
                                if (names[i].equals(type)) fields.putIfAbsent(labels[i] + "\0" + value, labels[i]);
                            }
                        }
                    }
                } catch (Exception unavailable) {
                    // Do not echo provider exception messages or contact data to a log.
                    if (!cancel.isCanceled()) main.post(() -> replaceIfOpen("Contact details couldn't be loaded."));
                    return;
                }
                if (!cancel.isCanceled()) main.post(() -> {
                    if (!isOpen()) return;
                    if (!DeviceSearch.hasContacts(activity)) { sheet.dismiss(); return; }
                    content.removeAllViews();
                    if (fields.isEmpty()) { append("No phone, email, organization or address on this contact.", null, false); return; }
                    for (Map.Entry<String, String> field : fields.entrySet())
                        append(field.getKey().substring(field.getValue().length() + 1), field.getValue(), false);
                });
            });
        } catch (RejectedExecutionException ignored) { /* Already dismissed. */ }
    }

    private void openContacts() {
        if (!DeviceSearch.hasContacts(activity) || result.uri == null) return;
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, result.uri)); }
        catch (RuntimeException unavailable) {
            Toast.makeText(activity, "No contacts app available", Toast.LENGTH_SHORT).show();
        }
    }

    private static long resolvedContactId(ContentResolver resolver, Uri lookup, CancellationSignal cancel) {
        if (lookup == null || !ContactsContract.AUTHORITY.equals(lookup.getAuthority()))
            throw new IllegalArgumentException("Contact lookup unavailable");
        try (Cursor cursor = query(resolver, lookup, new String[]{ContactsContract.Contacts._ID},
                null, null, null, 1, cancel)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getLong(0) > 0) return cursor.getLong(0);
        }
        throw new IllegalArgumentException("Contact lookup unavailable");
    }

    private void loadThread(int direction) {
        if (busy || !isOpen() || result.domain != DeviceSearch.SMS
                || !BuildConfig.SMS_ENABLED || !DeviceSearch.hasSms(activity)) return;
        SmsPage previous = page;
        setBusy(true);
        replaceWithMessage("Loading messages…");
        try {
            work.execute(() -> {
                try {
                    if (!BuildConfig.SMS_ENABLED || !DeviceSearch.hasSms(activity)) throw new SecurityException();
                    ContentResolver resolver = activity.getContentResolver();
                    SmsPage loaded;
                    if (direction == 0 || previous == null) {
                        SmsRow selected = selectedMessage(resolver, result.uri, cancel);
                        if (selected.thread <= 0) {
                            loaded = new SmsPage(Collections.singletonList(selected), false, false);
                        } else {
                            // Frame the actual search match, even when it is years behind newer messages.
                            List<SmsRow> before = page(resolver, selected, -1, PAGE_SIZE / 2, cancel);
                            List<SmsRow> after = page(resolver, selected, 1, PAGE_SIZE / 2, cancel);
                            boolean hasOlder = before.size() > PAGE_SIZE / 2;
                            boolean hasNewer = after.size() > PAGE_SIZE / 2;
                            List<SmsRow> rows = new ArrayList<>(PAGE_SIZE + 1);
                            for (int i = Math.min(before.size(), PAGE_SIZE / 2) - 1; i >= 0; i--) rows.add(before.get(i));
                            rows.add(selected);
                            rows.addAll(after.subList(0, Math.min(after.size(), PAGE_SIZE / 2)));
                            loaded = new SmsPage(rows, hasOlder, hasNewer);
                        }
                    } else {
                        SmsRow boundary = previous.rows.get(direction < 0 ? 0 : previous.rows.size() - 1);
                        List<SmsRow> batch = page(resolver, boundary, direction, PAGE_SIZE, cancel);
                        if (batch.isEmpty()) {
                            loaded = new SmsPage(previous.rows, direction > 0 && previous.hasOlder,
                                    direction < 0 && previous.hasNewer);
                        } else {
                            boolean more = batch.size() > PAGE_SIZE;
                            List<SmsRow> rows = new ArrayList<>(batch.subList(0, Math.min(batch.size(), PAGE_SIZE)));
                            if (direction < 0) Collections.reverse(rows);
                            loaded = new SmsPage(rows, direction < 0 ? more : true,
                                    direction > 0 ? more : true);
                        }
                    }
                    if (!cancel.isCanceled()) main.post(() -> render(loaded));
                } catch (Exception unavailable) {
                    if (!cancel.isCanceled()) main.post(() -> {
                        if (!isOpen()) return;
                        page = null;
                        replaceWithMessage("SMS couldn't be loaded. This device may restrict access.");
                        setBusy(false);
                    });
                }
            });
        } catch (RejectedExecutionException ignored) { /* Already dismissed. */ }
    }

    private static SmsRow selectedMessage(ContentResolver resolver, Uri uri, CancellationSignal cancel) {
        if (uri == null || !Sms.CONTENT_URI.getAuthority().equals(uri.getAuthority()))
            throw new IllegalArgumentException("SMS unavailable");
        try (Cursor cursor = query(resolver, uri, MESSAGE_COLUMNS, null, null, null, 1, cancel)) {
            if (cursor != null && cursor.moveToFirst()) return new SmsRow(cursor);
        }
        throw new IllegalArgumentException("SMS unavailable");
    }

    private static List<SmsRow> page(ContentResolver resolver, SmsRow pivot, int direction,
                                     int limit, CancellationSignal cancel) {
        String select = Sms.THREAD_ID + "=? AND " + (direction < 0 ? OLDER_BY_KEY : NEWER_BY_KEY);
        String order = direction < 0 ? " DESC" : " ASC";
        String[] values = {Long.toString(pivot.thread), Long.toString(pivot.date),
                Long.toString(pivot.date), Long.toString(pivot.id)};
        List<SmsRow> result = new ArrayList<>();
        try (Cursor rows = query(resolver, Sms.CONTENT_URI, MESSAGE_COLUMNS, select, values,
                Sms.DATE + order + ", " + Sms._ID + order, limit + 1, cancel)) {
            while (rows != null && result.size() < limit + 1 && rows.moveToNext()) {
                cancel.throwIfCanceled();
                result.add(new SmsRow(rows));
            }
        }
        return result;
    }

    private static Cursor query(ContentResolver resolver, Uri uri, String[] columns, String where,
                                String[] args, String order, int limit, CancellationSignal cancel) {
        cancel.throwIfCanceled();
        Bundle query = new Bundle();
        if (where != null) query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where);
        if (args != null) query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args);
        if (order != null) query.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order);
        query.putInt(ContentResolver.QUERY_ARG_LIMIT, limit);
        return resolver.query(uri, columns, query, cancel);
    }

    private void render(SmsPage loaded) {
        if (!isOpen()) return;
        if (!BuildConfig.SMS_ENABLED || !DeviceSearch.hasSms(activity)) { sheet.dismiss(); return; }
        page = loaded;
        content.removeAllViews();
        TextView anchor = null;
        DateFormat dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (SmsRow row : page.rows) {
            String meta = direction(row.type);
            if (row.date > 0) meta += " · " + dates.format(new java.util.Date(row.date));
            boolean selected = Long.toString(row.id).equals(result.id);
            if (selected) meta = "Opened message · " + meta;
            TextView box = append(row.body.isEmpty() ? "No text" : row.body, meta, selected);
            if (selected) anchor = box;
        }
        TextView position = anchor;
        scroll.post(() -> {
            if (!isOpen()) return;
            scroll.scrollTo(0, position != null && position.getParent() == content ? position.getTop() : 0);
        });
        setBusy(false);
    }

    private static String direction(int type) {
        switch (type) {
            case Sms.MESSAGE_TYPE_INBOX: return "Received";
            case Sms.MESSAGE_TYPE_SENT: return "Sent";
            case Sms.MESSAGE_TYPE_DRAFT: return "Draft";
            case Sms.MESSAGE_TYPE_OUTBOX:
            case Sms.MESSAGE_TYPE_QUEUED: return "Pending";
            case Sms.MESSAGE_TYPE_FAILED: return "Not sent";
            default: return "SMS";
        }
    }

    private TextView append(String value, String meta, boolean highlighted) {
        TextView text = sheet.text(meta == null ? value : meta + "\n" + value, 15, sheet.theme.ink);
        int space = sheet.dp(12);
        text.setPadding(space, space, space, space);
        text.setGravity(Gravity.START | Gravity.TOP);
        text.setBackground(AppSheet.shape(highlighted ? sheet.theme.selected : sheet.theme.chrome, sheet.dp(12)));
        LinearLayout.LayoutParams margins = new LinearLayout.LayoutParams(-1, -2);
        margins.bottomMargin = sheet.dp(8);
        content.addView(text, margins);
        return text;
    }

    private void setBusy(boolean value) {
        busy = value;
        older.setEnabled(!busy && page != null && page.hasOlder);
        newer.setEnabled(!busy && page != null && page.hasNewer);
    }

    private void replaceWithMessage(String status) { content.removeAllViews(); append(status, null, false); }
    private boolean isOpen() { return !closed && !activity.isDestroyed() && sheet.isShowing(); }
    private void replaceIfOpen(String status) { if (isOpen()) replaceWithMessage(status); }

    private static String bound(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end) + "…";
    }

    private static final class SmsRow {
        final long id, thread, date;
        final int type;
        final String body;
        SmsRow(Cursor cursor) {
            id = cursor.getLong(0); thread = cursor.getLong(1); date = cursor.getLong(2);
            type = cursor.getInt(3); body = bound(cursor.getString(4), BODY_LIMIT);
        }
    }

    private static final class SmsPage {
        final List<SmsRow> rows;
        final boolean hasOlder, hasNewer;
        SmsPage(List<SmsRow> rows, boolean hasOlder, boolean hasNewer) {
            this.rows = rows; this.hasOlder = hasOlder; this.hasNewer = hasNewer;
        }
    }
}
