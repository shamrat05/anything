package dev.fastsearch.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Plain text only. A file's HTML, scripts and links are never executed. */
final class PreviewText {
    static final int MAX_BYTES = 128 * 1024;
    static final int MAX_CHARACTERS = 128 * 1024;
    static final int MAX_LINES = 2000;

    static final class Result {
        final String text, encoding;
        final boolean truncated;
        Result(String text, String encoding, boolean truncated) {
            this.text = text;
            this.encoding = encoding;
            this.truncated = truncated;
        }
    }

    static Result read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        while (bytes.size() <= MAX_BYTES) {
            cancelled();
            int count = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - bytes.size()));
            if (count == -1) break;
            if (count == 0) continue;
            bytes.write(buffer, 0, count);
        }
        byte[] data = bytes.toByteArray();
        int length = Math.min(data.length, MAX_BYTES), start = 0;
        boolean truncated = data.length > MAX_BYTES;
        Charset charset = StandardCharsets.UTF_8;
        if (length >= 3 && u(data[0]) == 0xEF && u(data[1]) == 0xBB && u(data[2]) == 0xBF) start = 3;
        else if (length >= 2 && u(data[0]) == 0xFF && u(data[1]) == 0xFE) {
            charset = StandardCharsets.UTF_16LE; start = 2;
        } else if (length >= 2 && u(data[0]) == 0xFE && u(data[1]) == 0xFF) {
            charset = StandardCharsets.UTF_16BE; start = 2;
        } else {
            int controls = 0;
            for (int i = 0; i < length; i++) {
                int value = u(data[i]);
                if (value == 0) throw new IOException("This file contains binary data. Open it in another app.");
                if (value < 32 && value != 9 && value != 10 && value != 12 && value != 13) controls++;
            }
            if (controls > Math.max(4, length / 50)) throw new IOException("This file contains binary data. Open it in another app.");
        }
        String decoded = new String(data, start, length - start, charset);
        StringBuilder text = new StringBuilder(Math.min(decoded.length(), MAX_CHARACTERS));
        int lines = 1;
        for (int i = 0; i < decoded.length(); i++) {
            cancelled();
            char ch = decoded.charAt(i);
            if (text.length() >= MAX_CHARACTERS || lines > MAX_LINES) { truncated = true; break; }
            boolean lineBreak = ch == '\r' || (ch == '\n' && (i == 0 || decoded.charAt(i - 1) != '\r'));
            if (lineBreak && lines >= MAX_LINES) { truncated = true; break; }
            if (lineBreak) lines++;
            if (ch == 0 || (ch < 32 && ch != '\n' && ch != '\r' && ch != '\t' && ch != '\f')) ch = '\uFFFD';
            text.append(ch);
        }
        return new Result(text.toString(), charset.name(), truncated);
    }

    private static int u(byte value) { return value & 255; }
    static void cancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Preview stopped.");
    }
    private PreviewText() { }
}
