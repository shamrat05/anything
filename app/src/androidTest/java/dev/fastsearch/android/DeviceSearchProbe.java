package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.provider.ContactsContract;
import android.provider.Telephony;

import java.util.List;
import java.util.Locale;

/** Queries synthetic contacts/SMS only. It never reads or changes the real providers. */
public final class DeviceSearchProbe extends Instrumentation {
    private int checks;
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        boolean passed = false;
        try {
            appChecks();
            if (Build.VERSION.SDK_INT >= 29) syntheticChecks();
            else report.append("Synthetic resolver checks require API 29.\n");
            passed = true;
        } catch (Throwable failure) {
            java.io.StringWriter stack = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(stack));
            report.append(stack);
        }
        result.putString("stream", report.append(passed ? "PASS " : "FAIL ").append(checks)
                .append(" device-search assertions.\n").toString());
        result.putBoolean("passed", passed);
        result.putInt("assertions", checks);
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }

    private void appChecks() throws Exception {
        Context target = getTargetContext().getApplicationContext();
        DeviceSearch search = new DeviceSearch(target);
        List<DeviceSearch.Result> own = search.search(target.getPackageName().toUpperCase(Locale.ROOT),
                DeviceSearch.APPS, new CancellationSignal());
        check(own.stream().anyMatch(x -> x.packageName.equals(target.getPackageName())), "case-insensitive own-package search");
        check(own.stream().allMatch(x -> x.domain == DeviceSearch.APPS && "package".equals(x.uri.getScheme())), "app result action identity");
        boolean immutable = false;
        try { own.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "result snapshot is immutable");
        CancellationSignal canceled = new CancellationSignal(); canceled.cancel();
        boolean stopped = false;
        try { search.search("", DeviceSearch.APPS, canceled); }
        catch (OperationCanceledException expected) { stopped = true; }
        check(stopped, "pre-canceled app query stops");
        boolean invalid = false;
        try { search.search("", 4, null); } catch (IllegalArgumentException expected) { invalid = true; }
        check(invalid, "unknown domain rejected");
        search.invalidateApps();
        check(search.search(target.getPackageName(), DeviceSearch.APPS, null).stream()
                .anyMatch(x -> x.packageName.equals(target.getPackageName())), "app cache can refresh");
    }

    @android.annotation.TargetApi(29)
    private void syntheticChecks() throws Exception {
        Context target = getTargetContext().getApplicationContext();
        FixtureProvider provider = new FixtureProvider();
        ProviderInfo info = new ProviderInfo(); info.authority = "com.android.contacts;sms";
        info.applicationInfo = target.getApplicationInfo();
        provider.attachInfo(target, info);
        FixtureContext context = new FixtureContext(target, ContentResolver.wrap(provider));
        DeviceSearch search = new DeviceSearch(context);
        try {
            check(!DeviceSearch.hasContacts(context) && !DeviceSearch.hasSms(context), "permission helpers reflect denial");
            check(search.search("fixture", DeviceSearch.CONTACTS, null).isEmpty(), "contacts denied without a query");
            check(search.search("fixture", DeviceSearch.SMS, null).isEmpty(), "SMS denied without a query");
            check(provider.queries == 0, "denied domains never reach any provider");
            context.contacts = true;
            List<DeviceSearch.Result> contacts = search.search("fixture", DeviceSearch.CONTACTS, new CancellationSignal());
            check(contacts.size() == 3, "contact IDs deduplicated and name-only contact included");
            check(contacts.stream().filter(x -> x.id.equals("7")).count() == 1, "multiple phone rows produce one contact");
            check(contacts.stream().anyMatch(x -> x.id.equals("9") && x.uri.equals(
                    Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, "9"))), "name-only contact has a view URI");
            check(provider.closes == 2 && provider.queries == 2, "both contact cursors closed");
            search.search("+1 (234)", DeviceSearch.CONTACTS, null);
            check("%+1234%".equals(provider.phoneArgs[2]), "formatted phone query also searches normalized digits");

            provider.manyPhoneRows = true;
            provider.phoneMoves = 0;
            contacts = search.search("fixture", DeviceSearch.CONTACTS, null);
            check(contacts.size() == 1 && provider.phoneMoves == DeviceSearch.MAX_PROVIDER_ROWS,
                    "client caps duplicate provider rows even when provider ignores limit");
            provider.manyPhoneRows = false;

            context.sms = true;
            provider.addSms(1, "+100", "Fixture literal 50%_\\' body", 1);
            provider.addSms(2, "+200", "Fixture literal 50ABz' body", 2);
            List<DeviceSearch.Result> sms = search.search("50%_\\'", DeviceSearch.SMS, new CancellationSignal());
            check(sms.size() == 1 && sms.get(0).id.equals("1"), "percent underscore backslash and quote remain literal SQL values");
            check(provider.smsSelection.contains(" LIKE ? ESCAPE ") && !provider.smsSelection.contains("50"), "user text stays outside SQL selection");
            check(provider.lastLimit == DeviceSearch.MAX_PROVIDER_ROWS && provider.sawSignal, "bounded structured query forwards cancellation");
            check(sms.get(0).detail.contains("Fixture literal 50%_\\' body"), "SMS body remains available in the transient result");
            check(sms.get(0).uri.equals(Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, "1")), "SMS result retains exact content URI");
            check(sms.get(0).packageName.isEmpty(), "SMS result carries no app launch identity");
            provider.addSms(3, "needle sender", "unrelated body", 3);
            check(search.search("needle", DeviceSearch.SMS, null).size() == 1, "SMS address is searchable");
            for (int i = 4; i < 120; i++) provider.addSms(i, "+100", "Fixture body", i);
            StringBuilder longBody = new StringBuilder("Fixture ");
            while (longBody.length() < 10_000) longBody.append('x');
            provider.addSms(120, "+100", longBody.toString(), 120);
            sms = search.search("Fixture", DeviceSearch.SMS, null);
            check(sms.size() == DeviceSearch.MAX_RESULTS_PER_DOMAIN, "SMS result count is bounded");
            check(sms.get(0).id.equals("120") && sms.get(0).detail.endsWith("[Message truncated at 8192 characters]"), "newest message first and detail truncation is explicit");
            check(sms.get(0).subtitle.length() <= 180 && sms.get(0).detail.length() < 8400, "SMS preview and detail memory are bounded");
            CancellationSignal canceled = new CancellationSignal();
            provider.cancelDuringMove = canceled;
            int closed = provider.closes;
            boolean stopped = false;
            try { search.search("Fixture", DeviceSearch.SMS, canceled); }
            catch (OperationCanceledException expected) { stopped = true; }
            check(stopped && provider.closes == closed + 1, "cancellation while consuming closes the cursor");
            provider.cancelDuringMove = null;
            provider.bulkContacts=4230;
            java.util.Set<String> contactIds=new java.util.HashSet<>();String next=null;int contactPages=0;
            do{DeviceSearch.Page page=search.page("",DeviceSearch.CONTACTS,next,new CancellationSignal());
                check(page.results.size()<=100,"contact page stays bounded");for(DeviceSearch.Result row:page.results)check(contactIds.add(row.id),"contact pages never repeat a contact");
                next=page.next;if(++contactPages>100)throw new AssertionError("Contact cursor did not advance");}while(next!=null);
            check(contactIds.size()==4232&&contactPages>40,"all 4232 synthetic contacts reachable beyond 100 and 2000, including name-only contacts");
            provider.bulkContacts=0;
            for(int i=121;i<=521;i++)provider.addSms(i,"+100","Fixture body",i);
            java.util.Set<String> messageIds=new java.util.HashSet<>();next=null;int messagePages=0;
            do{DeviceSearch.Page page=search.page("Fixture",DeviceSearch.SMS,next,new CancellationSignal());check(page.results.size()<=100,"SMS page stays bounded");
                for(DeviceSearch.Result row:page.results)check(messageIds.add(row.id),"SMS keyset never repeats a message");next=page.next;if(++messagePages>20)throw new AssertionError("SMS cursor did not advance");}while(next!=null);
            check(messageIds.size()==520,"all 520 matching messages reachable beyond the first 100");
            provider.bulkContacts=230;next=null;java.util.Set<String> combinedIds=new java.util.HashSet<>();int combinedPages=0;
            do{DeviceSearch.Page page=search.page("Fixture",DeviceSearch.ALL,next,new CancellationSignal());check(page.results.size()<=100,"combined device page remains bounded");
                for(DeviceSearch.Result row:page.results)check(combinedIds.add(row.domain+":"+row.id),"combined pages never repeat cross-domain identity");
                next=page.next;if(++combinedPages>30)throw new AssertionError("Combined cursor did not terminate");}while(next!=null);
            check(combinedIds.stream().filter(id->id.startsWith(DeviceSearch.CONTACTS+":")).count()==232,"combined search includes all contacts beyond 100");
            check(combinedIds.stream().filter(id->id.startsWith(DeviceSearch.SMS+":")).count()==520,"combined search includes all matching messages beyond 100");
            provider.deny = true;
            boolean denied = false;
            try { search.search("Fixture", DeviceSearch.SMS, null); }
            catch (SecurityException expected) { denied = true; }
            check(denied, "provider permission revocation remains visible");
        } finally {
            provider.database.close();
        }
    }

    static final class FixtureContext extends ContextWrapper {
        final ContentResolver resolver;
        boolean contacts, sms;
        FixtureContext(Context app, ContentResolver resolver) { super(app); this.resolver = resolver; }
        @Override public Context getApplicationContext() { return this; }
        @Override public ContentResolver getContentResolver() { return resolver; }
        @Override public int checkSelfPermission(String permission) {
            if (android.Manifest.permission.READ_CONTACTS.equals(permission))
                return contacts ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
            if (android.Manifest.permission.READ_SMS.equals(permission))
                return sms ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
            return PackageManager.PERMISSION_DENIED;
        }
    }

    static final class FixtureProvider extends ContentProvider {
        final SQLiteDatabase database = SQLiteDatabase.create(null);
        int queries, closes, phoneMoves, lastLimit;
        boolean manyPhoneRows, deny, sawSignal;
        int bulkContacts;
        String[] phoneArgs;
        String smsSelection;
        CancellationSignal cancelDuringMove;

        FixtureProvider() { database.execSQL("CREATE TABLE sms (_id INTEGER PRIMARY KEY,address TEXT,body TEXT,date INTEGER)"); }
        @Override public boolean onCreate() { return true; }
        void addSms(long id, String address, String body, long date) {
            ContentValues values = new ContentValues(); values.put("_id", id); values.put("address", address);
            values.put("body", body); values.put("date", date);
            database.insertOrThrow("sms", null, values);
        }
        @Override public Cursor query(Uri uri, String[] projection, Bundle args, CancellationSignal signal) {
            if (deny) throw new SecurityException("Synthetic revoked permission");
            queries++;
            String selection = args.getString(ContentResolver.QUERY_ARG_SQL_SELECTION);
            String[] values = args.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS);
            lastLimit = args.getInt(ContentResolver.QUERY_ARG_LIMIT);
            sawSignal |= signal != null;
            final boolean phone = uri.getPath().contains("phones");
            Cursor raw;
            if ("sms".equals(uri.getAuthority())) {
                smsSelection = selection;
                raw = database.query(false, "sms", projection, selection, values, null, null,
                        args.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER), Integer.toString(lastLimit), signal);
            } else {
                MatrixCursor rows = new MatrixCursor(projection);
                if(bulkContacts>0){long after=Long.parseLong(values[0]);
                    if(phone){for(long id=Math.max(1,after+1);id<=Math.min(bulkContacts,after+1000);id++)rows.addRow(new Object[]{id,"Contact "+id,"+"+id,"key"+id});}
                    else{for(long id=10000;id<=10001;id++)if(id>after)rows.addRow(new Object[]{id,"Name only "+id,"key"+id});}
                }else if (phone) {
                    phoneArgs = values;
                    if (manyPhoneRows) {
                        for (int i = 0; i < 1005; i++) rows.addRow(new Object[]{7L, "Fixture Name", "+100", "key7"});
                        rows.addRow(new Object[]{8L, "Beyond cap", "+200", "key8"});
                    } else {
                        rows.addRow(new Object[]{7L, "Fixture Name", "+100", "key7"});
                        rows.addRow(new Object[]{7L, "Fixture Name", "+101", "key7"});
                        rows.addRow(new Object[]{8L, "Fixture Other", "+200", "key8"});
                    }
                } else if (!manyPhoneRows) {
                    rows.addRow(new Object[]{7L, "Fixture Name", "key7"});
                    rows.addRow(new Object[]{9L, "Fixture Without Phone", null});
                }
                raw = rows;
            }
            return new CursorWrapper(raw) {
                @Override public boolean moveToNext() {
                    if (phone) phoneMoves++;
                    boolean next = super.moveToNext();
                    if (cancelDuringMove != null) cancelDuringMove.cancel();
                    return next;
                }
                @Override public void close() { closes++; super.close(); }
            };
        }
        @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String order) {
            throw new AssertionError("Expected structured cancellable query");
        }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    }
}
