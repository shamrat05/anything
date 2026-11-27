package dev.fastsearch.android;

import java.util.Arrays;
import java.util.Objects;

public final class AppFileAttributionTest {
    private static int checks;
    private static final String INTERNAL="/storage/emulated/0", REMOVABLE="/storage/1A2B-3C4D";
    private static final String APP="com.example.owner";
    public static void main(String[] args){
        AppFileAttribution paths=new AppFileAttribution(Arrays.asList(INTERNAL,REMOVABLE),INTERNAL,"0");
        for(String scope:new String[]{"data","obb","media"}) {
            check(APP,paths.pathPackage(INTERNAL+"/Android/"+scope+"/"+APP,true,false),"package folder "+scope);
            check(APP,paths.pathPackage(INTERNAL+"/Android/"+scope+"/"+APP+"/nested/payload.dat",false,false),"nested "+scope);
        }
        check(APP,paths.pathPackage(REMOVABLE+"/Android/media/"+APP,true,false),"OS-reported removable volume");
        check(APP,paths.pathPackage("/data/user/0/"+APP+"/files/counter",false,true),"root credential-protected namespace");
        check(APP,paths.pathPackage("/data/user_de/0/"+APP,true,true),"root device-protected namespace");
        check(APP,paths.pathPackage("/data/data/"+APP+"/files/x",false,true),"owner root alias");
        check(APP,paths.pathPackage("/data/media/0/Android/data/"+APP,true,true),"root shared physical alias");
        for(String wrong:new String[]{INTERNAL+"/Download/"+APP, INTERNAL+"/DCIM/com.example.owner.jpg",
                INTERNAL+"/Download/Android/data/"+APP, INTERNAL+"/android/data/"+APP,
                "/storage/NOT-MOUNTED/Android/data/"+APP, "/storage/emulated/01/Android/data/"+APP,
                INTERNAL+"/Android/data/settings",INTERNAL+"/Android/data/../"+APP,
                INTERNAL+"/Android/data/"+APP+"/../other",INTERNAL+"/Android/data//"+APP,
                "/data/user/10/"+APP, "/data/user_de/1/"+APP,"/data/user/00/"+APP,
                "/data/media/10/Android/data/"+APP,"/data/local/tmp/"+APP}){
            check(null,paths.pathPackage(wrong,true,true),"no misleading path "+wrong);
        }
        check(null,paths.pathPackage(INTERNAL+"/Android/media/"+APP,false,false),"loose regular file not package directory");
        check(null,paths.pathPackage("/data/data/"+APP,true,false),"shared mode does not infer privileged data");
        check(null,paths.pathPackage("/data/media/0/Android/data/"+APP,true,false),"root physical alias requires Root mode");
        check(INTERNAL+"/Download/photo.jpg",paths.mediaPath(INTERNAL+"/Download/photo.jpg",false),"exact canonical media path");
        check(INTERNAL+"/Download/photo.jpg",paths.mediaPath("/data/media/0/Download/photo.jpg",true),"same-profile root media alias");
        check(null,paths.mediaPath("/data/media/0/Download/photo.jpg",false),"shared mode physical media rejected");
        check(null,paths.mediaPath("/data/media/10/Download/photo.jpg",true),"other-profile media hidden");
        check(null,paths.mediaPath("/tmp/storage/DCIM/fake.jpg",true),"not a known storage volume");
        check(null,paths.mediaPath(INTERNAL+"/Download/../test.jpg",true),"malformed media path");
        check(null,paths.mediaPath(INTERNAL+"/Download/\0evil.jpg",true),"NUL in path");
        check("0",AppFileAttribution.currentProfile("/data/data/dev.fastsearch.debug"),"owner profile data alias");
        check("0",AppFileAttribution.currentProfile("/data/user/0/dev.fastsearch.debug"),"owner profile storage");
        check("10",AppFileAttribution.currentProfile("/data/user_de/10/dev.fastsearch.debug"),"secondary Android profile");
        check(null,AppFileAttribution.currentProfile("/tmp/data/user_de/10/dev.fastsearch.debug"),"unknown profile");
        AppFileAttribution other=new AppFileAttribution(Arrays.asList("/storage/emulated/10"),"/storage/emulated/10","10");
        check(APP,other.pathPackage("/data/user_de/10/"+APP,true,true),"same work profile");
        check(null,other.pathPackage("/data/data/"+APP,true,true),"work profile cannot infer owner profile install");
        for(String invalid:new String[]{"a", "abcd", "1com.app", "com.1app", "com..app", "com.app.", "com.app$",
                "com.-app", "com.app/name", "com.äpp", "com.app\n"})check(false,AppFileAttribution.validPackage(invalid),"invalid package "+invalid);
        check(true,AppFileAttribution.validPackage("com.app_owner2"),"valid package");
        System.out.println("AppFileAttributionTest: "+checks+" checks passed");
    }
    private static void check(Object expected,Object actual,String name){
        if(!Objects.equals(expected,actual))throw new AssertionError(name+": expected "+expected+", got "+actual);
        checks++;
    }
}
