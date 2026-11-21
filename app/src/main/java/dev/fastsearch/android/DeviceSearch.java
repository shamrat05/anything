package dev.fastsearch.android;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.Telephony;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Worker-thread device search. Contacts and SMS exist only in returned snapshots. */
public final class DeviceSearch {
    public static final int ALL = 0, APPS = 1, CONTACTS = 2, SMS = 3;
    public static final int MAX_RESULTS_PER_DOMAIN = 100;
    static final int MAX_PROVIDER_ROWS = 1000;
    private static final int MAX_DETAIL_CHARS = 8192;
    private static final long APP_CACHE_MILLIS = 30_000;
    private final Context app;
    private final Object appLock = new Object();
    private volatile AppCache appCache;

    public DeviceSearch(Context context) {
        if (context == null || context.getApplicationContext() == null)
            throw new IllegalArgumentException("An application context is required");
        app = context.getApplicationContext();
    }

    public static final class Result {
        public final int domain;
        public final String id, title, subtitle, detail, packageName;
        public final Uri uri;

        private Result(int domain, String id, String title, String subtitle,
                String detail, String packageName, Uri uri) {
            this.domain = domain;
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.detail = detail;
            this.packageName = packageName;
            this.uri = uri;
        }
    }

    public static final class Page {
        public final List<Result> results;
        public final String next;
        Page(List<Result> results, String next) {
            this.results = Collections.unmodifiableList(results); this.next = next;
        }
    }

    /** Keyset pages do not depend on provider OFFSET support or a total-result cap. */
    public Page page(String text, int domain, String continuation, CancellationSignal signal) throws Exception {
        checkCanceled(signal);
        String term = text == null ? "" : text.trim();
        if (term.length() > 512) throw new IllegalArgumentException("Device search is limited to 512 characters");
        if(domain==ALL){int active=APPS;String cursor=null;
            if(continuation!=null){int split=continuation.indexOf(';');active=Integer.parseInt(continuation.substring(0,split));cursor=continuation.substring(split+1);if(cursor.isEmpty())cursor=null;}
            for(;active<=SMS;active++,cursor=null){Page part=page(term,active,cursor,signal);
                String next=part.next!=null?active+";"+part.next:active<SMS?(active+1)+";":null;
                if(!part.results.isEmpty()||part.next!=null)return new Page(new ArrayList<>(part.results),next);}
            return new Page(new ArrayList<>(),null);
        }
        if (domain == APPS) {
            int start = continuation == null ? 0 : Integer.parseInt(continuation);
            List<Result> found = new ArrayList<>(); String needle = term.toLowerCase(Locale.ROOT);
            List<AppEntry> entries = apps(signal); int position = start;
            for (; position < entries.size(); position++) {
                checkCanceled(signal); AppEntry entry = entries.get(position);
                if (!entry.label.toLowerCase(Locale.ROOT).contains(needle) && !entry.packageName.toLowerCase(Locale.ROOT).contains(needle)) continue;
                found.add(new Result(APPS, entry.packageName, entry.label, entry.packageName, entry.packageName,
                        entry.packageName, Uri.parse("package:" + entry.packageName)));
                if (found.size() == MAX_RESULTS_PER_DOMAIN) { position++; break; }
            }
            return new Page(found, position < entries.size() ? Integer.toString(position) : null);
        }
        if (domain == CONTACTS) return hasContacts(app) ? contactPage(term, continuation, signal) : new Page(new ArrayList<>(), null);
        if (domain == SMS) return hasSms(app) ? smsPage(term, continuation, signal) : new Page(new ArrayList<>(), null);
        throw new IllegalArgumentException("Choose a device search category");
    }

    private Page contactPage(String term, String continuation, CancellationSignal signal) {
        String[] token = continuation == null ? new String[]{"0", "-1"} : continuation.split(":");
        int phase = Integer.parseInt(token[0]); long after = Long.parseLong(token[1]);
        List<Result> found = new ArrayList<>();
        String pattern = like(term), normalized = normalizedPhoneTerm(term);
        for (; phase < 2; phase++, after = -1) {
            boolean phone = phase == 0;
            String idColumn = phone ? Phone.CONTACT_ID : ContactsContract.Contacts._ID;
            String selection = idColumn + ">? AND (" + (phone ? Phone.DISPLAY_NAME_PRIMARY + " LIKE ? ESCAPE '\\' OR "
                    + Phone.NUMBER + " LIKE ? ESCAPE '\\' OR " + Phone.NORMALIZED_NUMBER + " LIKE ? ESCAPE '\\'"
                    : ContactsContract.Contacts.HAS_PHONE_NUMBER + "=0 AND " + ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " LIKE ? ESCAPE '\\'") + ")";
            String[] args = phone ? new String[]{Long.toString(after), pattern, pattern, normalized == null ? pattern : like(normalized)}
                    : new String[]{Long.toString(after), pattern};
            String[] columns = phone ? new String[]{Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.LOOKUP_KEY}
                    : new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.LOOKUP_KEY};
            int rows = 0; long previous = after;
            try (Cursor cursor = query(contactLimit(phone ? Phone.CONTENT_URI : ContactsContract.Contacts.CONTENT_URI), columns,
                    selection, args, idColumn + " ASC", signal)) {
                while (cursor != null && rows < MAX_PROVIDER_ROWS && cursor.moveToNext()) {
                    rows++; checkCanceled(signal); long id = cursor.getLong(0);
                    if (id <= previous) continue; previous = id; after = id;
                    String number = phone ? bounded(value(cursor, 2), 128) : "";
                    String label = bounded(value(cursor, 1), 256);
                    if (label.isEmpty()) label = number.isEmpty() ? "Contact" : number;
                    found.add(new Result(CONTACTS, Long.toString(id), label, number.isEmpty() ? "Contact" : number,
                            number, "", contactUri(id, value(cursor, phone ? 3 : 2))));
                    if (found.size() == MAX_RESULTS_PER_DOMAIN) return new Page(found, phase + ":" + after);
                }
            }
            if (rows == MAX_PROVIDER_ROWS && after >= 0) return new Page(found, phase + ":" + after);
        }
        return new Page(found, null);
    }

    private Page smsPage(String term, String continuation, CancellationSignal signal) {
        String[] token = continuation == null ? null : continuation.split(":");
        String pattern = like(term);
        String selection = "(" + Telephony.Sms.BODY + " LIKE ? ESCAPE '\\' OR " + Telephony.Sms.ADDRESS + " LIKE ? ESCAPE '\\')";
        String[] args = new String[]{pattern, pattern};
        if (token != null) {
            selection += " AND (date<? OR (date=? AND _id<?))";
            args = new String[]{pattern, pattern, token[0], token[0], token[1]};
        }
        List<Result> found = new ArrayList<>(); long lastDate = 0, lastId = 0;
        try (Cursor cursor = query(Telephony.Sms.CONTENT_URI,
                new String[]{Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE},
                selection, args, Telephony.Sms.DATE + " DESC, " + Telephony.Sms._ID + " DESC", signal)) {
            while (cursor != null && found.size() < MAX_RESULTS_PER_DOMAIN && cursor.moveToNext()) {
                checkCanceled(signal); lastId = cursor.getLong(0); lastDate = cursor.getLong(3);
                String address = bounded(value(cursor, 1), 160), body = value(cursor, 2);
                String detail = bounded(body, MAX_DETAIL_CHARS);
                if (body.length() > MAX_DETAIL_CHARS) detail += "\n\n[Message truncated at 8192 characters]";
                if (lastDate > 0) detail = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(lastDate)) + "\n\n" + detail;
                found.add(new Result(SMS, Long.toString(lastId), address.isEmpty() ? "SMS message" : address,
                        bounded(body, 180).replace('\n', ' ').replace('\r', ' '), detail, "", ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, lastId)));
            }
        }
        return new Page(found, found.size() == MAX_RESULTS_PER_DOMAIN ? lastDate + ":" + lastId : null);
    }

    public static boolean hasContacts(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasSms(Context context) {
        return BuildConfig.SMS_ENABLED && context.checkSelfPermission(Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Literal substring search; each domain returns at most 100 results. */
    public List<Result> search(String text, int domain, CancellationSignal signal) throws Exception {
        if (domain < ALL || domain > SMS) throw new IllegalArgumentException("Unknown search domain");
        checkCanceled(signal);
        String term = text == null ? "" : text.trim();
        if (term.length() > 512) throw new IllegalArgumentException("Device search is limited to 512 characters");
        List<Result> results = new ArrayList<>();
        if (domain == ALL || domain == APPS) searchApps(term, signal, results);
        if ((domain == ALL || domain == CONTACTS) && hasContacts(app))
            searchContacts(term, signal, results);
        if ((domain == ALL || domain == SMS) && hasSms(app))
            searchSms(term, signal, results);
        checkCanceled(signal);
        return Collections.unmodifiableList(results);
    }

    /** Refresh and package-change handlers can discard the bounded app metadata snapshot. */
    public void invalidateApps() { appCache = null; }

    private void searchApps(String term, CancellationSignal signal, List<Result> results) {
        String needle = term.toLowerCase(Locale.ROOT);
        int count = 0;
        for (AppEntry entry : apps(signal)) {
            checkCanceled(signal);
            if (!entry.label.toLowerCase(Locale.ROOT).contains(needle)
                    && !entry.packageName.toLowerCase(Locale.ROOT).contains(needle)) continue;
            results.add(new Result(APPS, entry.packageName, entry.label, entry.packageName,
                    entry.packageName, entry.packageName, Uri.parse("package:" + entry.packageName)));
            if (++count == MAX_RESULTS_PER_DOMAIN) break;
        }
    }

    private List<AppEntry> apps(CancellationSignal signal) {
        AppCache cached = appCache;
        if (cached != null && SystemClock.elapsedRealtime() - cached.created < APP_CACHE_MILLIS)
            return cached.entries;
        synchronized (appLock) {
            checkCanceled(signal);
            cached = appCache;
            if (cached != null && SystemClock.elapsedRealtime() - cached.created < APP_CACHE_MILLIS)
                return cached.entries;
            PackageManager manager = app.getPackageManager();
            List<ApplicationInfo> installed = manager.getInstalledApplications(0);
            List<AppEntry> entries = new ArrayList<>();
            for (ApplicationInfo info : installed) {
                checkCanceled(signal);
                if (info.packageName == null || info.packageName.isEmpty()) continue;
                CharSequence label = manager.getApplicationLabel(info);
                entries.add(new AppEntry(info.packageName,
                        bounded(label == null ? info.packageName : label.toString(), 256)));
            }
            entries.sort(Comparator.comparing((AppEntry x) -> x.label, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(x -> x.packageName));
            checkCanceled(signal);
            AppCache fresh = new AppCache(Collections.unmodifiableList(entries), SystemClock.elapsedRealtime());
            appCache = fresh;
            return fresh.entries;
        }
    }

    private void searchContacts(String term, CancellationSignal signal, List<Result> results) {
        String pattern = like(term);
        String normalized = normalizedPhoneTerm(term);
        String phonePattern = normalized == null ? pattern : like(normalized);
        Map<Long, Result> contacts = new LinkedHashMap<>();
        String selection = Phone.DISPLAY_NAME_PRIMARY + " LIKE ? ESCAPE '\\' OR "
                + Phone.NUMBER + " LIKE ? ESCAPE '\\' OR "
                + Phone.NORMALIZED_NUMBER + " LIKE ? ESCAPE '\\'";
        try (Cursor cursor = query(contactLimit(Phone.CONTENT_URI),
                new String[]{Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.LOOKUP_KEY},
                selection, new String[]{pattern, pattern, phonePattern},
                Phone.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC, " + Phone.CONTACT_ID + " ASC", signal)) {
            if (cursor != null) {
                int rows = 0;
                while (rows++ < MAX_PROVIDER_ROWS && cursor.moveToNext()) {
                    checkCanceled(signal);
                    long id = cursor.getLong(0);
                    if (contacts.containsKey(id)) continue;
                    String number = bounded(value(cursor, 2), 128);
                    String name = bounded(value(cursor, 1), 256);
                    if (name.isEmpty()) name = number.isEmpty() ? "Contact" : number;
                    contacts.put(id, new Result(CONTACTS, Long.toString(id), name, number,
                            number, "", contactUri(id, value(cursor, 3))));
                    if (contacts.size() == MAX_RESULTS_PER_DOMAIN) break;
                }
            }
        }
        // The Phone table omits contacts with no phone number. Include name-only contacts too.
        if (contacts.size() < MAX_RESULTS_PER_DOMAIN) {
            try (Cursor cursor = query(contactLimit(ContactsContract.Contacts.CONTENT_URI),
                    new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                            ContactsContract.Contacts.LOOKUP_KEY},
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " LIKE ? ESCAPE '\\'",
                    new String[]{pattern}, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC", signal)) {
                if (cursor != null) {
                    int rows = 0;
                    while (rows++ < MAX_PROVIDER_ROWS && cursor.moveToNext()) {
                        checkCanceled(signal);
                        long id = cursor.getLong(0);
                        if (contacts.containsKey(id)) continue;
                        String name = bounded(value(cursor, 1), 256);
                        contacts.put(id, new Result(CONTACTS, Long.toString(id), name.isEmpty() ? "Contact" : name,
                                "Contact", "", "", contactUri(id, value(cursor, 2))));
                        if (contacts.size() == MAX_RESULTS_PER_DOMAIN) break;
                    }
                }
            }
        }
        List<Result> sorted = new ArrayList<>(contacts.values());
        sorted.sort(Comparator.comparing((Result x) -> x.title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(x -> x.id));
        results.addAll(sorted);
    }

    private void searchSms(String term, CancellationSignal signal, List<Result> results) {
        String pattern = like(term);
        String selection = Telephony.Sms.BODY + " LIKE ? ESCAPE '\\' OR "
                + Telephony.Sms.ADDRESS + " LIKE ? ESCAPE '\\'";
        try (Cursor cursor = query(Telephony.Sms.CONTENT_URI,
                new String[]{Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE},
                selection, new String[]{pattern, pattern}, Telephony.Sms.DATE + " DESC", signal)) {
            if (cursor == null) return;
            int rows = 0, count = 0;
            while (rows++ < MAX_PROVIDER_ROWS && cursor.moveToNext()) {
                checkCanceled(signal);
                long id = cursor.getLong(0);
                String address = bounded(value(cursor, 1), 160);
                String body = value(cursor, 2);
                String detail = bounded(body, MAX_DETAIL_CHARS);
                if (body.length() > MAX_DETAIL_CHARS) detail += "\n\n[Message truncated at 8192 characters]";
                long date = cursor.getLong(3);
                if (date > 0) detail = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(date)) + "\n\n" + detail;
                results.add(new Result(SMS, Long.toString(id), address.isEmpty() ? "SMS message" : address,
                        bounded(body, 180).replace('\n', ' ').replace('\r', ' '), detail, "",
                        ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)));
                if (++count == MAX_RESULTS_PER_DOMAIN) break;
            }
        }
    }

    private Cursor query(Uri uri, String[] projection, String selection, String[] args,
            String sort, CancellationSignal signal) {
        checkCanceled(signal);
        Bundle query = new Bundle();
        query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
        query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args);
        query.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort);
        query.putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_PROVIDER_ROWS);
        return app.getContentResolver().query(uri, projection, query, signal);
    }

    private static Uri contactLimit(Uri uri) {
        return uri.buildUpon().appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY,
                Integer.toString(MAX_PROVIDER_ROWS)).build();
    }

    private static Uri contactUri(long id, String lookup) {
        Uri uri = lookup.isEmpty() ? null : ContactsContract.Contacts.getLookupUri(id, lookup);
        return uri == null ? ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id) : uri;
    }

    private static String like(String text) {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String normalizedPhoneTerm(String text) {
        StringBuilder digits = new StringBuilder();
        boolean number = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int digit = Character.digit(ch, 10);
            if (digit >= 0) { digits.append((char) ('0' + digit)); number = true; }
            else if (ch == '+' && digits.length() == 0) digits.append(ch);
            else if (ch != ' ' && ch != '(' && ch != ')' && ch != '-' && ch != '.' && ch != '/') return null;
        }
        return number ? digits.toString() : null;
    }

    private static String value(Cursor cursor, int column) {
        return cursor.isNull(column) ? "" : cursor.getString(column);
    }

    private static String bounded(String value, int max) {
        if (value.length() <= max) return value;
        int end = max;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    private static void checkCanceled(CancellationSignal signal) {
        if (signal != null) signal.throwIfCanceled();
        if (Thread.currentThread().isInterrupted()) throw new OperationCanceledException();
    }

    private static final class AppEntry {
        final String packageName, label;
        AppEntry(String packageName, String label) { this.packageName = packageName; this.label = label; }
    }

    private static final class AppCache {
        final List<AppEntry> entries;
        final long created;
        AppCache(List<AppEntry> entries, long created) { this.entries = entries; this.created = created; }
    }
}
