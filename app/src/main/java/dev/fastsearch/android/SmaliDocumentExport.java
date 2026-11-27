package dev.fastsearch.android;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.IOException;

final class SmaliDocumentExport {
    private static final String[] PICKERS = {"com.android.documentsui","com.google.android.documentsui"};
    private SmaliDocumentExport() {}

    static Intent picker(Context context,String className) {
        // Generic MIME preserves a .smali extension when the platform has no MIME mapping for it.
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_LOCAL_ONLY,true)
                .putExtra(Intent.EXTRA_TITLE,SmaliExportPlan.suggestedName(className));
        PackageManager pm=context.getPackageManager();
        for(String packageName:PICKERS) {
            Intent restricted=new Intent(intent).setPackage(packageName);
            ResolveInfo match=pm.resolveActivity(restricted,PackageManager.MATCH_DEFAULT_ONLY|PackageManager.MATCH_SYSTEM_ONLY);
            if (match!=null && match.activityInfo!=null && match.activityInfo.exported
                    && packageName.equals(match.activityInfo.packageName) && system(match.activityInfo.applicationInfo))
                return restricted.setComponent(new ComponentName(packageName,match.activityInfo.name));
        }
        return null;
    }

    static void write(Context context,Uri destination,byte[] snapshot) throws Exception {
        if(destination==null || !"content".equals(destination.getScheme()) || destination.getQuery()!=null
                || destination.getFragment()!=null || !SmaliExportPlan.localAuthority(destination.getAuthority())
                || !DocumentsContract.isDocumentUri(context,destination))
            throw new IOException("Choose a new local document in Files or Downloads.");
        ProviderInfo provider=context.getPackageManager().resolveContentProvider(destination.getAuthority(),0);
        if(provider==null || !system(provider.applicationInfo))
            throw new IOException("Only local system storage can receive this file.");
        if(context.checkUriPermission(destination,android.os.Process.myPid(),android.os.Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=PackageManager.PERMISSION_GRANTED)
            throw new IOException("Android did not grant access to the new file.");
        ContentResolver resolver=context.getContentResolver();
        try(Cursor row=resolver.query(destination,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(row==null || !row.moveToFirst() || !SmaliExportPlan.smaliName(row.getString(0)))
                throw new IOException("Choose a new file with a .smali name.");
        }
        try(ParcelFileDescriptor fd=resolver.openFileDescriptor(destination,"rw")) {
            if(fd==null)throw new IOException("Android could not open the new document.");
            // rw (never w/rwt) avoids truncating any existing document on open.
            writeEmptyRegular(fd,snapshot);
        }
    }

    static void writeEmptyRegular(ParcelFileDescriptor fd,byte[] bytes) throws IOException,ErrnoException {
        if(bytes==null || bytes.length==0 || bytes.length>SmaliExportPlan.MAX_BYTES)
            throw new IOException("Only a bounded, complete Smali preview can be saved.");
        StructStat start=Os.fstat(fd.getFileDescriptor());
        if(!OsConstants.S_ISREG(start.st_mode) || start.st_size!=0)
            throw new IOException("Choose a new empty document. No existing data was replaced.");
        try(ParcelFileDescriptor.AutoCloseOutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.dup(fd.getFileDescriptor()))) {
            out.getChannel().position(0);
            out.write(bytes);
            out.flush();
            out.getFD().sync();
            StructStat end=Os.fstat(fd.getFileDescriptor());
            if(end.st_size!=bytes.length)throw new IOException("The destination changed while saving.");
        }
    }

    private static boolean system(ApplicationInfo info) {
        return info!=null && (info.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0;
    }
}
