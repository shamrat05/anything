package dev.fastsearch.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.regex.Pattern;

/** Read-only matching by size and the SHA-256 of verified file bytes. */
public final class DuplicateFinder {
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final Pattern COPY_NAME = Pattern.compile(
            "^copy[ _-](?:of )?.+|.+(?:[ _-]+copy(?:[ _-]*\\d+)?|[ _-]*\\((?:copy(?:[ _-]*\\d+)?|[1-9]\\d{0,4})\\))$");

    public static final class Limits {
        public static final Limits DEFAULT = new Limits(100_000,4096,2L*1024*1024*1024);
        public final int maxRows, maxOpenAttempts;
        public final long maxBytes;
        public Limits(int maxRows,int maxOpenAttempts,long maxBytes){
            if(maxRows<=0||maxOpenAttempts<=0||maxBytes<=0)throw new IllegalArgumentException("Positive limits required.");
            this.maxRows=maxRows;this.maxOpenAttempts=maxOpenAttempts;this.maxBytes=maxBytes;
        }
    }

    /** Implementations must refuse virtual, unauthorized, symlinked and non-regular paths. */
    public interface Access {
        boolean eligible(FileEntry entry);
        ReadFile open(FileEntry entry)throws IOException;
    }

    public interface ReadFile extends AutoCloseable {
        /** Metadata of the opened descriptor, not of a separately opened filename. */
        Stamp stamp()throws IOException;
        InputStream stream();
        /** Revalidate both the descriptor and that the recorded pathname still denotes it. */
        boolean stillSame(Stamp before)throws IOException;
        @Override void close()throws IOException;
    }

    public static final class Stamp {
        public final String objectKey, revision;
        public final long size, linkCount;
        public Stamp(String objectKey,String revision,long size,long linkCount){
            if(objectKey==null||objectKey.isEmpty()||revision==null||size<0)
                throw new IllegalArgumentException("Verified identity and size required.");
            this.objectKey=objectKey;this.revision=revision;this.size=size;this.linkCount=linkCount;
        }
        public boolean same(Stamp other){
            return other!=null&&size==other.size&&linkCount==other.linkCount
                    &&objectKey.equals(other.objectKey)&&revision.equals(other.revision);
        }
    }

    public enum Role { PROTECTED_FOLDER, PRIMARY_FOLDER, BACKUP_FOLDER, BACKUP_FILE, OTHER, TEMPORARY_FOLDER }
    public enum KeepReason {
        PROTECTED_FOLDER, PRIMARY_FOLDER, BACKUP_FOLDER, BACKUP_FILE, ORIGINAL_NAME, OLDER_COPY, STABLE_PATH, OUTSIDE_TEMPORARY_FOLDER
    }

    public static final class Member {
        public final FileEntry entry;
        public final Role role;
        public final boolean looksRenamedCopy, protectedCopy;
        /** Proof at scan time, never permission to remove: revalidate fresh before further action. */
        public final Stamp evidence;
        private Member(FileEntry entry,Stamp stamp){
            this.entry=entry;evidence=stamp;role=classify(entry);
            String name=entry.name.toLowerCase(Locale.ROOT);
            int ext=name.lastIndexOf('.');
            looksRenamedCopy=COPY_NAME.matcher(ext>0?name.substring(0,ext):name).matches();
            protectedCopy=role==Role.PROTECTED_FOLDER||role==Role.BACKUP_FOLDER||role==Role.BACKUP_FILE;
        }
    }

    public static final class Group {
        public final long sizePerFile;
        /** Logical estimate excluding protected folders, backups and entries with multiple hard links. */
        public final long removableBytesEstimate;
        public final Member keep;
        public final KeepReason keepReason;
        public final List<Member> members;
        /** Digest of the full observed content, not an identity guarantee for later use. */
        public final String sha256;
        private Group(long size,Digest digest,List<Member> sorted){
            sizePerFile=size;sha256=digest.hex();members=immutable(sorted);keep=members.get(0);
            Member other=members.get(1);
            if(keep.role==Role.PROTECTED_FOLDER)keepReason=KeepReason.PROTECTED_FOLDER;
            else if(keep.role!=other.role&&keep.role==Role.PRIMARY_FOLDER)keepReason=KeepReason.PRIMARY_FOLDER;
            else if(keep.role!=other.role&&keep.role==Role.BACKUP_FOLDER)keepReason=KeepReason.BACKUP_FOLDER;
            else if(keep.role!=other.role&&keep.role==Role.BACKUP_FILE)keepReason=KeepReason.BACKUP_FILE;
            else if(keep.role!=other.role)keepReason=KeepReason.OUTSIDE_TEMPORARY_FOLDER;
            else if(!keep.looksRenamedCopy&&other.looksRenamedCopy)keepReason=KeepReason.ORIGINAL_NAME;
            else if(effectiveModified(keep.entry)!=effectiveModified(other.entry))keepReason=KeepReason.OLDER_COPY;
            else keepReason=KeepReason.STABLE_PATH;
            long estimate=0;
            for(int i=1;i<members.size();i++){
                Member copy=members.get(i);
                if(!copy.protectedCopy&&copy.evidence.linkCount==1)estimate=saturatingAdd(estimate,size);
            }
            removableBytesEstimate=estimate;
        }
    }

    public static final class Result {
        public final List<Group> groups;
        public final int rowsExamined,openAttempts,verifiedFiles,inaccessible,changed,sameFileAliases,outOfScope;
        public final long bytesRead,removableBytesEstimate;
        public final boolean rowLimitReached,openLimitReached,byteLimitReached;
        private Result(List<Group> groups,Progress p){
            this.groups=immutable(groups);rowsExamined=p.rows;openAttempts=p.attempts;verifiedFiles=p.verified;
            inaccessible=p.inaccessible;changed=p.changed;sameFileAliases=p.aliases;outOfScope=p.outOfScope;bytesRead=p.bytes;
            rowLimitReached=p.rowsLimited;openLimitReached=p.openLimited;byteLimitReached=p.bytesLimited;
            long estimate=0;
            for(Group group:groups)estimate=saturatingAdd(estimate,group.removableBytesEstimate);
            removableBytesEstimate=estimate;
        }
        public boolean isPartial(){
            return rowLimitReached||openLimitReached||byteLimitReached||inaccessible>0||changed>0;
        }
    }

    private static final class Progress {
        int rows,attempts,verified,inaccessible,changed,aliases,outOfScope;
        long bytes;
        boolean rowsLimited,openLimited,bytesLimited;
    }

    private static final class PathKey {
        final FileEntry entry;
        PathKey(FileEntry entry){this.entry=entry;}
        @Override public int hashCode(){return entry.pathHash();}
        @Override public boolean equals(Object other){
            return other instanceof PathKey&&entry.samePath(((PathKey)other).entry);
        }
    }

    private static final class Bucket {
        final FileEntry first;
        ArrayList<FileEntry> files;
        Bucket(FileEntry first){this.first=first;}
        void add(FileEntry more){
            if(files==null){files=new ArrayList<>(4);files.add(first);}
            files.add(more);
        }
    }

    private static final class Digest {
        final byte[] bytes;
        final int hash;
        Digest(byte[] bytes){this.bytes=bytes;hash=Arrays.hashCode(bytes);}
        @Override public boolean equals(Object other){return other instanceof Digest&&Arrays.equals(bytes,((Digest)other).bytes);}
        @Override public int hashCode(){return hash;}
        String hex(){
            final char[] digits="0123456789abcdef".toCharArray(), output=new char[2*bytes.length];
            for(int i=0;i<bytes.length;i++){output[2*i]=digits[bytes[i]>>>4&15];output[2*i+1]=digits[bytes[i]&15];}
            return new String(output);
        }
    }

    private static final class Verified {
        final Digest digest;
        final Member member;
        Verified(Digest digest,Member member){this.digest=digest;this.member=member;}
    }

    private static final class Match {
        final long size;
        final Digest digest;
        final Map<String,Member> files;
        Match(long size,Digest digest,Map<String,Member> files){this.size=size;this.digest=digest;this.files=files;}
    }

    private static final Comparator<FileEntry> PATH=FileEntry::comparePath;
    private static final Comparator<Member> KEEP=Comparator.comparingInt((Member item)->item.role.ordinal())
            .thenComparing(item->item.looksRenamedCopy).thenComparingLong(item->effectiveModified(item.entry))
            .thenComparing((a,b)->PATH.compare(a.entry,b.entry));

    /** Call only on a worker. Cancellation throws; incomplete scans never fabricate unchecked matches. */
    public static Result scan(Iterable<FileEntry> entries,Access access,Limits limits,
                              BooleanSupplier cancelled)throws IOException{
        checkCancelled(cancelled);
        Progress p=new Progress();
        HashMap<Long,Bucket> bySize=new HashMap<>();
        Set<PathKey> paths=new HashSet<>();
        Iterator<FileEntry> rows=entries.iterator();
        while(p.rows<limits.maxRows&&rows.hasNext()){
            checkCancelled(cancelled);
            FileEntry entry=rows.next();p.rows++;
            // A zero-length match never represents usable storage. Unknown sizes are not guessed.
            if(entry==null||entry.directory||entry.size<=0)continue;
            String path=entry.path();
            if(!directPath(path)||!access.eligible(entry)){p.outOfScope++;continue;}
            // Don't retain another copy of potentially long strings from the indexed paths.
            if(!paths.add(new PathKey(entry)))continue;
            Bucket bucket=bySize.get(entry.size);
            if(bucket==null)bySize.put(entry.size,new Bucket(entry));
            else bucket.add(entry);
        }
        p.rowsLimited=rows.hasNext();
        paths.clear();
        ArrayList<Long> sizes=new ArrayList<>();
        for(Map.Entry<Long,Bucket> item:bySize.entrySet()){
            checkCancelled(cancelled);
            if(item.getValue().files!=null)sizes.add(item.getKey());
        }
        sort(sizes,Comparator.naturalOrder(),cancelled);

        ArrayList<Match> matches=new ArrayList<>();
        Map<String,Verified> seenObjects=new HashMap<>();
        Set<String> unstableObjects=new HashSet<>();
        MessageDigest digest=sha256();
        byte[] buffer=new byte[BUFFER_BYTES];
        for(long size:sizes){
            checkCancelled(cancelled);
            if(p.attempts>=limits.maxOpenAttempts){p.openLimited=true;break;}
            if(size>(limits.maxBytes-p.bytes)/2){p.bytesLimited=true;break;}
            ArrayList<FileEntry> candidates=bySize.remove(size).files;
            sort(candidates,PATH,cancelled);
            Map<Digest,Map<String,Member>> matchesAtSize=new HashMap<>();
            for(FileEntry candidate:candidates){
                checkCancelled(cancelled);
                if(p.attempts>=limits.maxOpenAttempts){p.openLimited=true;break;}
                if(size>limits.maxBytes-p.bytes){p.bytesLimited=true;break;}
                p.attempts++;
                Verified checked=hash(candidate,access,digest,buffer,p,unstableObjects,cancelled);
                if(checked==null)continue;
                Stamp stamp=checked.member.evidence;
                if(unstableObjects.contains(stamp.objectKey))continue;
                Verified previous=seenObjects.putIfAbsent(stamp.objectKey,checked);
                if(previous!=null){
                    if(!previous.member.evidence.same(stamp)||!previous.digest.equals(checked.digest)){
                        // Any inconsistent alias across the scan invalidates this physical object.
                        unstableObjects.add(stamp.objectKey);p.changed++;continue;
                    }
                    p.aliases++;
                }
                Map<String,Member> same=matchesAtSize.computeIfAbsent(checked.digest,k->new HashMap<>());
                Member alias=same.get(stamp.objectKey);
                if(alias==null||KEEP.compare(checked.member,alias)<0)same.put(stamp.objectKey,checked.member);
            }
            for(Map.Entry<Digest,Map<String,Member>> item:matchesAtSize.entrySet()){
                checkCancelled(cancelled);
                if(item.getValue().size()>1)matches.add(new Match(size,item.getKey(),item.getValue()));
            }
        }

        ArrayList<Group> groups=new ArrayList<>();
        for(Match match:matches){
            checkCancelled(cancelled);
            ArrayList<Member> members=new ArrayList<>();
            for(Member member:match.files.values()){
                checkCancelled(cancelled);
                if(!unstableObjects.contains(member.evidence.objectKey))members.add(member);
            }
            if(members.size()<2)continue;
            sort(members,KEEP,cancelled);
            groups.add(new Group(match.size,match.digest,members));
        }
        sort(groups,Comparator.comparingLong((Group group)->group.removableBytesEstimate).reversed()
                .thenComparing(Comparator.comparingLong((Group group)->group.sizePerFile).reversed())
                .thenComparing((a,b)->PATH.compare(a.keep.entry,b.keep.entry)),cancelled);
        return new Result(groups,p);
    }

    private static Verified hash(FileEntry entry,Access access,MessageDigest digest,byte[] buffer,
                                 Progress p,Set<String> unstable,BooleanSupplier cancelled)throws IOException{
        try(ReadFile file=access.open(entry)){
            Stamp before=file.stamp();
            if(before.size!=entry.size){unstable.add(before.objectKey);p.changed++;return null;}
            InputStream contents=file.stream();
            long remaining=entry.size;
            digest.reset();
            while(remaining>0){
                checkCancelled(cancelled);
                int read=contents.read(buffer,0,(int)Math.min(buffer.length,remaining));
                if(read<0){unstable.add(before.objectKey);p.changed++;return null;}
                if(read==0)throw new IOException("File descriptor did not advance.");
                digest.update(buffer,0,read);remaining-=read;p.bytes+=read;
            }
            checkCancelled(cancelled);
            // A post-read stamp catches truncated/grown or replaced paths without an unbudgeted extra byte.
            if(!file.stillSame(before)){unstable.add(before.objectKey);p.changed++;return null;}
            p.verified++;
            return new Verified(new Digest(digest.digest()),new Member(entry,before));
        }catch(ClosedByInterruptException interrupted){
            Thread.currentThread().interrupt();throw cancelled();
        }catch(InterruptedIOException interrupted){
            if(wantsCancellation(cancelled))throw cancelled();
            p.inaccessible++;return null;
        }catch(IOException|SecurityException inaccessible){
            checkCancelled(cancelled);p.inaccessible++;return null;
        }
    }

    /**
     * Read-only proof refresh for a later, separately authorized workflow. Opens both descriptors at
     * once, repeats the full hashes and checks both identities after reading the second file.
     * A true result says nothing about changes after these descriptors close.
     */
    public static boolean revalidate(Group group,Member target,Member keeper,Access access,
                                     BooleanSupplier cancelled)throws IOException{
        checkCancelled(cancelled);
        if(group==null||target==null||keeper==null||target==keeper||target.protectedCopy
                ||target.evidence.linkCount!=1||target.evidence.objectKey.equals(keeper.evidence.objectKey)
                ||!group.members.contains(target)||!group.members.contains(keeper)
                ||group.sizePerFile>Limits.DEFAULT.maxBytes/2
                ||!access.eligible(target.entry)||!access.eligible(keeper.entry))return false;
        MessageDigest sha=sha256();
        try(ReadFile candidate=access.open(target.entry);ReadFile survivor=access.open(keeper.entry)){
            byte[] buffer=new byte[BUFFER_BYTES];
            if(!proofMatches(candidate,target.evidence,group.sha256,sha,buffer,cancelled)
                    ||!proofMatches(survivor,keeper.evidence,group.sha256,sha,buffer,cancelled))return false;
            checkCancelled(cancelled);
            return candidate.stillSame(target.evidence)&&survivor.stillSame(keeper.evidence);
        }catch(ClosedByInterruptException interrupted){
            Thread.currentThread().interrupt();throw cancelled();
        }catch(IOException|SecurityException unavailable){
            checkCancelled(cancelled);return false;
        }
    }

    private static boolean proofMatches(ReadFile file,Stamp expected,String hex,MessageDigest sha,
                                        byte[] buffer,BooleanSupplier cancelled)throws IOException{
        if(!expected.same(file.stamp()))return false;
        long left=expected.size;
        InputStream source=file.stream();
        sha.reset();
        while(left>0){
            checkCancelled(cancelled);
            int read=source.read(buffer,0,(int)Math.min(buffer.length,left));
            if(read<=0)return false;
            sha.update(buffer,0,read);left-=read;
        }
        checkCancelled(cancelled);
        return file.stillSame(expected)&&hex.equals(new Digest(sha.digest()).hex());
    }

    private static Role classify(FileEntry entry){
        boolean primary=false,backup=false,temp=false;
        String[] folders=entry.parent.toLowerCase(Locale.ROOT).split("/");
        for(int i=0;i<folders.length;i++){
            String part=folders[i];
            if(part.equals("original")||part.equals("originals")||part.equals("vault")
                    ||part.equals("keep")||part.equals("important"))return Role.PROTECTED_FOLDER;
            if(part.equals("backup")||part.equals("backups")||part.equals("restore")||part.equals("archives"))backup=true;
            if(part.equals("tmp")||part.equals("temp")||part.equals("cache")||part.equals(".cache"))temp=true;
            if(part.equals("documents")||part.equals("projects")||part.equals("pictures")
                    ||part.equals("dcim")&&i+1<folders.length&&folders[i+1].equals("camera"))primary=true;
        }
        if(backup)return Role.BACKUP_FOLDER;
        if(CleanerRules.hasBackupFilename(entry.foldedName))return Role.BACKUP_FILE;
        if(temp)return Role.TEMPORARY_FOLDER;
        return primary?Role.PRIMARY_FOLDER:Role.OTHER;
    }
    private static long effectiveModified(FileEntry file){return file.modified<0?Long.MAX_VALUE:file.modified;}

    private static boolean directPath(String path){
        if(!path.startsWith("/")||path.length()==1||path.length()>4096||path.indexOf('\0')>=0||path.endsWith("/"))return false;
        if(path.equals("/proc")||path.startsWith("/proc/")||path.equals("/sys")||path.startsWith("/sys/")
                ||path.equals("/dev")||path.startsWith("/dev/"))return false;
        int start=1;
        for(int i=1;i<=path.length();i++){
            if(i!=path.length()&&path.charAt(i)!='/'){
                char character=path.charAt(i);
                if(character=='\\'||Character.isISOControl(character)||Character.getType(character)==Character.FORMAT)return false;
                continue;
            }
            int length=i-start;
            if(length==0||path.charAt(start)=='.'&&(length==1||length==2&&path.charAt(start+1)=='.'))return false;
            start=i+1;
        }
        return true;
    }

    private static MessageDigest sha256(){
        try{return MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException unavailable){throw new AssertionError("Required SHA-256 is unavailable.",unavailable);}
    }
    private static boolean wantsCancellation(BooleanSupplier cancel){
        return Thread.currentThread().isInterrupted()||cancel!=null&&cancel.getAsBoolean();
    }
    private static void checkCancelled(BooleanSupplier cancel)throws InterruptedIOException{
        if(wantsCancellation(cancel))throw cancelled();
    }
    private static InterruptedIOException cancelled(){return new InterruptedIOException("Duplicate scan cancelled.");}
    private static final class SortCancelled extends RuntimeException{private static final long serialVersionUID=1L;}
    private static <T> void sort(List<T> values,Comparator<? super T> comparator,BooleanSupplier cancelled)
            throws InterruptedIOException{
        checkCancelled(cancelled);
        int[] comparisons={0};
        try{
            values.sort((a,b)->{
                if((comparisons[0]++&255)==0&&wantsCancellation(cancelled))throw new SortCancelled();
                return comparator.compare(a,b);
            });
        }catch(SortCancelled interrupted){throw cancelled();}
        checkCancelled(cancelled);
    }
    private static long saturatingAdd(long a,long b){return a>Long.MAX_VALUE-b?Long.MAX_VALUE:a+b;}
    private static <T> List<T> immutable(List<T> values){return Collections.unmodifiableList(new ArrayList<>(values));}
    private DuplicateFinder(){}
}
