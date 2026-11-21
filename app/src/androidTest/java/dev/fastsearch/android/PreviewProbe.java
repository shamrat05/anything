package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import dev.fastsearch.core.FileEntry;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Native decoder and descriptor fixtures only. This probe never launches an Activity. */
public final class PreviewProbe extends Instrumentation {
    private Context target;
    private File fixture;
    private boolean ownsFixture;
    private int assertions;
    private final List<String> tokens = new ArrayList<>();
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        boolean passed = false;
        try {
            target = getTargetContext(); runFixtures(); passed = true;
        } catch (Throwable failure) {
            java.io.StringWriter trace = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(trace)); report.append(trace);
        } finally {
            try { cleanup(); } catch (Exception failure) { passed = false; report.append("Fixture cleanup failed: ").append(failure.getClass().getSimpleName()).append('\n'); }
        }
        Bundle result = new Bundle(); result.putBoolean("passed", passed); result.putInt("assertions", assertions);
        result.putString("stream", report.append(passed ? "PASS " : "FAIL ").append(assertions).append(" preview assertions. No UI launched.\n").toString());
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void runFixtures() throws Exception {
        fixture = new File(target.getCacheDir(), "PreviewProbe-" + SystemClock.elapsedRealtimeNanos()).getCanonicalFile();
        ownsFixture = fixture.mkdir(); check(ownsFixture, "synthetic cache fixture");
        File large = new File(fixture, "large.png"); png(large, 8192, 4096); Uri largeUri = issue(large);
        FileViewerActivity.PreviewCheck image = inspect(largeUri, "large.png", 0);
        check(image.error == null && image.width == 8192 && image.height == 4096, "native image header and decode");
        check(image.bitmapWidth <= FileViewerActivity.MAX_BITMAP_EDGE && image.bitmapHeight <= FileViewerActivity.MAX_BITMAP_EDGE
                && (long) image.bitmapWidth * image.bitmapHeight <= FileViewerActivity.MAX_BITMAP_PIXELS, "native image pixel limit");

        File jpeg = new File(fixture, "oriented.jpg"); Bitmap tiny = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888); tiny.eraseColor(Color.BLUE);
        try (FileOutputStream out = new FileOutputStream(jpeg)) { check(tiny.compress(Bitmap.CompressFormat.JPEG, 90, out), "create EXIF fixture"); }
        finally { tiny.recycle(); }
        ExifInterface exif = new ExifInterface(jpeg.getPath()); exif.setAttribute(ExifInterface.TAG_ORIENTATION, "6"); exif.saveAttributes();
        FileViewerActivity.PreviewCheck rotated = inspect(issue(jpeg), "oriented.jpg", 0);
        check(rotated.error == null && rotated.orientation == 6, "native EXIF orientation retained without a second bitmap");

        File pdf = new File(fixture, "pages.pdf");
        PdfDocument document = new PdfDocument();
        try {
            for (int page = 0; page < 3; page++) {
                PdfDocument.Page sheet = document.startPage(new PdfDocument.PageInfo.Builder(600, 800, page + 1).create());
                Paint ink = new Paint(); ink.setColor(Color.BLACK); ink.setTextSize(18); sheet.getCanvas().drawText("Synthetic preview page " + page, 30, 40, ink); document.finishPage(sheet);
            }
            try (FileOutputStream out = new FileOutputStream(pdf)) { document.writeTo(out); }
        } finally { document.close(); }
        Uri pdfUri = issue(pdf); FileViewerActivity.PreviewCheck middle = inspect(pdfUri, "pages.pdf", 1);
        check(middle.error == null && middle.page == 1 && middle.pageCount == 3, "native PDF navigation");
        check(middle.bitmapWidth > 0 && middle.bitmapHeight <= FileViewerActivity.MAX_BITMAP_EDGE
                && (long) middle.bitmapWidth * middle.bitmapHeight <= FileViewerActivity.MAX_BITMAP_PIXELS, "PDF bitmap bound");
        check(inspect(pdfUri, "pages.pdf", Integer.MAX_VALUE).page == 2, "saved PDF page clamps to final page");

        File text = new File(fixture, "literal.html"); String literal = "<script>literal text only</script>\n";
        write(text, literal.getBytes(StandardCharsets.UTF_8)); Uri textUri = issue(text);
        check(literal.equals(inspect(textUri, "literal.html", 0).text), "HTML remains plain text in the production reader");
        File longText = new File(fixture, "bounded.txt"); byte[] textBytes = new byte[PreviewText.MAX_BYTES + 1000]; Arrays.fill(textBytes, (byte) 'a'); write(longText, textBytes);
        FileViewerActivity.PreviewCheck boundedText = inspect(issue(longText), "bounded.txt", 0);
        check(boundedText.error == null && boundedText.truncated && boundedText.text.length() <= PreviewText.MAX_CHARACTERS, "text preview bound");

        File archive = new File(fixture, "entries.apk");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(archive))) {
            out.putNextEntry(new ZipEntry("../label-only\nsnow 雪.txt")); out.write(new byte[1024 * 1024]); out.closeEntry();
            out.putNextEntry(new ZipEntry("folder/")); out.closeEntry();
        }
        Uri zipUri = issue(archive); FileViewerActivity.PreviewCheck zipped = inspect(zipUri, "entries.apk", 0);
        check(zipped.error == null && zipped.archiveEntries == 2, "ZIP/APK metadata through provider descriptor");
        File media = new File(fixture, "descriptor.wav"); write(media, new byte[64]); Uri mediaUri = issue(media);
        check(inspect(mediaUri, "descriptor.wav", 0).mediaDescriptor, "media descriptor transfer closes after the check");

        File sparse = new File(fixture, "too-large.png");
        try (RandomAccessFile file = new RandomAccessFile(sparse, "rw")) { file.setLength(FileViewerActivity.MAX_DOCUMENT_BYTES + 1); }
        check(inspect(issue(sparse), "too-large.png", 0).error != null, "large source rejected before decoder allocation");
        File corrupt = new File(fixture, "corrupt.pdf"); write(corrupt, new byte[]{1, 2, 3}); Uri corruptUri = issue(corrupt);
        check(inspect(corruptUri, "corrupt.pdf", 0).error != null, "corrupt PDF has a fallback");
        int before = descriptors();
        for (int i = 0; i < 12; i++) {
            check(inspect(pdfUri, "pages.pdf", i % 3).error == null, "repeated PDF renderer cleanup");
            check(inspect(zipUri, "entries.apk", 0).error == null, "repeated archive stream cleanup");
            check(inspect(mediaUri, "descriptor.wav", 0).mediaDescriptor, "repeated media descriptor cleanup");
            check(inspect(corruptUri, "corrupt.pdf", 0).error != null, "repeated failure cleanup");
        }
        int after = descriptors();
        report.append("Descriptors before/after repeated previews: ").append(before).append('/').append(after).append('\n');
        check(openFixtureDescriptors() == 0, "no fixture descriptor retained after successful or corrupt previews");
        check(after <= before + 2, "descriptor count does not grow with repeated success and failure");
        check(!new File(fixture, "folder").exists(), "archive directories were never extracted");
        report.append("Native images, EXIF, bounded PDF pages, plain text, ZIP metadata and repeated descriptor cleanup checked on synthetic files.\n");
    }
    private FileViewerActivity.PreviewCheck inspect(Uri uri, String name, int page) { return FileViewerActivity.checkForProbe(target, uri, name, page); }
    private Uri issue(File file) throws Exception {
        Uri uri = RootFileProvider.issue(target, new FileEntry(file.getCanonicalPath(), false, file.length(), file.lastModified()), false);
        tokens.add(uri.getPathSegments().get(0)); return uri;
    }
    private static int descriptors() throws IOException {
        String[] files = new File("/proc/self/fd").list(); if (files == null) throw new IOException("Descriptor list unavailable."); return files.length;
    }
    private int openFixtureDescriptors() throws Exception {
        int count = 0;
        File[] descriptors = new File("/proc/self/fd").listFiles();
        if (descriptors == null) throw new IOException("Descriptor list unavailable.");
        for (File descriptor : descriptors) {
            try { if (android.system.Os.readlink(descriptor.getPath()).startsWith(fixture.getPath() + "/")) count++; }
            catch (android.system.ErrnoException vanished) { if (vanished.errno != android.system.OsConstants.ENOENT) throw vanished; }
        }
        return count;
    }
    private void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void write(File file, byte[] bytes) throws IOException { try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); } }
    private static void png(File file, int width, int height) throws IOException {
        // A large real image without allocating a large fixture bitmap in the test process.
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(encoded)) {
            byte[] row = new byte[width + 1]; for (int i = 0; i < height; i++) compressed.write(row);
        }
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(header)) { data.writeInt(width); data.writeInt(height); data.write(new byte[]{8, 0, 0, 0, 0}); }
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}); chunk(out, "IHDR", header.toByteArray()); chunk(out, "IDAT", encoded.toByteArray()); chunk(out, "IEND", new byte[0]);
        }
    }
    private static void chunk(DataOutputStream out, String type, byte[] bytes) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII); CRC32 crc = new CRC32(); crc.update(name); crc.update(bytes);
        out.writeInt(bytes.length); out.write(name); out.write(bytes); out.writeInt((int) crc.getValue());
    }
    private void cleanup() throws IOException {
        if (target != null) {
            SharedPreferences.Editor edit = target.getSharedPreferences("file-grants", Context.MODE_PRIVATE).edit();
            for (String token : tokens) edit.remove(token); if (!edit.commit()) throw new IOException("Grant cleanup failed.");
        }
        if (fixture != null && ownsFixture) {
            if (!fixture.getName().startsWith("PreviewProbe-") || !fixture.getParentFile().getCanonicalFile().equals(target.getCacheDir().getCanonicalFile())) throw new IOException("Invalid fixture root.");
            File[] files = fixture.listFiles(); if (files == null) throw new IOException("Cannot list fixture.");
            for (File file : files) if (!file.delete()) throw new IOException("Cannot remove fixture file.");
            if (!fixture.delete()) throw new IOException("Cannot remove fixture root.");
        }
    }
}
