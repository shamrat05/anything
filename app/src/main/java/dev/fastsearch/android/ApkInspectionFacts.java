package dev.fastsearch.android;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/** Non-executing, bounded APK metadata presentation helpers. */
final class ApkInspectionFacts {
    private static final int MAX_ABIS=32;
    private static final char[] HEX="0123456789ABCDEF".toCharArray();
    private ApkInspectionFacts() {}

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException missing) { throw new IllegalStateException("SHA-256 is unavailable",missing); }
    }

    static String hex(byte[] bytes,boolean separators){
        StringBuilder encoded=new StringBuilder(bytes.length*(separators?3:2));
        for(byte value:bytes){
            if(separators&&encoded.length()!=0)encoded.append(':');
            encoded.append(HEX[(value&255)>>>4]).append(HEX[value&15]);
        }
        return encoded.toString();
    }

    static final class NativeABIs {
        final List<String> names;
        final boolean partial;
        NativeABIs(TreeSet<String> names,boolean partial){
            this.names=Collections.unmodifiableList(new ArrayList<>(names));this.partial=partial;
        }
    }

    static NativeABIs nativeABIs(ZipDirectory.Listing listing){
        TreeSet<String> names=new TreeSet<>();
        boolean partial=listing.truncated;
        for(ZipDirectory.Entry entry:listing.entries) {
            if(entry.directory||entry.encrypted||entry.special||entry.size<=0
                    ||(entry.method!=0&&entry.method!=8)||entry.rawName==null
                    ||!entry.rawName.equals(entry.name))continue;
            String abi=nativeABI(entry.rawName);
            if(abi==null||names.contains(abi))continue;
            if(names.size()<MAX_ABIS)names.add(abi);else partial=true;
        }
        return new NativeABIs(names,partial);
    }

    static String nativeABI(String path) {
        if(path==null||path.length()>512||!path.startsWith("lib/")||!path.endsWith(".so"))return null;
        int slash=path.indexOf('/',4);
        if(slash<5||slash>36||path.indexOf('/',slash+1)!=-1)return null;
        String library=path.substring(slash+1);
        if(!library.startsWith("lib")||library.length()<7)return null;
        for(int i=4;i<slash;i++)if(!identifier(path.charAt(i),false))return null;
        for(int i=0;i<library.length();i++)if(!identifier(library.charAt(i),true))return null;
        return path.substring(4,slash);
    }

    private static boolean identifier(char c,boolean dots){
        return c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||c=='_'||c=='-'||dots&&c=='.';
    }
}
