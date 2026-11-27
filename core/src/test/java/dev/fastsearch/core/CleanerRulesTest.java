package dev.fastsearch.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static dev.fastsearch.core.CleanerRules.Category.*;
import static dev.fastsearch.core.CleanerRules.Reason.*;

public final class CleanerRulesTest {
    private static final String STORAGE="/storage/emulated/0", REMOVABLE="/storage/12AF-4BE0";
    private static final long DAY=86400000L, NOW=20*DAY;
    private static final List<String> ROOTS=Arrays.asList(STORAGE,REMOVABLE);
    private static final CleanerRules.Filter REVIEW=new CleanerRules.Filter(ROOTS,7*DAY,16,null,null);
    private static int count;

    public static void main(String[] args){
        category(STORAGE+"/Download/old.TMP",TEMPORARY,TEMP_EXTENSION);
        category(STORAGE+"/Movies/camera.webm.crdownload",TEMPORARY,DOWNLOAD_MARKER);
        category(STORAGE+"/downloads/child/movie.MP4.part",TEMPORARY,DOWNLOAD_MARKER);
        category(STORAGE+"/DCIM/.thumbnails/.thumbdata3--7",CACHE,THUMBNAIL_CACHE);
        category(STORAGE+"/Pictures/.thumbnails/preview.png",CACHE,THUMBNAIL_CACHE);
        category(STORAGE+"/Download/Shared/Cache/entry.cache",CACHE,CACHE_EXTENSION);
        category(STORAGE+"/Documents/diff/File.java.rej",LEFTOVER,PATCH_REJECT);
        category(STORAGE+"/Download/diagnostics/vm.hprof",LEFTOVER,CRASH_REPORT);
        category(STORAGE+"/Documents/contracts/payroll.docx.BAK",BACKUP,BACKUP_EXTENSION);
        category(STORAGE+"/Documents/draft.txt~",BACKUP,BACKUP_EXTENSION);
        category(STORAGE+"/Download/app-config.swp",BACKUP,BACKUP_EXTENSION);
        category(STORAGE+"/Backups/November.tar.gz",BACKUP,BACKUP_ARCHIVE);
        category(STORAGE+"/project/Backup/November.zip",BACKUP,BACKUP_ARCHIVE);
        category(STORAGE+"/Download/holiday.tar.xz",ARCHIVE,ARCHIVE_EXTENSION);
        category(REMOVABLE+"/important.zip",ARCHIVE,ARCHIVE_EXTENSION);
        category(STORAGE+"/WhatsApp/Databases/msgstore-2026-11-01.1.db.crypt14",BACKUP,MESSAGING_BACKUP);
        category(STORAGE+"/Android/media/com.whatsapp.w4b/WhatsApp Business/Databases/msgstore.db.crypt15",BACKUP,MESSAGING_BACKUP);
        category(STORAGE+"/Android/Media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/2026/audio.opus",SOCIAL_MEDIA,WHATSAPP_MEDIA);
        category(STORAGE+"/WhatsApp Business/Media/WhatsApp Images/family.JPG",SOCIAL_MEDIA,WHATSAPP_MEDIA);
        category(STORAGE+"/Telegram/Telegram Videos/mov.webm",SOCIAL_MEDIA,TELEGRAM_MEDIA);
        category(STORAGE+"/Download/Telegram/movie.mp4",SOCIAL_MEDIA,TELEGRAM_MEDIA);
        category(REMOVABLE+"/Android/media/org.telegram.messenger/Telegram/Video/clip.mov",SOCIAL_MEDIA,TELEGRAM_MEDIA);

        String[] normal={
                "/DCIM/family.jpg","/Pictures/Cache/my-scan.pdf","/Download/game.apk",
                "/Download/comic.cbz","/Download/kit.jar","/Download/my cache.zip4",
                "/DCIM/.thumbnails/a.pdf","/Documents/microservice.hprof","/Download/clean.tmp.keep",
                "/Documents/song.part","/Downloading/song.part","/Documents/untitled.cache",
                "/WhatsApp/Databases/app.db.crypt14","/WhatsApp/Media/Folders/personal.csv",
                "/WhatsApp/MediaExtra/family.jpg","/Download/NotTelegram/clip.mp4",
                "/Telegrams/clip.mp4","/Download/.nomedia"
        };
        for(String path:normal)same(null,match(file(STORAGE+path),REVIEW),path);

        String[] unsafe={
                "/data/media/0/Download/a.tmp","/mnt/media_rw/12AF-4BE0/a.tmp","/storage/emulated/10/a.tmp",
                "/storage/emulated/01/a.tmp","/sdcard/Download/a.tmp","/system/a.tmp","/storage/self/primary/a.tmp",
                "/proc/self/a.tmp","relative/Download/a.tmp",
                STORAGE+"/ANDROID/DATA/app/a.tmp", STORAGE+"/Android/obb/app/a.tmp",
                STORAGE+"/Android/sandbox/app/a.tmp", STORAGE+"/Android/private/a.tmp",
                STORAGE+"/Android/Media-Private/company/a.tmp",
                STORAGE+"/Android/media/com.android.systemui/a.tmp",
                STORAGE+"/Android/media/com.google.android.providers.media.module/a.tmp",
                STORAGE+"/Android/media/android/a.tmp",
                STORAGE+"/.android_secure/a.tmp", STORAGE+"/DCIM/.trash/a.tmp",
                STORAGE+"/.Trash-1000/a.tmp", STORAGE+"/DCIM/.trashed-1234-a.tmp",
                STORAGE+"/DCIM/.pending-1234-a.tmp", STORAGE+"/Pictures/.recycle_bin/a.tmp",
                STORAGE+"/Recycle Bin/personal.tmp", STORAGE+"/Trash/restore.tmp",
                STORAGE+"/Documents/.private/a.tmp", STORAGE+"/.git/a.tmp",
                REMOVABLE+"/System Volume Information/a.tmp", REMOVABLE+"/$RECYCLE.BIN/a.tmp",
                REMOVABLE+"/lost+found/a.tmp", STORAGE+"/Download/../../escape.tmp",
                STORAGE+"/Download/./a.tmp", STORAGE+"//Download/a.tmp",
                STORAGE+"/Download/evil\\a.tmp", STORAGE+"/Download/evil\nbreak.tmp",
                STORAGE+"/Download/evil\u202ebreak.tmp"
        };
        CleanerRules.Filter broad=new CleanerRules.Filter(ROOTS,0,0,null,"tmp");
        for(String path:unsafe){
            same(null,match(file(path),broad),"reject classified " + path);
            check(!broad.accepts(file(path),NOW),"reject duplicate eligibility "+path);
        }
        same(null,match(new FileEntry(STORAGE+"/old.tmp",true,128,NOW-8*DAY),REVIEW),"directory");
        same(null,match(new FileEntry(STORAGE+"/old.tmp",false,-1,NOW-8*DAY),REVIEW),"unknown size");
        same(null,match(new FileEntry(STORAGE+"/old.tmp",false,15,NOW-8*DAY),REVIEW),"too small");
        same(null,match(new FileEntry(STORAGE+"/old.tmp",false,128,NOW-6*DAY),REVIEW),"too recent");
        same(null,match(new FileEntry(STORAGE+"/old.tmp",false,128,-1),REVIEW),"unknown age");
        same(null,match(new FileEntry(STORAGE+"/old.tmp",false,128,NOW+1),REVIEW),"future date");
        same(TEMPORARY,match(new FileEntry(STORAGE+"/old.tmp",false,16,NOW-7*DAY),REVIEW).category,"inclusive limits");
        same(null,CleanerRules.classify(file(STORAGE+"/old.tmp"),-1,REVIEW),"negative now");
        CleanerRules.Filter huge=new CleanerRules.Filter(ROOTS,Long.MAX_VALUE,0,null,null);
        same(null,match(file(STORAGE+"/old.tmp"),huge),"huge age no overflow");

        CleanerRules.Filter custom=new CleanerRules.Filter(ROOTS,DAY,100,STORAGE+"/Pictures/",
                " .jpg, PNG; tar.gz ",Arrays.asList(STORAGE+"/Pictures/Keep",STORAGE+"/Pictures/Keep/"));
        same(CUSTOM,match(file(STORAGE+"/Pictures/Family.jpg"),custom).category,"custom scoped extension");
        same(ARCHIVE,match(file(STORAGE+"/Pictures/packed.tar.gz"),custom).category,"custom conditions retain more specific category");
        same(CUSTOM,match(file(STORAGE+"/Pictures/KeepSafe/screenshot.PNG"),custom).category,"exclude exact folder boundary");
        same(null,match(file(STORAGE+"/Pictures/Keep/sub/image.png"),custom),"exclude folder descendants");
        same(null,match(file(STORAGE+"/Pictures/my.tar.xz"),custom),"whole compound extension");
        same(null,match(file(STORAGE+"/Pictures/archive.gz"),custom),"not final suffix of compound");
        same(null,match(file(STORAGE+"/Pictures/.jpg"),custom),"extension without base not a file type");
        same(null,match(file(STORAGE+"/Pictures/notes.txt"),custom),"extension condition mandatory");
        same(null,match(file(STORAGE+"/Pictures2/p.png"),custom),"include folder boundary");
        same(null,match(file(REMOVABLE+"/Pictures/p.png"),custom),"include volume boundary");
        same(null,match(file(STORAGE+"/Pictures/.nomedia"),custom),"protected control file");
        check(custom.accepts(file(STORAGE+"/Pictures/Family.jpg"),NOW),"duplicate eligibility uses same conditions");
        check(!custom.accepts(file(STORAGE+"/Pictures/Keep/Family.jpg"),NOW),"duplicate exclusions enforced");
        check(REVIEW.accepts(file(STORAGE+"/Documents/ordinary.pdf"),NOW),"duplicates can review uncategorized ordinary files");
        same(null,match(file(STORAGE+"/Documents/ordinary.pdf"),REVIEW),"ordinary file not a cleanup candidate");
        CleanerRules.Filter folder=new CleanerRules.Filter(ROOTS,0,0,STORAGE+"/Documents",null);
        same(CUSTOM,match(file(STORAGE+"/Documents/anything.pdf"),folder).category,"folder-only custom review");
        CleanerRules.Filter empty=new CleanerRules.Filter(Collections.emptyList(),0,0,null,null);
        same(null,match(file(STORAGE+"/x.tmp"),empty),"no trusted roots means no candidates");

        check(CleanerRules.isSharedVolumeRoot(STORAGE),"emulated");
        check(CleanerRules.isSharedVolumeRoot(REMOVABLE),"short UUID");
        check(CleanerRules.isSharedVolumeRoot("/storage/01234567-abcd-1234-5678-0123456789af"),"long UUID");
        for(String root:Arrays.asList("/","/storage/emulated","/storage/emulated/-1",STORAGE+"/Download",
                "/storage/emulated/0/","/storage/zzzz-zzzz","/storage/self/primary","/data/media/0")){
            check(!CleanerRules.isSharedVolumeRoot(root),"reject mount syntax "+root);
            invalid(()->new CleanerRules.Filter(Collections.singleton(root),0,0,null,null),"reject supplied root "+root);
        }
        for(String ext:Arrays.asList("jpg,", "jpg.*", "x/y", "../jpg","a..b","jpeg; ;png"))
            invalid(()->new CleanerRules.Filter(ROOTS,0,0,null,ext),"reject extension "+ext);
        invalid(()->new CleanerRules.Filter(ROOTS,-1,0,null,null),"reject negative age");
        invalid(()->new CleanerRules.Filter(ROOTS,0,-1,null,null),"reject negative size");
        invalid(()->new CleanerRules.Filter(ROOTS,0,0,STORAGE+"/Android/data",null),"reject protected include");
        invalid(()->new CleanerRules.Filter(ROOTS,0,0,STORAGE+"/Download/../Photos",null),"reject path traversal include");
        invalid(()->new CleanerRules.Filter(ROOTS,0,0,null,null,Collections.singleton("/system")),"reject private exclude");
        System.out.println("PASS "+count+" shared Cleaner rule assertions");
    }

    private static void category(String path,CleanerRules.Category category,CleanerRules.Reason reason){
        CleanerRules.Match result=match(file(path),REVIEW);
        check(result!=null,"classify "+path);
        same(category,result.category,path);
        same(reason,result.reason,path);
    }
    private static FileEntry file(String path){return new FileEntry(path,false,128,NOW-8*DAY);}
    private static CleanerRules.Match match(FileEntry entry,CleanerRules.Filter filter){
        return CleanerRules.classify(entry,NOW,filter);
    }
    private static void invalid(Runnable call,String label){
        try{call.run();}catch(IllegalArgumentException expected){check(true,label);return;}
        check(false,label);
    }
    private static void same(Object expected,Object got,String label){
        check(expected==got,label+" expected "+expected+" got "+got);
    }
    private static void check(boolean passed,String label){count++;if(!passed)throw new AssertionError(label);}
}
