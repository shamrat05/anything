package dev.fastsearch.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Small generated fixtures. Audio samples are silent; no user media is accessed. */
final class PreviewUiFixtures {
    static void image(File file) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(720, 1080, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap); canvas.drawColor(0xFFF0F1FA); Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
            ink.setColor(0xFF1A2FFB); canvas.drawRect(0, 0, 720, 330, ink);
            ink.setColor(0xFFFFB240); canvas.drawCircle(360, 600, 205, ink);
            ink.setColor(0xFF202020); ink.setTextSize(42); canvas.drawText("Preview UI fixture", 44, 965, ink);
            ink.setColor(Color.WHITE); ink.setTextSize(54); canvas.drawText("Synthetic image", 44, 180, ink);
            try (FileOutputStream out = new FileOutputStream(file)) { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG fixture encoding failed."); }
        } finally { bitmap.recycle(); }
    }
    static void aspectImage(File file,int width,int height,int orientation) throws IOException {
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        try{
            Canvas canvas=new Canvas(bitmap);canvas.drawColor(0xFF1A2FFB);Paint ink=new Paint();
            ink.setColor(0xFFFFB240);canvas.drawRect(0,0,width*.25f,height,ink);
            try(FileOutputStream out=new FileOutputStream(file)){
                if(!bitmap.compress(orientation==1?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG,95,out))throw new IOException("Aspect fixture encoding failed.");
            }
        }finally{bitmap.recycle();}
        if(orientation!=1){android.media.ExifInterface exif=new android.media.ExifInterface(file.getPath());
            exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,Integer.toString(orientation));exif.saveAttributes();}
    }
    static void pdf(File file) throws IOException {
        PdfDocument document = new PdfDocument();
        try {
            Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int i = 0; i < 3; i++) {
                PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(600, 800, i + 1).create());
                Canvas canvas = page.getCanvas(); canvas.drawColor(Color.WHITE);
                ink.setColor(0xFF1A2FFB); canvas.drawRect(30, 30, 570, 190, ink);
                ink.setColor(Color.WHITE); ink.setTextSize(26); canvas.drawText("Synthetic PDF page " + (i + 1), 55, 105, ink);
                ink.setColor(0xFF202020); ink.setTextSize(17); canvas.drawText("Only generated fixture content appears here.", 40, 250, ink);
                document.finishPage(page);
            }
            try (FileOutputStream out = new FileOutputStream(file)) { document.writeTo(out); }
        } finally { document.close(); }
    }
    static void text(File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("Preview UI fixture\n<script>literal text only</script>\n".getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 3000; i++) out.write(("Line " + i + ": generated plain text, snow 雪.\n").getBytes(StandardCharsets.UTF_8));
        }
    }
    static void archive(File file, File pdf) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            out.putNextEntry(new ZipEntry("folder/")); out.closeEntry();
            out.putNextEntry(new ZipEntry("folder/snow 雪.txt")); out.write(new byte[1024 * 1024]); out.closeEntry();
            out.putNextEntry(new ZipEntry("../labels-only\nentry.txt")); out.write("Archived note streamed from ZIP.".getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
            out.putNextEntry(new ZipEntry("folder/safe-note.txt")); out.write("Archived note streamed from ZIP.".getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
            out.putNextEntry(new ZipEntry("folder/embedded.pdf"));
            try (FileInputStream input = new FileInputStream(pdf)) {
                byte[] bytes = new byte[4096];
                for (int length; (length = input.read(bytes)) != -1;) out.write(bytes, 0, length);
            }
            out.closeEntry();
        }
    }
    static void wav(File file) throws IOException {
        int rate = 8000, dataBytes = rate * 2 * 30;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("RIFF".getBytes(StandardCharsets.US_ASCII)); little(out, dataBytes + 36, 4); out.write("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
            little(out, 16, 4); little(out, 1, 2); little(out, 1, 2); little(out, rate, 4); little(out, rate * 2, 4); little(out, 2, 2); little(out, 16, 2);
            out.write("data".getBytes(StandardCharsets.US_ASCII)); little(out, dataBytes, 4);
            byte[] silence = new byte[4096]; for (int remaining = dataBytes; remaining > 0;) { int length = Math.min(remaining, silence.length); out.write(silence, 0, length); remaining -= length; }
        }
    }
    private static void little(FileOutputStream out, int value, int bytes) throws IOException { for (int i = 0; i < bytes; i++) out.write(value >>> (i * 8)); }

    static void video(File file) throws Exception {
        final int width = 320, height = 240, frameRate = 10, frames = 120;
        MediaCodec codec = null; MediaMuxer muxer = null; boolean started = false;
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 240000); format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate); format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start();
            muxer = new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int nextFrame = 0, track = -1; boolean inputEnded = false, outputEnded = false;
            long deadline = SystemClock.elapsedRealtime() + 25000;
            while (!outputEnded) {
                if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Synthetic platform video encoding timed out.");
                if (!inputEnded) {
                    int input = codec.dequeueInputBuffer(10000);
                    if (input >= 0) {
                        if (nextFrame == frames) { codec.queueInputBuffer(input, 0, 0, nextFrame * 1000000L / frameRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true; }
                        else {
                            Image image = codec.getInputImage(input);
                            if (image == null) throw new IOException("Platform encoder has no flexible YUV input image.");
                            try {
                                if (image.getPlanes().length != 3) throw new IOException("Platform encoder input is not YUV420.");
                                fill(image.getPlanes()[0], width, height, nextFrame, true);
                                fill(image.getPlanes()[1], width / 2, height / 2, 90, false);
                                fill(image.getPlanes()[2], width / 2, height / 2, 200, false);
                            } finally { image.close(); }
                            codec.queueInputBuffer(input, 0, width * height * 3 / 2, nextFrame * 1000000L / frameRate, 0); nextFrame++;
                        }
                    }
                }
                int output = codec.dequeueOutputBuffer(info, 10000);
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (started) throw new IOException("Encoder format changed twice.");
                    track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); started = true;
                } else if (output >= 0) {
                    try {
                        ByteBuffer buffer = codec.getOutputBuffer(output);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                            if (!started || buffer == null) throw new IOException("Encoder output preceded format.");
                            buffer.position(info.offset); buffer.limit(info.offset + info.size); muxer.writeSampleData(track, buffer, info);
                        }
                        outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally { codec.releaseOutputBuffer(output, false); }
                }
            }
        } finally {
            try {
                if (codec != null) { try { codec.stop(); } catch (IllegalStateException unstarted) { /* Configuration may have failed. */ } finally { codec.release(); } }
            } finally {
                if (muxer != null) { try { if (started) muxer.stop(); } finally { muxer.release(); } }
            }
        }
    }
    private static void fill(Image.Plane plane, int width, int height, int value, boolean pattern) {
        ByteBuffer buffer = plane.getBuffer(); int row = plane.getRowStride(), pixel = plane.getPixelStride(), base = buffer.position();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) buffer.put(base + y * row + x * pixel, (byte) (pattern ? 45 + ((x / 32 + y / 24 + value / 5) % 5) * 30 : value));
    }
    private PreviewUiFixtures() { }
}
