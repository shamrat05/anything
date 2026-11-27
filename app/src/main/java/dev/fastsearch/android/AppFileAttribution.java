package dev.fastsearch.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Only recognized Android storage namespaces; never infer an app from a basename. */
final class AppFileAttribution {
    static final int MAX_PATH=4096;
    private static final int MAX_PACKAGE=255;
    private final List<String> sharedRoots;
    private final String primary, profile, backing;
    private final String credential, device;

    AppFileAttribution(List<String> sharedRoots,String primary,String profile) {
        this.sharedRoots=Collections.unmodifiableList(new ArrayList<>(sharedRoots));
        this.primary=primary;
        this.profile=validProfile(profile)?profile:null;
        backing=this.profile==null||primary==null?null:"/data/media/"+profile;
        credential=this.profile==null?null:"/data/user/"+profile+"/";
        device=this.profile==null?null:"/data/user_de/"+profile+"/";
    }

    /** The app's private data path identifies the current Android profile without hidden APIs. */
    static String currentProfile(String path) {
        if(path==null)return null;
        if(path.startsWith("/data/data/"))return "0";
        int first=path.startsWith("/data/user/")?11:path.startsWith("/data/user_de/")?14:-1;
        if(first<0)return null;
        int end=path.indexOf('/',first);
        if(end<0)return null;
        String id=path.substring(first,end);
        return validProfile(id)?id:null;
    }

    String pathPackage(String path,boolean directory,boolean root){
        if(!directPath(path))return null;
        if(root){
            String prefix=starts(path,credential)?credential:starts(path,device)?device
                    :"0".equals(profile)&&path.startsWith("/data/data/")?"/data/data/":null;
            if(prefix!=null)return packageAt(path,prefix.length(),directory);
            if(below(path,backing))return sharedPackage(path,backing.length(),directory);
        }
        for(String volume:sharedRoots)if(below(path,volume))
            return sharedPackage(path,volume.length(),directory);
        return null;
    }

    /** Maps Android physical/canonical storage aliases only for the same profile. */
    String mediaPath(String path,boolean root){
        if(!directPath(path))return null;
        if(root&&below(path,backing))return primary+path.substring(backing.length());
        for(String volume:sharedRoots)if(below(path,volume))return path;
        return null;
    }

    private static String sharedPackage(String path,int volumeEnd,boolean directory){
        int from=volumeEnd;
        if(path.startsWith("/Android/data/",from))from+=14;
        else if(path.startsWith("/Android/obb/",from))from+=13;
        else if(path.startsWith("/Android/media/",from))from+=15;
        else return null;
        return packageAt(path,from,directory);
    }

    private static String packageAt(String path,int offset,boolean directory){
        int end=path.indexOf('/',offset);
        if(end<0){if(!directory)return null;end=path.length();}
        if(end-offset>MAX_PACKAGE)return null;
        String candidate=path.substring(offset,end);
        return validPackage(candidate)?candidate:null;
    }

    static boolean validPackage(String value){
        if(value==null||value.length()<3||value.length()>MAX_PACKAGE)return false;
        boolean letterNeeded=true, hasDot=false;
        for(int i=0;i<value.length();i++){
            char ch=value.charAt(i);
            boolean letter=ch>='a'&&ch<='z'||ch>='A'&&ch<='Z';
            if(letterNeeded){if(!letter)return false;letterNeeded=false;}
            else if(ch=='.'){hasDot=true;letterNeeded=true;}
            else if(!(letter||ch=='_'||ch>='0'&&ch<='9'))return false;
        }
        return hasDot&&!letterNeeded;
    }

    private static boolean validProfile(String value){
        if(value==null||value.isEmpty()||value.length()>9||value.length()>1&&value.charAt(0)=='0')return false;
        for(int i=0;i<value.length();i++)if(value.charAt(i)<'0'||value.charAt(i)>'9')return false;
        return true;
    }
    private static boolean directPath(String path){
        if(path==null||path.length()<2||path.length()>MAX_PATH||path.charAt(0)!='/'||path.endsWith("/")
                ||path.indexOf('\0')>=0||path.indexOf("//")>=0||path.indexOf("/./")>=0||path.indexOf("/../")>=0
                ||path.endsWith("/.")||path.endsWith("/.."))return false;
        return true;
    }
    private static boolean starts(String path,String prefix){return prefix!=null&&path.startsWith(prefix);}
    private static boolean below(String path,String parent){
        return parent!=null&&path.length()>parent.length()&&path.startsWith(parent)&&path.charAt(parent.length())=='/';
    }
}
