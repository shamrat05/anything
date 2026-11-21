package dev.fastsearch.android;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Bounded directory metadata check; no timers, threads or retained native objects. */
final class NativeDiscovery {
    private static volatile boolean available;
    static { try {System.loadLibrary("discovery");available=true;}catch(UnsatisfiedLinkError|SecurityException unavailable){available=false;} }
    /** app_process has no application native-library search path. Supplied by ApplicationInfo. */
    static synchronized void load(String library){if(!available&&library!=null)try{System.load(library);available=true;}catch(UnsatisfiedLinkError|SecurityException unavailable){/* Checked Java fallback. */}}
    static long[] modified(List<String> paths) {
        if(paths.size()>128)throw new IllegalArgumentException("Directory batch exceeds 128 paths.");
        if(available){byte[][] encoded=new byte[paths.size()][];
            for(int i=0;i<encoded.length;i++)encoded[i]=paths.get(i).getBytes(StandardCharsets.UTF_8);
            long[] result=stamps(encoded);if(result!=null)return result;}
        long[] result=new long[paths.size()];
        for(int i=0;i<result.length;i++)try{android.system.StructStat stat=android.system.Os.lstat(paths.get(i));
            result[i]=android.system.OsConstants.S_ISDIR(stat.st_mode)?modified(stat):-1;
        }catch(android.system.ErrnoException failure){result[i]=-failure.errno;}
        return result;
    }
    /** Match the JNI stamp precision even on older libcore NIO implementations. */
    static long modified(android.system.StructStat stat){return android.os.Build.VERSION.SDK_INT>=27?stat.st_mtim.tv_sec*1000+stat.st_mtim.tv_nsec/1000000:stat.st_mtime*1000;}
    static boolean availableForProbe(){return available;}
    private static native long[] stamps(byte[][] paths);
}
