package dev.fastsearch.core;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Metadata-based, read-only planning. Nothing here authorizes changing or removing a file. */
public final class CleanerRecommendations {
    public static final long DAY_MS=24L*60*60*1000;
    public static final long INSTALLER_AGE_MS=30*DAY_MS, PARTIAL_AGE_MS=7*DAY_MS, TEMP_AGE_MS=14*DAY_MS;
    private static final int MAX_SCAN_ROWS=100_000, MAX_PLAN_ROWS=5_000;
    private static final String[] PARTIAL={".crdownload",".opdownload",".part",".download"};
    private static final String[] ARCHIVES={".zip",".7z",".rar",".tar",".tgz",".gz",".bz2",".xz",".zst",".cbz",".jar"};
    private static final String[] BACKUP={".bak",".backup",".orig",".old",".swp",".swo",".swx","~"};
    private static final String[] PACKAGE_BUNDLES={".apks",".xapk",".apkm"}, TEMPORARY={".tmp",".temp"};

    public enum Disposition { SUGGESTED_REVIEW, REVIEW_ONLY }
    public enum Sort { SUGGESTIONS_FIRST, LARGEST_FIRST, OLDEST_FIRST, PATH }
    public enum Rule {
        DOWNLOAD_INSTALLER("download.installer.old","An APK directly in Downloads, last modified at least 30 days ago. Check whether you still need this installer."),
        DOWNLOAD_PARTIAL("download.partial.old","An incomplete-download filename directly in Downloads, last modified at least 7 days ago. Check that the download is not active."),
        SHARED_TEMP("shared.temp.old","A .tmp or .temp file in a designated shared temporary folder, last modified at least 14 days ago. Check it before moving anything."),
        ACTIVE_FILE("protected.active","This file was marked as in use. Review only."),
        BACKUP_FILE("protected.backup","A backup or recovery clue is present. Keep it out of the savings estimate."),
        ARCHIVE_FILE("protected.archive","This file appears to be or contain an archive. Its contents may be important."),
        MESSAGING_FILE("protected.messaging","This file is in a shared messaging location. Its app may still use it."),
        PROTECTED_LOCATION("protected.location","This file is in an Originals, Vault, Archives, Keep, or similar folder. Review only."),
        OUTSIDE_EXPECTED_FOLDER("review.location","The file type matches, but it is not in the specific Downloads or temporary location for a suggestion."),
        RECENT_FILE("review.recent","This file is newer than the recommendation threshold. It might still be needed."),
        UNKNOWN_AGE("review.unknown_age","The modification date is unknown or in the future. No age-based recommendation is possible."),
        PACKAGE_BUNDLE("review.package_bundle","This may be a bundled Android installer. Review its contents or purpose before changing it."),
        METADATA_CLUE("review.metadata","Location or filename offers a clue. It does not establish that the file is unused.");

        public final String id, reason;
        Rule(String id,String reason){this.id=id;this.reason=reason;}
    }

    public static final class Item {
        public final FileEntry entry;
        public final Rule rule;
        public final Disposition disposition;
        public final String reason;
        /** Logical file size, not promised free space or permission to remove. */
        public final long suggestedLogicalBytes;
        private Item(FileEntry entry,Rule rule,boolean suggested,String reason){
            this.entry=entry;this.rule=rule;this.reason=reason;
            disposition=suggested?Disposition.SUGGESTED_REVIEW:Disposition.REVIEW_ONLY;
            suggestedLogicalBytes=suggested?entry.size:0;
        }
    }

    public static final class Plan {
        public final List<Item> items;
        public final int rowsExamined;
        public final int metadataConflicts;
        public final boolean partial;
        /** Sum for suggestions visible in this plan only; all remain unselected. */
        public final long suggestedLogicalBytes;
        private Plan(List<Item> items,int rows,int conflicts,boolean partial){
            this.items=Collections.unmodifiableList(new ArrayList<>(items));
            rowsExamined=rows;metadataConflicts=conflicts;this.partial=partial;
            long sum=0;for(Item item:items)sum=add(sum,item.suggestedLogicalBytes);suggestedLogicalBytes=sum;
        }

        /** Only exact paths explicitly requested by the caller are selected for review. */
        public Selection selectForReview(Collection<String> explicitPaths){
            if(explicitPaths==null||explicitPaths.size()>MAX_PLAN_ROWS)
                throw new IllegalArgumentException("A bounded explicit review selection is required.");
            Set<String> wanted=new HashSet<>(explicitPaths);
            List<Item> selected=new ArrayList<>();
            long logical=0;
            for(Item item:items){
                if(!wanted.remove(item.entry.path()))continue;
                selected.add(item);logical=add(logical,item.suggestedLogicalBytes);
            }
            return new Selection(selected,wanted.size(),logical);
        }
    }

    public static final class Selection {
        public final List<Item> items;
        public final int unmatchedPaths;
        public final long suggestedLogicalBytes;
        private Selection(List<Item> items,int unmatched,long logical){
            this.items=Collections.unmodifiableList(new ArrayList<>(items));
            unmatchedPaths=unmatched;suggestedLogicalBytes=logical;
        }
    }

    /** Use knownInUse when a checked application state marks this file active. Unknown is not proof of idle. */
    public static Item classify(FileEntry entry,long now,CleanerRules.Filter filter,boolean knownInUse){
        if(entry==null||filter==null||entry.directory||entry.size<=0||!filter.accepts(entry,now))return null;
        String name=entry.foldedName, relative=relativeParent(entry.parent);
        CleanerRules.Match match=CleanerRules.classify(entry,now,filter);
        String partial=suffix(name,PARTIAL);
        String original=partial==null?name:name.substring(0,name.length()-partial.length());
        boolean apk=name.endsWith(".apk")&&name.length()>4;
        String bundle=suffix(name,PACKAGE_BUNDLES), temp=suffix(name,TEMPORARY);
        if(match==null&&partial==null&&!apk&&bundle==null&&temp==null)return null;
        if(knownInUse)return review(entry,Rule.ACTIVE_FILE);
        int extension=apk?4:temp==null?0:temp.length();
        String stem=extension==0?original:name.substring(0,name.length()-extension);
        if(match!=null&&match.category==CleanerRules.Category.BACKUP
                ||suffix(original,BACKUP)!=null||suffix(stem,BACKUP)!=null
                ||segment(relative,"backup","backups","restore")||backupName(stem))
            return review(entry,Rule.BACKUP_FILE);
        if(match!=null&&match.category==CleanerRules.Category.ARCHIVE||suffix(original,ARCHIVES)!=null
                ||suffix(stem,ARCHIVES)!=null)
            return review(entry,Rule.ARCHIVE_FILE);
        if(match!=null&&match.category==CleanerRules.Category.SOCIAL_MEDIA
                ||starts(relative,"android/media")||segment(relative,"whatsapp","whatsapp business","telegram","signal"))
            return review(entry,Rule.MESSAGING_FILE);
        if(segment(relative,"original","originals","archive","archives","vault","keep","important"))
            return review(entry,Rule.PROTECTED_LOCATION);
        if(bundle!=null)return review(entry,Rule.PACKAGE_BUNDLE);

        boolean download=relative.equals("download")||relative.equals("downloads");
        if(apk)return byAge(entry,now,download,INSTALLER_AGE_MS,Rule.DOWNLOAD_INSTALLER);
        if(partial!=null)return byAge(entry,now,download,PARTIAL_AGE_MS,Rule.DOWNLOAD_PARTIAL);
        if(temp!=null)return byAge(entry,now,temporary(relative),TEMP_AGE_MS,Rule.SHARED_TEMP);
        return new Item(entry,Rule.METADATA_CLUE,false,match==null?Rule.METADATA_CLUE.reason:match.reason.label);
    }

    /**
     * Builds a bounded plan over the caller's already-discovered immutable records; performs no I/O.
     * Conflicting observations of an exact path are omitted instead of choosing one for review.
     */
    public static Plan plan(Iterable<FileEntry> entries,long now,CleanerRules.Filter filter,
                            Predicate<FileEntry> knownInUse,Sort sort,int maxShown,
                            BooleanSupplier cancelled)throws InterruptedIOException{
        if(maxShown<=0||maxShown>MAX_PLAN_ROWS||filter==null||sort==null)
            throw new IllegalArgumentException("Invalid Cleaner review plan.");
        Map<PathKey,Observation> observed=new HashMap<>();
        Iterator<FileEntry> source=entries.iterator();
        int rows=0;
        while(rows<MAX_SCAN_ROWS&&source.hasNext()){
            check(cancelled);
            FileEntry entry=source.next();rows++;
            if(entry==null)continue;
            PathKey key=new PathKey(entry);
            Observation previous=observed.get(key);
            // Even if the newer record is below a user's age/size filter, never let a second
            // observation promote a stale older record of the same path.
            Item item=filter.accepts(entry,now)?classify(entry,now,filter,knownInUse!=null&&knownInUse.test(entry)):null;
            if(previous==null)observed.put(key,new Observation(entry,item));
            else {
                previous.relevant|=item!=null;
                if(entry.size!=previous.entry.size||entry.modified!=previous.entry.modified||entry.directory!=previous.entry.directory){
                    previous.ambiguous=true;previous.item=null;
                }
                if(!previous.ambiguous&&item!=null&&(previous.item==null||priority(item)<priority(previous.item)
                        ||item.rule==Rule.ACTIVE_FILE))previous.item=item;
            }
        }
        int conflicts=0;
        ArrayList<Item> ranked=new ArrayList<>();
        for(Observation observation:observed.values()){
            check(cancelled);
            if(observation.ambiguous&&observation.relevant)conflicts++;
            else if(observation.item!=null)ranked.add(observation.item);
        }
        boolean limited=source.hasNext()||ranked.size()>maxShown||conflicts>0;
        Comparator<Item> order=order(sort);
        try{
            int[] comparisons={0};
            ranked.sort((a,b)->{
                if((comparisons[0]++&255)==0&&isCancelled(cancelled))throw new SortCancelled();
                return order.compare(a,b);
            });
        }catch(SortCancelled canceled){throw new InterruptedIOException("Cleaner review cancelled.");}
        check(cancelled);
        return new Plan(ranked.subList(0,Math.min(ranked.size(),maxShown)),rows,conflicts,limited);
    }

    public static Comparator<Item> order(Sort sort){
        Comparator<Item> path=(a,b)->a.entry.comparePath(b.entry);
        Comparator<Item> largest=Comparator.comparingLong((Item item)->item.entry.size).reversed().thenComparing(path);
        switch(sort){
            case PATH:return path;
            case LARGEST_FIRST:return largest;
            case OLDEST_FIRST:return Comparator.comparingLong((Item i)->i.entry.modified<0?Long.MAX_VALUE:i.entry.modified)
                    .thenComparing(largest);
            default:return Comparator.comparingInt(CleanerRecommendations::priority).reversed().thenComparing(largest);
        }
    }

    private static int priority(Item item){return item.disposition==Disposition.SUGGESTED_REVIEW?1:0;}
    private static Item review(FileEntry e,Rule rule){return new Item(e,rule,false,rule.reason);}
    private static Item byAge(FileEntry entry,long now,boolean located,long age,Rule rule){
        if(!located)return review(entry,Rule.OUTSIDE_EXPECTED_FOLDER);
        if(entry.modified<0||entry.modified>now)return review(entry,Rule.UNKNOWN_AGE);
        if(now-entry.modified<age)return review(entry,Rule.RECENT_FILE);
        return new Item(entry,rule,true,rule.reason);
    }

    /** A Filter has already verified the scoped volume syntax; compare only the relative folder. */
    private static String relativeParent(String path){
        int start=path.startsWith("/storage/emulated/")?18:9;
        int slash=path.indexOf('/',start);
        return slash<0?"":path.substring(slash+1).toLowerCase(Locale.ROOT);
    }

    private static boolean temporary(String path){
        String folder=path.startsWith("download/")?path.substring(9):path.startsWith("downloads/")?path.substring(10):path;
        return folder.equals("tmp")||folder.equals("temp")||folder.equals("temporary");
    }
    private static boolean starts(String path,String prefix){return path.equals(prefix)||path.startsWith(prefix+"/");}
    private static boolean segment(String path,String... labels){
        for(int from=0;from<path.length();){
            int to=path.indexOf('/',from);if(to<0)to=path.length();
            for(String label:labels)
                if(label.length()==to-from&&path.regionMatches(from,label,0,label.length()))return true;
            from=to+1;
        }
        return false;
    }
    private static boolean backupName(String name){
        for(String marker:new String[]{"backup","backups","restore"}){
            if(name.equals(marker))return true;
            if(name.endsWith("-"+marker)||name.endsWith("_"+marker)||name.endsWith(" "+marker)
                    ||name.endsWith("("+marker+")"))return true;
        }
        return false;
    }
    private static String suffix(String name,String[] tails){
        for(String tail:tails)if(name.length()>tail.length()&&name.endsWith(tail))return tail;
        return null;
    }
    private static long add(long a,long b){return a>Long.MAX_VALUE-b?Long.MAX_VALUE:a+b;}
    private static boolean isCancelled(BooleanSupplier cancel){return Thread.currentThread().isInterrupted()||cancel!=null&&cancel.getAsBoolean();}
    private static void check(BooleanSupplier cancel)throws InterruptedIOException{
        if(isCancelled(cancel))throw new InterruptedIOException("Cleaner review cancelled.");
    }
    private static final class SortCancelled extends RuntimeException{private static final long serialVersionUID=1L;}
    private static final class Observation{
        final FileEntry entry;
        Item item;
        boolean ambiguous,relevant;
        Observation(FileEntry entry,Item item){this.entry=entry;this.item=item;relevant=item!=null;}
    }
    private static final class PathKey{
        final FileEntry entry;
        PathKey(FileEntry entry){this.entry=entry;}
        @Override public int hashCode(){return entry.pathHash();}
        @Override public boolean equals(Object other){return other instanceof PathKey&&entry.samePath(((PathKey)other).entry);}
    }
    private CleanerRecommendations(){}
}
