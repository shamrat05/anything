package dev.fastsearch.core;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class CleanerRecommendationsTest {
    private static final long DAY=CleanerRecommendations.DAY_MS, NOW=1000*DAY;
    private static final String ROOT="/storage/emulated/0", SD="/storage/1234-aBCd";
    private static final String ADOPTED="/storage/01234567-89ab-cdef-0123-456789abcdef";
    private static final CleanerRules.Filter ALL=filter(0,null,null);
    private static int assertions;

    public static void main(String[] args)throws Exception{
        exactRules();
        protection();
        likelyJunk();
        planning();
        bounds();
        System.out.println("PASS "+assertions+" Cleaner review recommendation assertions.");
    }

    private static void exactRules(){
        expect("Download/example.APK",30,CleanerRecommendations.Rule.DOWNLOAD_INSTALLER,true);
        expect("Downloads/example.apk",29,CleanerRecommendations.Rule.RECENT_FILE,false);
        expect("Documents/example.apk",500,CleanerRecommendations.Rule.OUTSIDE_EXPECTED_FOLDER,false);
        expect("Download/installed/example.apk",500,CleanerRecommendations.Rule.OUTSIDE_EXPECTED_FOLDER,false);
        expect("Download/x.xapk",500,CleanerRecommendations.Rule.PACKAGE_BUNDLE,false);
        expect("Download/x.apks",500,CleanerRecommendations.Rule.PACKAGE_BUNDLE,false);
        expect("download/video.MP4.crdownload",7,CleanerRecommendations.Rule.DOWNLOAD_PARTIAL,true);
        expect("Downloads/video.opdownload",6,CleanerRecommendations.Rule.RECENT_FILE,false);
        expect("Downloads/transfer.part",45,CleanerRecommendations.Rule.DOWNLOAD_PARTIAL,true);
        expect("Downloads/transfer.download",45,CleanerRecommendations.Rule.DOWNLOAD_PARTIAL,true);
        expect("Documents/transfer.crdownload",45,CleanerRecommendations.Rule.OUTSIDE_EXPECTED_FOLDER,false);
        expect("Download/unfinished/transfer.part",45,CleanerRecommendations.Rule.OUTSIDE_EXPECTED_FOLDER,false);
        expect("Temp/print.tmp",14,CleanerRecommendations.Rule.SHARED_TEMP,true);
        expect("Download/Tmp/print.TEMP",90,CleanerRecommendations.Rule.SHARED_TEMP,true);
        expect("Downloads/Temporary/print.tmp",90,CleanerRecommendations.Rule.SHARED_TEMP,true);
        expect("Download/print.tmp",90,CleanerRecommendations.Rule.OUTSIDE_EXPECTED_FOLDER,false);
        expect("Documents/temp/print.tmp",90,CleanerRecommendations.Rule.OUTSIDE_EXPECTED_FOLDER,false);
        expect("Temp/print.tmp",2,CleanerRecommendations.Rule.RECENT_FILE,false);
        expect("Downloads/updating.apk",-1,CleanerRecommendations.Rule.UNKNOWN_AGE,false);
        CleanerRecommendations.Item missing=classify(new FileEntry(ROOT+"/Download/unknown.apk",false,100,-1),false);
        check(missing.rule==CleanerRecommendations.Rule.UNKNOWN_AGE,"missing age never suggests");
        for(String mount:new String[]{SD,ADOPTED})
            check(classify(new FileEntry(mount+"/Download/install.apk",false,80,NOW-42*DAY),false)
                    .rule==CleanerRecommendations.Rule.DOWNLOAD_INSTALLER,"relative location works on a direct external mount");
        check(CleanerRecommendations.Rule.DOWNLOAD_INSTALLER.id.equals("download.installer.old")
                &&CleanerRecommendations.Rule.DOWNLOAD_PARTIAL.reason.contains("7 days"),"stable ID and displayed rule evidence");
    }

    private static void likelyJunk(){
        for(String path:new String[]{"Download/pending.mp4.crdownload","Downloads/video.opdownload",
                "Temp/print.tmp","Download/Tmp/print.temp"}){
            CleanerRecommendations.Item item=classify(file(path,45,20),false);
            check(CleanerRecommendations.isLikelyJunk(item)&&item.suggestedLogicalBytes==20,
                    "old known-location browser or temp marker enters likely junk: "+path);
        }
        for(String path:new String[]{"Downloads/normal.apk","Downloads/research.part","Downloads/export.download",
                "Backup/archive.mp4.crdownload","Download/photo.jpg.bak.crdownload","Download/dataset.zip.crdownload",
                "Documents/clutter.tmp","Downloads/old.zip","DCIM/.thumbnails/.thumbdata1",
                "Documents/old.xls","WhatsApp/Media/photo.jpg"}){
            check(!CleanerRecommendations.isLikelyJunk(classify(file(path,100,1234567),false)),
                    "valuable or ambiguous file cannot count as default junk: "+path);
        }
        check(!CleanerRecommendations.isLikelyJunk(classify(file("Download/running.mp4.crdownload",1,80),false)),
                "recent partials stay out");
        check(!CleanerRecommendations.isLikelyJunk(classify(file("Download/active.mp4.crdownload",100,123),true)),
                "files known in use stay out");
        for(long modified:new long[]{0,-1,NOW+DAY}){
            CleanerRecommendations.Item uncertain=classify(new FileEntry(ROOT+"/Temp/time.tmp",false,4096,modified),false);
            check(uncertain!=null&&uncertain.rule==CleanerRecommendations.Rule.UNKNOWN_AGE
                    &&!CleanerRecommendations.isLikelyJunk(uncertain),"unreliable age never counts");
        }
        check(!CleanerRecommendations.isLikelyJunk(classify(file("Temp/empty.tmp",100,0),false)),"no claim for empty file");
    }

    private static void protection(){
        expect("Downloads/restore.apk",365,CleanerRecommendations.Rule.BACKUP_FILE,false);
        expect("Downloads/music.backup.apk",365,CleanerRecommendations.Rule.BACKUP_FILE,false);
        expect("Downloads/project (backup).apk",365,CleanerRecommendations.Rule.BACKUP_FILE,false);
        expect("Downloads/document.bak.crdownload",365,CleanerRecommendations.Rule.BACKUP_FILE,false);
        expect("Downloads/document.bak.tmp",365,CleanerRecommendations.Rule.BACKUP_FILE,false);
        expect("Backups/data.zip",100,CleanerRecommendations.Rule.BACKUP_FILE,false);
        expect("Downloads/bundle.zip",100,CleanerRecommendations.Rule.ARCHIVE_FILE,false);
        expect("Downloads/bundle.zip.part",100,CleanerRecommendations.Rule.ARCHIVE_FILE,false);
        expect("Temp/plans.zip.tmp",100,CleanerRecommendations.Rule.ARCHIVE_FILE,false);
        expect("WhatsApp/Media/Movie.mp4",500,CleanerRecommendations.Rule.MESSAGING_FILE,false);
        expect("Downloads/Telegram/clip.part",100,CleanerRecommendations.Rule.MESSAGING_FILE,false);
        expect("Android/media/com.example/tool.apk",100,CleanerRecommendations.Rule.MESSAGING_FILE,false);
        expect("Vault/example.apk",100,CleanerRecommendations.Rule.PROTECTED_LOCATION,false);
        expect("Originals/temp/note.tmp",100,CleanerRecommendations.Rule.PROTECTED_LOCATION,false);
        expect("DCIM/.thumbnails/.thumbdata1",100,CleanerRecommendations.Rule.METADATA_CLUE,false);
        CleanerRecommendations.Item active=classify(file("Download/live.apk",150,123),true);
        check(active.rule==CleanerRecommendations.Rule.ACTIVE_FILE&&active.suggestedLogicalBytes==0,"known active always review only");
        for(String path:new String[]{"Android/data/com.example/cache/large.tmp","Android/obb/com.example/large.apk",
                "Android/media/com.android.provider/secret.apk","Download/../shared/secret.apk","Download/.pending-01.apk",
                "Download/.trashed-01.apk",".private/Download/secret.apk","Download/.git/install.apk"})
            check(classify(file(path,200,123),false)==null,"no admission from " + path);
        for(String path:new String[]{"/data/media/0/Download/test.apk","/data/user/0/Download/test.apk","/proc/temp/a.tmp"})
            check(classify(new FileEntry(path,false,100,NOW-500*DAY),false)==null,"never map protected or virtual paths");
        check(classify(file("Download/ordinary.txt",600,800),false)==null,"not all old downloads are cleanup candidates");
        check(classify(file("Download/empty.apk",100,0),false)==null,"empty files do not imply reclaimed storage");
    }

    private static void planning()throws Exception{
        FileEntry apk=file("Download/a.apk",100,40);
        FileEntry partial=file("Download/z.bin.part",120,200);
        FileEntry backup=file("Backup/large.bak",300,1500);
        FileEntry active=file("Temp/active.tmp",300,600);
        FileEntry excluded=file("Downloads/excluded/subfolder/a.apk",100,2000);
        CleanerRules.Filter selected=new CleanerRules.Filter(Arrays.asList(ROOT,SD,ADOPTED),0,0,null,null,
                Collections.singletonList(ROOT+"/Downloads/excluded"));
        List<FileEntry> source=Arrays.asList(backup,apk,partial,partial,active,excluded);
        CleanerRecommendations.Plan plan=CleanerRecommendations.plan(source,NOW,selected,e->e==active,
                CleanerRecommendations.Sort.SUGGESTIONS_FIRST,10,()->false);
        check(plan.rowsExamined==6&&plan.items.size()==4&&!plan.partial,"exact path duplicates and exclusions do not reappear");
        check(plan.items.get(0).entry==partial&&plan.items.get(1).entry==apk,"positive suggestions first, larger one first");
        check(plan.suggestedLogicalBytes==240,"advisory logical bytes exclude backups and files in use");
        check(plan.selectForReview(Collections.emptyList()).items.isEmpty(),"no default selection");
        CleanerRecommendations.Selection pick=plan.selectForReview(Arrays.asList(backup.path(),apk.path(),apk.path(),excluded.path()));
        check(pick.items.size()==2&&pick.unmatchedPaths==1&&pick.suggestedLogicalBytes==40,
                "only exact explicit rows selected, protected review contributes zero");
        try{plan.items.clear();throw new AssertionError("plan mutated");}
        catch(UnsupportedOperationException expected){check(true,"immutable plan");}
        try{pick.items.clear();throw new AssertionError("selection mutated");}
        catch(UnsupportedOperationException expected){check(true,"immutable selection");}

        CleanerRecommendations.Plan oldest=CleanerRecommendations.plan(source,NOW,selected,null,
                CleanerRecommendations.Sort.OLDEST_FIRST,2,()->false);
        check(oldest.partial&&oldest.items.get(0).entry==backup,"display limit and oldest order");
        CleanerRecommendations.Plan byName=CleanerRecommendations.plan(source,NOW,selected,null,
                CleanerRecommendations.Sort.PATH,10,()->false);
        check(byName.items.get(0).entry==backup,"stable path order");
        CleanerRecommendations.Plan largest=CleanerRecommendations.plan(source,NOW,selected,null,
                CleanerRecommendations.Sort.LARGEST_FIRST,10,()->false);
        check(largest.items.get(0).entry==backup,"size order also exposes protected rows without suggesting them");

        FileEntry newer=file("Download/a.apk",1,40);
        CleanerRules.Filter oldOnly=filter(30*DAY,null,null);
        for(List<FileEntry> dir:Arrays.asList(Arrays.asList(apk,newer,partial),Arrays.asList(newer,apk,partial))){
            CleanerRecommendations.Plan conflict=CleanerRecommendations.plan(dir,NOW,oldOnly,null,
                    CleanerRecommendations.Sort.SUGGESTIONS_FIRST,10,()->false);
            check(conflict.partial&&conflict.metadataConflicts==1&&conflict.items.size()==1&&conflict.items.get(0).entry==partial,
                    "stale repeated paths are omitted irrespective of order or whether a filter hides the newer row");
        }
        check(CleanerRecommendations.classify(apk,NOW,filter(0,ROOT+"/Documents",null),false)==null,"scoped folder respected");
        check(CleanerRecommendations.classify(apk,NOW,filter(0,null,"pdf"),false)==null,"extension filter respected");
    }

    private static void bounds()throws Exception{
        List<FileEntry> huge=new ArrayList<>();
        for(int i=0;i<100_001;i++)huge.add(file("Download/old"+i+".apk",100,i+1));
        CleanerRecommendations.Plan capped=CleanerRecommendations.plan(huge,NOW,ALL,null,
                CleanerRecommendations.Sort.LARGEST_FIRST,3,()->false);
        check(capped.partial&&capped.rowsExamined==100_000&&capped.items.size()==3,"cold inventory bounded before return");
        check(capped.items.get(0).entry.size==100_000,"does not speculate about budget-excluded rows");
        AtomicInteger observed=new AtomicInteger();
        try{
            CleanerRecommendations.plan(huge,NOW,ALL,entry->{observed.incrementAndGet();return false;},
                    CleanerRecommendations.Sort.PATH,4,()->observed.get()>=100);
            throw new AssertionError("did not cancel");
        }catch(InterruptedIOException expected){check(observed.get()==100,"cooperative cancellation bounds the review scan");}
        CleanerRecommendations.Plan saturation=CleanerRecommendations.plan(Arrays.asList(
                file("Download/big.apk",100,Long.MAX_VALUE),file("Download/big2.apk",100,1)),NOW,ALL,null,
                CleanerRecommendations.Sort.PATH,5,()->false);
        check(saturation.suggestedLogicalBytes==Long.MAX_VALUE,"logical totals do not overflow");
    }

    private static CleanerRules.Filter filter(long age,String folder,String extensions){
        return new CleanerRules.Filter(Arrays.asList(ROOT,SD,ADOPTED),age,0,folder,extensions);
    }

    private static FileEntry file(String relative,long ageDays,long size){
        return new FileEntry(ROOT+"/"+relative,false,size,NOW-ageDays*DAY);
    }
    private static CleanerRecommendations.Item classify(FileEntry entry,boolean active){
        return CleanerRecommendations.classify(entry,NOW,ALL,active);
    }
    private static void expect(String path,long age,CleanerRecommendations.Rule rule,boolean suggestion){
        CleanerRecommendations.Item item=classify(file(path,age,100),false);
        check(item!=null&&item.rule==rule&&(item.disposition==CleanerRecommendations.Disposition.SUGGESTED_REVIEW)==suggestion
                &&item.suggestedLogicalBytes==(suggestion?100:0),"wrong rule for "+path+(item==null?" (none)":" ("+item.rule+")"));
    }
    private static void check(boolean good,String label){assertions++;if(!good)throw new AssertionError(label);}
}
