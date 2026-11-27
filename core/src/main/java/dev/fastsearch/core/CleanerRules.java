package dev.fastsearch.core;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies shared-storage metadata for review, not deletion. A label is only a filename or
 * path clue; it does not prove which app wrote the file, that it is unused, or that it is safe
 * to remove. It never walks a filesystem, resolves symlinks or invokes privileged access.
 */
public final class CleanerRules {
    private static final int MAX_FILTER_VALUES=32, MAX_PATH=4096, MAX_EXTENSION=32;

    private CleanerRules(){}

    public enum Category {
        TEMPORARY("Temporary files"), CACHE("Shared cache clues"), LEFTOVER("Leftover clues"),
        BACKUP("Backups"), ARCHIVE("Archives"), SOCIAL_MEDIA("Shared messaging media"),
        CUSTOM("Custom review");

        public final String label;
        Category(String label){this.label=label;}
    }

    public enum Reason {
        TEMP_EXTENSION(Category.TEMPORARY,"Temporary-file extension."),
        DOWNLOAD_MARKER(Category.TEMPORARY,"Filename has a partial-download marker."),
        THUMBNAIL_CACHE(Category.CACHE,"Thumbnail data in a public thumbnail folder."),
        CACHE_EXTENSION(Category.CACHE,"Cache extension in a shared cache folder."),
        PATCH_REJECT(Category.LEFTOVER,"Filename has a rejected-patch marker; it may contain changes."),
        CRASH_REPORT(Category.LEFTOVER,"Crash report in a shared diagnostics folder."),
        BACKUP_EXTENSION(Category.BACKUP,"Backup or recovery filename marker; it may hold an original copy."),
        BACKUP_ARCHIVE(Category.BACKUP,"Archive in a Backups folder; it may hold an original copy."),
        MESSAGING_BACKUP(Category.BACKUP,"WhatsApp database backup filename in a shared backup folder."),
        ARCHIVE_EXTENSION(Category.ARCHIVE,"Archive filename extension; it may contain important files."),
        WHATSAPP_MEDIA(Category.SOCIAL_MEDIA,"Media filename in a shared WhatsApp folder."),
        TELEGRAM_MEDIA(Category.SOCIAL_MEDIA,"Media filename in a shared Telegram folder."),
        CUSTOM_FILTER(Category.CUSTOM,"Matches your folder or extension filter.");

        public final Category category;
        public final String label;
        private final Match match;
        Reason(Category category,String label){
            this.category=category;
            this.label=label;
            match=new Match(this);
        }
    }

    public static final class Match {
        public final Category category;
        public final Reason reason;
        private Match(Reason reason){category=reason.category;this.reason=reason;}
    }

    /** Snapshot of app-discovered public mount roots and user review conditions. */
    public static final class Filter {
        private final String[] roots, extensions, excluded;
        private final String folder;
        private final long minimumAgeMillis, minimumBytes;

        public Filter(Collection<String> sharedRoots,long minimumAgeMillis,long minimumBytes,
                      String folder,String extensions){
            this(sharedRoots,minimumAgeMillis,minimumBytes,folder,extensions,null);
        }

        public Filter(Collection<String> sharedRoots,long minimumAgeMillis,long minimumBytes,
                      String folder,String extensions,Collection<String> excludedFolders){
            if(sharedRoots==null||sharedRoots.size()>MAX_FILTER_VALUES
                    ||minimumAgeMillis<0||minimumBytes<0)
                throw new IllegalArgumentException("Invalid Cleaner scope or limits.");
            Set<String> mountRoots=new LinkedHashSet<>();
            for(String root:sharedRoots){
                if(!isSharedVolumeRoot(root))
                    throw new IllegalArgumentException("Cleaner requires a public Android volume root.");
                mountRoots.add(root);
            }
            roots=mountRoots.toArray(new String[0]);
            this.minimumAgeMillis=minimumAgeMillis;
            this.minimumBytes=minimumBytes;
            this.folder=filterFolder(folder,false);
            this.extensions=extensionSet(extensions);
            if(excludedFolders!=null&&excludedFolders.size()>MAX_FILTER_VALUES)
                throw new IllegalArgumentException("Too many excluded Cleaner folders.");
            Set<String> exclude=new LinkedHashSet<>();
            if(excludedFolders!=null)for(String path:excludedFolders)exclude.add(filterFolder(path,true));
            excluded=exclude.toArray(new String[0]);
        }

        /** Applies scope and user conditions independently of review categories (e.g. duplicates). */
        public boolean accepts(FileEntry entry,long nowMillis){return acceptedStart(entry,nowMillis,this)>=0;}

        private String filterFolder(String raw,boolean required){
            if(raw==null||raw.trim().isEmpty()){
                if(required)throw new IllegalArgumentException("Empty Cleaner exclusion.");
                return null;
            }
            int end=raw.length();
            while(end>1&&raw.charAt(end-1)=='/')end--;
            String value=end==raw.length()?raw:raw.substring(0,end);
            int relative=relativeToRoot(value,roots);
            if(relative<0||!safeRelativePath(value,relative))
                throw new IllegalArgumentException("Cleaner folders must be in public shared storage.");
            return value;
        }
    }

    /** Only syntax; callers still have to discover and verify the mounted, accessible volumes. */
    public static boolean isSharedVolumeRoot(String path){
        if(path==null||!path.startsWith("/storage/"))return false;
        int start="/storage/".length();
        if(path.startsWith("emulated/",start)){
            start+="emulated/".length();
            return path.length()>start&&path.length()-start<=10&&digits(path,start,path.length());
        }
        int length=path.length()-start;
        if(length!=9&&length!=36)return false;
        for(int i=0;i<length;i++){
            char c=path.charAt(start+i);
            boolean dash=length==9?i==4:i==8||i==13||i==18||i==23;
            if(dash?c!='-':!hexDigit(c))return false;
        }
        return true;
    }

    /**
     * Returns one most specific review group, or null. Unknown sizes and unknown dates with an age
     * condition do not qualify. The same Filter is safe to reuse across records and threads.
     */
    public static Match classify(FileEntry entry,long nowMillis,Filter filter){
        int start=acceptedStart(entry,nowMillis,filter);
        if(start<0)return null;
        String parent=entry.parent, folded=entry.foldedName;

        boolean archive=suffix(folded,ARCHIVES);
        if(folded.startsWith("msgstore")&&suffix(folded,WHATSAPP_BACKUPS)
                &&whatsappDatabase(parent,start))return Reason.MESSAGING_BACKUP.match;
        if(suffix(folded,BACKUPS)||(folded.length()>1&&folded.endsWith("~")))
            return Reason.BACKUP_EXTENSION.match;
        if(archive&&folderSegment(parent,start,"backup","backups"))
            return Reason.BACKUP_ARCHIVE.match;
        if(archive)return Reason.ARCHIVE_EXTENSION.match;

        if(thumbnailFolder(parent,start)&&(folded.startsWith(".thumbdata")
                ||suffix(folded,THUMBNAILS)))return Reason.THUMBNAIL_CACHE.match;
        if(suffix(folded,CACHE_FILES)&&folderSegment(parent,start,"cache",".cache"))
            return Reason.CACHE_EXTENSION.match;
        if(suffix(folded,TEMP_EXTENSIONS))return Reason.TEMP_EXTENSION.match;
        if(suffix(folded,DOWNLOAD_MARKERS)||downloadFolder(parent,start)&&suffix(folded,WEAK_DOWNLOAD_MARKERS))
            return Reason.DOWNLOAD_MARKER.match;
        if(suffix(folded,PATCH_REJECTS))return Reason.PATCH_REJECT.match;
        if(suffix(folded,CRASH_REPORTS)&&folderSegment(parent,start,"crash","crashes","crashlogs","diagnostics"))
            return Reason.CRASH_REPORT.match;

        if(suffix(folded,SOCIAL_SUFFIXES)){
            if(whatsappMedia(parent,start))return Reason.WHATSAPP_MEDIA.match;
            if(telegramMedia(parent,start))return Reason.TELEGRAM_MEDIA.match;
        }
        if(filter.folder!=null||filter.extensions.length!=0)return Reason.CUSTOM_FILTER.match;
        return null;
    }

    private static int acceptedStart(FileEntry entry,long nowMillis,Filter filter){
        if(entry==null||filter==null||entry.directory||entry.size<filter.minimumBytes||nowMillis<0)
            return -1;
        if(filter.minimumAgeMillis>0&&(entry.modified<0||entry.modified>nowMillis
                ||nowMillis-entry.modified<filter.minimumAgeMillis))return -1;
        String parent=entry.parent, name=entry.name, folded=entry.foldedName;
        int start=relativeToRoot(parent,filter.roots);
        if(start<0||!safeRelativePath(parent,start)||!validSegment(name,0,name.length())
                ||protectedSegment(name,0,name.length()))return -1;
        if(filter.folder!=null&&!inside(parent,filter.folder))return -1;
        for(String excluded:filter.excluded)if(inside(parent,excluded))return -1;
        if(filter.extensions.length!=0&&!suffix(folded,filter.extensions))return -1;
        if(folded.equals(".nomedia")||folded.startsWith(".pending-")||folded.startsWith(".trashed-"))
            return -1;

        return start;
    }

    private static final String[] TEMP_EXTENSIONS={".tmp",".temp"};
    private static final String[] DOWNLOAD_MARKERS={".crdownload",".opdownload"};
    private static final String[] WEAK_DOWNLOAD_MARKERS={".part",".download"};
    private static final String[] CACHE_FILES={".cache"};
    private static final String[] PATCH_REJECTS={".rej"};
    private static final String[] CRASH_REPORTS={".dmp",".hprof",".stacktrace"};
    private static final String[] BACKUPS={".bak",".backup",".old",".orig",".swp",".swo",".swx"};
    private static final String[] ARCHIVES={
        ".zip",".rar",".7z",".tar",".gz",".tgz",".bz2",".tbz",".tbz2",".xz",".txz",".zst",".tzst"
    };
    private static final java.util.regex.Pattern BACKUP_NAME=java.util.regex.Pattern.compile(
            "(?:^|[_. -])(?:backup|backups|recovery|restore)(?=$|[_. -])");
    static boolean hasBackupFilename(String foldedName){
        return suffix(foldedName,BACKUPS)||(foldedName.length()>1&&foldedName.endsWith("~"))
                ||foldedName.startsWith("msgstore")&&suffix(foldedName,WHATSAPP_BACKUPS)
                ||BACKUP_NAME.matcher(foldedName).find();
    }
    private static final String[] THUMBNAILS={".jpg",".jpeg",".png",".webp",".thumb",".thumbdata"};
    private static final String[] WHATSAPP_BACKUPS={
        ".db.crypt5",".db.crypt7",".db.crypt8",".db.crypt10",".db.crypt12",".db.crypt14",".db.crypt15"
    };
    private static final String[] SOCIAL_SUFFIXES={
        ".jpg",".jpeg",".png",".gif",".webp",".heic",".heif",".avif",".bmp",
        ".mp4",".mkv",".webm",".mov",".avi",".m4v",".3gp",
        ".mp3",".aac",".amr",".m4a",".opus",".ogg",".flac",".wav"
    };

    private static boolean thumbnailFolder(String path,int start){
        return prefix(path,start,"dcim/.thumbnails")||prefix(path,start,"pictures/.thumbnails");
    }
    private static boolean downloadFolder(String path,int start){
        return prefix(path,start,"download")||prefix(path,start,"downloads");
    }
    private static boolean whatsappMedia(String path,int start){
        return prefix(path,start,"whatsapp/media")||prefix(path,start,"whatsapp business/media")
                ||prefix(path,start,"android/media/com.whatsapp/whatsapp/media")
                ||prefix(path,start,"android/media/com.whatsapp.w4b/whatsapp business/media");
    }
    private static boolean whatsappDatabase(String path,int start){
        return prefix(path,start,"whatsapp/databases")||prefix(path,start,"whatsapp business/databases")
                ||prefix(path,start,"android/media/com.whatsapp/whatsapp/databases")
                ||prefix(path,start,"android/media/com.whatsapp.w4b/whatsapp business/databases");
    }
    private static boolean telegramMedia(String path,int start){
        return prefix(path,start,"telegram")||prefix(path,start,"download/telegram")
                ||prefix(path,start,"pictures/telegram")||prefix(path,start,"movies/telegram")
                ||prefix(path,start,"android/media/org.telegram.messenger/telegram")
                ||prefix(path,start,"android/media/org.telegram.messenger.web/telegram");
    }

    private static boolean inside(String path,String parent){
        int end=parent.length();
        return path.startsWith(parent)&&(path.length()==end||path.charAt(end)=='/');
    }
    private static int relativeToRoot(String path,String[] roots){
        if(path.length()>MAX_PATH)return -1;
        for(String root:roots)
            if(inside(path,root))return Math.min(path.length(),root.length()+1);
        return -1;
    }

    private static boolean safeRelativePath(String path,int start){
        if(start==path.length())return path.charAt(path.length()-1)!='/';
        if(path.charAt(path.length()-1)=='/')return false;
        // Android/media is shared by platform design. All other Android subtrees are excluded.
        if(prefix(path,start,"android")){
            if(!prefix(path,start,"android/media"))return false;
            int firstPackage=start+"android/media/".length();
            if(firstPackage<path.length()){
                int end=path.indexOf('/',firstPackage);
                if(end<0)end=path.length();
                int n=end-firstPackage;
                if(equals(path,firstPackage,n,"android")
                        ||equals(path,firstPackage,n,"com.google.android.providers.media.module")
                        ||(n>12&&path.regionMatches(true,firstPackage,"com.android.",0,12)))return false;
            }
        }
        int length=path.length();
        for(int from=start;from<length;){
            int to=path.indexOf('/',from);
            if(to<0)to=length;
            if(!validSegment(path,from,to)||protectedSegment(path,from,to))return false;
            from=to+1;
        }
        return true;
    }

    private static boolean validSegment(String path,int start,int end){
        int length=end-start;
        if(length==0||length>255||dot(path,start,length))return false;
        for(int i=start;i<end;i++){
            char c=path.charAt(i);
            if(c=='/'||c=='\\'||Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)return false;
        }
        return true;
    }
    private static boolean dot(String path,int start,int length){
        return length<=2&&path.charAt(start)=='.'&&(length==1||path.charAt(start+1)=='.');
    }
    private static boolean protectedSegment(String path,int start,int end){
        int n=end-start;
        return equals(path,start,n,".android_secure")||equals(path,start,n,".android-secure")
                ||equals(path,start,n,".trash")||equals(path,start,n,".trashes")
                ||equals(path,start,n,".recycle")||equals(path,start,n,".recyclebin")
                ||equals(path,start,n,".recycle_bin")||equals(path,start,n,"$recycle.bin")
                ||equals(path,start,n,"trash")||equals(path,start,n,"recycle bin")
                ||equals(path,start,n,"recyclebin")||equals(path,start,n,"recycle_bin")
                ||equals(path,start,n,"lost+found")||equals(path,start,n,"system volume information")
                ||equals(path,start,n,".private")||equals(path,start,n,".secure")
                ||equals(path,start,n,".git")||equals(path,start,n,".svn")||equals(path,start,n,".hg")
                ||(n>7&&path.regionMatches(true,start,".trash-",0,7))
                ||(n>9&&path.regionMatches(true,start,".trashed-",0,9));
    }

    private static boolean folderSegment(String path,int start,String... tokens){
        int length=path.length();
        for(int from=start;from<length;){
            int to=path.indexOf('/',from);
            if(to<0)to=length;
            for(String token:tokens)if(equals(path,from,to-from,token))return true;
            from=to+1;
        }
        return false;
    }
    private static boolean prefix(String path,int start,String prefix){
        int end=start+prefix.length();
        return end<=path.length()&&path.regionMatches(true,start,prefix,0,prefix.length())
                &&(end==path.length()||path.charAt(end)=='/');
    }
    private static boolean equals(String path,int start,int length,String label){
        return label.length()==length&&path.regionMatches(true,start,label,0,length);
    }
    private static boolean suffix(String filename,String[] suffixes){
        for(String suffix:suffixes)if(filename.length()>suffix.length()&&filename.endsWith(suffix))return true;
        return false;
    }

    private static String[] extensionSet(String raw){
        if(raw==null||raw.trim().isEmpty())return new String[0];
        if(raw.length()>(MAX_EXTENSION+2)*MAX_FILTER_VALUES)
            throw new IllegalArgumentException("Too many Cleaner extensions.");
        String[] parts=raw.split("[,;]",-1);
        if(parts.length>MAX_FILTER_VALUES)throw new IllegalArgumentException("Too many Cleaner extensions.");
        Set<String> normalized=new LinkedHashSet<>();
        for(String part:parts){
            String value=part.trim().toLowerCase(Locale.ROOT);
            if(value.startsWith("."))value=value.substring(1);
            if(value.isEmpty()||value.length()>MAX_EXTENSION||value.startsWith(".")||value.endsWith(".")||value.contains(".."))
                throw new IllegalArgumentException("Enter extensions like jpg, png or tar.gz.");
            for(int i=0;i<value.length();i++){
                char c=value.charAt(i);
                if(!(c>='a'&&c<='z'||c>='0'&&c<='9'||c=='.'||c=='_'||c=='-'||c=='+'))
                    throw new IllegalArgumentException("Enter extensions like jpg, png or tar.gz.");
            }
            normalized.add("."+value);
        }
        return normalized.toArray(new String[0]);
    }
    private static boolean digits(String path,int from,int to){
        for(int i=from;i<to;i++)if(path.charAt(i)<'0'||path.charAt(i)>'9')return false;
        return true;
    }
    private static boolean hexDigit(char c){
        return c>='0'&&c<='9'||c>='a'&&c<='f'||c>='A'&&c<='F';
    }
}
