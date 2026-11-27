package dev.fastsearch.android;

import dev.fastsearch.core.CleanerRules;
import dev.fastsearch.core.CleanerRecommendations;
import dev.fastsearch.core.DuplicateFinder;
import dev.fastsearch.core.FileEntry;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

/** Local review adapter. Its only content reads are explicitly requested exact comparisons. */
final class CleanerReviewScanner implements CleanerSheet.Scanner {
    private static final int MAX_ROWS=5000;
    private static final int MAX_DUPLICATE_PATH_CHARS=8*1024*1024;
    private static final Comparator<CleanerSheet.Row> INTEREST=Comparator
            .comparing((CleanerSheet.Row row)->!row.suggestedReview)
            .thenComparing(Comparator.comparingLong((CleanerSheet.Row row)->row.entry.size).reversed())
            .thenComparing((a,b)->a.entry.comparePath(b.entry));
    static final CleanerReviewScanner INSTANCE=new CleanerReviewScanner();

    @Override public CleanerSheet.Result scan(CleanerSheet.Request request,BooleanSupplier cancelled,
                                              CleanerSheet.Progress progress)throws IOException {
        String selected=CleanerSheet.normalize(request.scope);
        if(request.sharedRoots.isEmpty()||request.scope!=null
                &&(!request.scope.equals(selected)||!CleanerSheet.allowedScope(request.sharedRoots,selected)))
            throw new IOException("Choose an accessible shared storage folder.");
        CleanerRules.Filter filter=new CleanerRules.Filter(request.sharedRoots,request.minimumAgeMillis,
                request.minimumBytes,selected,request.extensions,request.excludedFolders);
        long now=System.currentTimeMillis();
        if(request.mode==CleanerSheet.Mode.DUPLICATES){
            ArrayList<FileEntry> candidates=new ArrayList<>();
            int[] characters={0};
            boolean[] clipped={false};
            WalkTotals visited=walk(request,cancelled,progress,entry->{
                if(!filter.accepts(entry,now))return;
                int retained=entry.parent.length()+2*entry.name.length();
                if(candidates.size()>=DuplicateFinder.Limits.DEFAULT.maxRows
                        ||retained>MAX_DUPLICATE_PATH_CHARS-characters[0]){clipped[0]=true;return;}
                characters[0]+=retained;
                candidates.add(entry);
            });
            CleanerScopedWalk.checkpoint(cancelled);
            visited.limited|=clipped[0];
            progress.changed("Comparing file contents",visited.visited);
            return duplicates(CleanerDuplicates.scan(candidates,cancelled),visited,cancelled);
        }

        PriorityQueue<CleanerSheet.Row> best=new PriorityQueue<>(64,INTEREST.reversed());
        long[] matched={0};
        WalkTotals visited=walk(request,cancelled,progress,entry->{
            CleanerRules.Match match=CleanerRules.classify(entry,now,filter);
            CleanerRecommendations.Item recommendation=CleanerRecommendations.classify(entry,now,filter,false);
            boolean junk=CleanerRecommendations.isLikelyJunk(recommendation);
            if(request.mode==CleanerSheet.Mode.TEMP_CACHE&&!junk)return;
            String installerName=request.mode==CleanerSheet.Mode.DOWNLOADS&&recommendation!=null
                    ?entry.name.toLowerCase(java.util.Locale.ROOT):"";
            boolean downloadInstaller=(installerName.endsWith(".apk")||installerName.endsWith(".apks")
                        ||installerName.endsWith(".apkm")||installerName.endsWith(".xapk"))
                    &&request.sharedRoots.stream().anyMatch(root->entry.parent.equalsIgnoreCase(root+"/Download")
                        ||entry.parent.equalsIgnoreCase(root+"/Downloads"));
            if(request.mode!=CleanerSheet.Mode.TEMP_CACHE&&!downloadInstaller&&(match==null||!matches(request.mode,match)))return;
            matched[0]++;
            CleanerSheet.Row row=new CleanerSheet.Row(entry,recommendation==null?match.reason.label:recommendation.reason,
                    request.mode==CleanerSheet.Mode.TEMP_CACHE&&junk);
            if(best.size()<MAX_ROWS)best.add(row);
            else if(INTEREST.compare(row,best.peek())<0){best.poll();best.add(row);}
        });
        CleanerScopedWalk.checkpoint(cancelled);
        ArrayList<CleanerSheet.Row> rows=new ArrayList<>(best);rows.sort(INTEREST);
        return new CleanerSheet.Result(rows,visited.visited,visited.skipped,visited.limited||matched[0]>MAX_ROWS);
    }

    static final class WalkTotals {
        long visited,skipped;
        boolean limited;
    }

    private static WalkTotals walk(CleanerSheet.Request request,BooleanSupplier cancelled,
                                   CleanerSheet.Progress progress,java.util.function.Consumer<FileEntry> consumer)
            throws IOException{
        WalkTotals total=new WalkTotals();
        boolean readable=false;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
        List<String> scopes=request.scope==null?request.sharedRoots:java.util.Collections.singletonList(request.scope);
        for(String scope:scopes){
            CleanerScopedWalk.checkpoint(cancelled);
            int remaining=CleanerScopedWalk.MAX_NODES-(int)total.visited;
            if(remaining<=0||System.nanoTime()-deadline>=0){total.limited=true;break;}
            try{
                CleanerScopedWalk volume=CleanerScopedWalk.scan(Paths.get(scope),remaining,deadline,path->{
                    if(!CleanerSheet.allowedScope(request.sharedRoots,path))return false;
                    for(String excluded:request.excludedFolders)
                        if(path.equals(excluded)||path.startsWith(excluded+"/"))return false;
                    return path.equals(scope)||path.startsWith(scope+"/");
                },cancelled,consumer,count->progress.changed("Reading file details",total.visited+count));
                total.visited+=volume.visited;
                total.skipped+=volume.skipped;
                total.limited|=volume.limited;
                readable=true;
            }catch(java.io.InterruptedIOException canceled){throw canceled;}
            catch(IOException|SecurityException denied){total.skipped++;total.limited=true;}
        }
        if(!readable)throw new IOException("Android has not granted access to readable shared storage.");
        return total;
    }

    static boolean matches(CleanerSheet.Mode mode,CleanerRules.Match match) {
        switch(mode){
            case DOWNLOADS: return match.reason==CleanerRules.Reason.DOWNLOAD_MARKER;
            case TEMP_CACHE: return false; // Default mode uses the stricter evidence policy above.
            case BACKUPS: return match.category==CleanerRules.Category.BACKUP;
            case ARCHIVES: return match.category==CleanerRules.Category.ARCHIVE;
            case SOCIAL: return match.category==CleanerRules.Category.SOCIAL_MEDIA;
            case CUSTOM: return true; // User's extension, age, size and path filter has already run.
            default: return false;
        }
    }

    static CleanerSheet.Result duplicates(DuplicateFinder.Result verified,WalkTotals visited,
                                          BooleanSupplier cancelled)throws IOException {
        List<CleanerSheet.Row> display=new ArrayList<>();
        List<DuplicateReview.Group> reviews=new ArrayList<>();
        boolean clipped=false;
        int groupNumber=0;
        for(DuplicateFinder.Group group:verified.groups){
            CleanerScopedWalk.checkpoint(cancelled);
            int budget=MAX_ROWS-display.size();
            if(budget<2){clipped=true;break;}
            int shown=Math.min(group.members.size(),budget);
            clipped|=shown<group.members.size();
            String label="Exact match "+(++groupNumber);
            DuplicateReview.Group review=new DuplicateReview.Group(label,group,shown);
            reviews.add(review);
            for(DuplicateFinder.Member member:review.visible){
                boolean keep=member==group.keep;
                String reason=keep?keepReason(group.keepReason)
                        :member.role==DuplicateFinder.Role.BACKUP_FOLDER?"Backup folder · excluded from selection."
                        :member.role==DuplicateFinder.Role.BACKUP_FILE?"Backup filename · excluded from selection."
                        :member.protectedCopy?"Protected folder · excluded from selection."
                        :member.evidence.linkCount!=1?"Multiple hard links · excluded from selection."
                        :member.looksRenamedCopy?"Exact bytes and a copy-style filename."
                        :member.role==DuplicateFinder.Role.TEMPORARY_FOLDER?"Exact bytes in a temporary folder."
                        :"The scanner verified that this file’s bytes match the other copy.";
                display.add(new CleanerSheet.Row(member.entry,reason,label,keep,review,member));
            }
        }
        long skipped=visited.skipped+verified.inaccessible+verified.changed+verified.outOfScope;
        return new CleanerSheet.Result(display,reviews,visited.visited,skipped,
                clipped||visited.limited||verified.isPartial()||verified.outOfScope>0);
    }

    private static String keepReason(DuplicateFinder.KeepReason reason){
        switch(reason){
            case PROTECTED_FOLDER:return "Suggested because this copy is in a protected folder.";
            case PRIMARY_FOLDER:return "Suggested because this copy is in a primary folder.";
            case BACKUP_FOLDER:return "Suggested because this copy is in a backup folder.";
            case BACKUP_FILE:return "Suggested because this copy has a backup filename.";
            case ORIGINAL_NAME:return "Suggested because the filename looks like the original.";
            case OLDER_COPY:return "Suggested because this is the older copy.";
            case OUTSIDE_TEMPORARY_FOLDER:return "Suggested because this copy is outside temporary folders.";
            default:return "Suggested as the first copy by path. Check the folder before relying on this choice.";
        }
    }

    private CleanerReviewScanner(){}
}
