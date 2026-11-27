package dev.fastsearch.android;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.TextView;
import java.io.InputStream;
import java.security.MessageDigest;

/** Synthetic reader in the distinct instrumentation package; never present in production. */
public final class ExternalOpenReceiver extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView result = new TextView(this);
        result.setPadding(16,16,16,16);
        try {
            Intent intent = getIntent();
            Uri uri = intent.getData();
            if (!Intent.ACTION_VIEW.equals(intent.getAction()) || uri == null
                    || (intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0)
                throw new AssertionError("Missing read handoff");
            String name;
            long bytes;
            try (Cursor cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)) {
                if (cursor == null || !cursor.moveToFirst()) throw new AssertionError("No metadata");
                name = cursor.getString(0); bytes = cursor.getLong(1);
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long read = 0;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new AssertionError("No descriptor");
                byte[] buffer = new byte[4096];
                for (int size; (size = in.read(buffer)) != -1;) {
                    read += size;
                    if (read > 16 * 1024 * 1024) throw new AssertionError("Oversize synthetic fixture");
                    sha.update(buffer,0,size);
                }
            }
            if (read != bytes || !intent.getType().equals(getContentResolver().getType(uri)))
                throw new AssertionError("Inconsistent metadata");
            try {
                try (java.io.OutputStream ignored = getContentResolver().openOutputStream(uri,"w")) {
                    throw new AssertionError("Receiver must not have write access");
                }
            } catch (SecurityException | java.io.FileNotFoundException expected) { }
            result.setText("OPEN_OK " + name + " " + intent.getType() + " " + hex(sha.digest()));
        } catch (Exception | AssertionError failure) {
            result.setText("OPEN_FAIL " + failure.getClass().getSimpleName() + " " + failure.getMessage());
        }
        setContentView(result);
    }

    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int value=b & 255;
            text.append(Character.forDigit(value>>>4,16)).append(Character.forDigit(value & 15,16));
        }
        return text.toString();
    }
}
