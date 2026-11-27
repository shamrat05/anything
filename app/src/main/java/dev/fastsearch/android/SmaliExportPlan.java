package dev.fastsearch.android;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Pure rules for one reviewed, complete generated Smali document. */
final class SmaliExportPlan {
    static final String EXTERNAL = "com.android.externalstorage.documents";
    static final String DOWNLOADS = "com.android.providers.downloads.documents";
    static final int MAX_SOURCE_CHARS = 90_000, MAX_BYTES = 4 * MAX_SOURCE_CHARS;

    private SmaliExportPlan() {}
    static boolean localAuthority(String authority) { return EXTERNAL.equals(authority) || DOWNLOADS.equals(authority); }
    static boolean smaliName(String name) { return name != null && name.toLowerCase(Locale.ROOT).endsWith(".smali"); }
    static String suggestedName(String className) {
        String leaf = className.substring(className.lastIndexOf('.')+1);
        StringBuilder clean = new StringBuilder();
        for (int i=0; i<leaf.length() && clean.length()<80; i++) {
            char c=leaf.charAt(i);
            if ((c>='0' && c<='9') || (c>='a' && c<='z') || (c>='A' && c<='Z') || c=='$' || c=='_' || c=='-') clean.append(c);
            else clean.append('_');
        }
        return (clean.length()==0?"Class":clean.toString())+".smali";
    }
    static byte[] bytes(String code) throws IOException {
        if (code == null || code.isEmpty() || code.length() > MAX_SOURCE_CHARS)
            throw new IOException("Only a complete Smali preview can be saved.");
        ByteBuffer utf8=StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(code));
        if(utf8.remaining()>MAX_BYTES)throw new IOException("Smali export is too large.");
        byte[] encoded=new byte[utf8.remaining()]; utf8.get(encoded);
        return encoded;
    }
    static String digest(byte[] snapshot) {
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(snapshot);
            StringBuilder hex=new StringBuilder(hash.length*2);
            for(byte b:hash)hex.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
            return hex.toString();
        } catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
