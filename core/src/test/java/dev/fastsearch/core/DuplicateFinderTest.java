package dev.fastsearch.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DuplicateFinderTest {
    private static int assertions;
    private static final DuplicateFinder.Limits NORMAL=DuplicateFinder.Limits.DEFAULT;
    public static void main(String[] args)throws Exception{
        sizeAndHash();
        unstableAndUnsafe();
        budgetAndStress();
        cancellationAndApplyGate();
        realDescriptorBoundary();
        System.out.println("PASS "+assertions+" read-only duplicate scanning and proof checks.");
    }

    private static void sizeAndHash()throws Exception{
        FakeSource fs=new FakeSource();
        byte[] one=bytes("starting-same END#1");
        byte[] two=bytes("starting-same END#2");
        List<FileEntry> rows=new ArrayList<>();
        rows.add(fs.add("/shared/Vault/report.txt",one,"vault",2,900));
        rows.add(fs.add("/shared/Downloads/report (1).txt",one,"download",1,100));
        rows.add(fs.add("/shared/backups/report.txt",one,"backup",1,50));
        rows.add(fs.add("/shared/Shortcuts/report.txt",one,"vault",2,900));
        rows.add(fs.add("/shared/Downloads/hardlink.txt",one,"other-multi",3,350));
        rows.add(fs.add("/shared/Downloads/same_name.txt",two,"mutated",1,99));
        rows.add(fs.add("/shared/Downloads/unique.bin",new byte[1224],"unique",1,1));
        rows.add(new FileEntry("/shared/Downloads/directory",true,one.length,1));
        rows.add(new FileEntry("/shared/Downloads/empty.txt",false,0,1));
        rows.add(new FileEntry("/shared/Downloads/unreported.txt",false,-1,1));
        rows.add(rows.get(0));
        DuplicateFinder.Result result=DuplicateFinder.scan(rows,fs,NORMAL,()->false);
        check(result.groups.size()==1,"full streamed hash excludes same-size same-prefix different suffix");
        DuplicateFinder.Group group=result.groups.get(0);
        check(group.members.size()==4,"one path per physical inode");
        check(group.keep.entry.path().equals("/shared/Vault/report.txt"),"protected folder is suggested before old copies");
        check(group.keepReason==DuplicateFinder.KeepReason.PROTECTED_FOLDER,"accurate keep reason");
        check(group.members.stream().anyMatch(member->member.role==DuplicateFinder.Role.BACKUP_FOLDER
                &&member.protectedCopy),"backup is explicitly protected");
        check(group.removableBytesEstimate==one.length,"excluded backups and files linked elsewhere from estimate");
        check(result.removableBytesEstimate==one.length&&result.openAttempts==6
                &&result.bytesRead==6L*one.length,"counts actual hash reads, skips singleton/empty/folder/duplicate rows");
        check(result.sameFileAliases==1&&!result.isPartial(),"alias counted once but not as a duplicate");
        check(group.sha256.equals(hex(MessageDigest.getInstance("SHA-256").digest(one))),"immutable proof records full SHA-256");
        check(group.keep.evidence.objectKey.equals("vault")&&group.keep.evidence.linkCount==2,"immutable physical proof");
        sameShapeAfterShuffle(rows,fs,result);
        try{result.groups.clear();throw new AssertionError("result groups mutable");}
        catch(UnsupportedOperationException expected){check(true,"immutable groups");}
        try{group.members.clear();throw new AssertionError("group members mutable");}
        catch(UnsupportedOperationException expected){check(true,"immutable members");}

        FakeSource named=new FakeSource();
        List<FileEntry> names=Arrays.asList(named.add("/shared/Downloads/a (1).txt",one,"later",1,100),
                named.add("/shared/Downloads/a.txt",one,"original",1,200));
        DuplicateFinder.Group original=DuplicateFinder.scan(names,named,NORMAL,()->false).groups.get(0);
        check(original.keep.entry.name.equals("a.txt")&&original.keepReason==DuplicateFinder.KeepReason.ORIGINAL_NAME,
                "original-looking name preferred before misleading age");
    }

    private static void sameShapeAfterShuffle(List<FileEntry> rows,FakeSource fs,DuplicateFinder.Result expected)throws Exception{
        String key=expected.groups.get(0).keep.entry.path();
        for(int i=0;i<9;i++){
            Collections.shuffle(rows,new Random(i));
            DuplicateFinder.Result test=DuplicateFinder.scan(rows,fs,NORMAL,()->false);
            check(test.groups.get(0).keep.entry.path().equals(key),"deterministic keep after input permutation");
            List<String> order=new ArrayList<>();
            for(DuplicateFinder.Member m:test.groups.get(0).members)order.add(m.entry.path());
            List<String> old=new ArrayList<>();
            for(DuplicateFinder.Member m:expected.groups.get(0).members)old.add(m.entry.path());
            check(order.equals(old),"deterministic member order after input permutation");
        }
    }

    private static void unstableAndUnsafe()throws Exception{
        FakeSource fs=new FakeSource();
        byte[] data=bytes("synthetic");
        List<FileEntry> rows=new ArrayList<>();
        rows.add(fs.add("/shared/a.txt",data,"a",1,1));
        rows.add(fs.add("/shared/b.txt",data,"b",1,1));
        FileEntry drift=fs.add("/shared/c-drift.txt",data,"c",1,1);
        rows.add(drift);fs.records.get(drift.path()).onRead=()->fs.records.get(drift.path()).revision++;
        FileEntry vanished=fs.add("/shared/d-no-access.txt",data,"d",1,1);
        rows.add(vanished);fs.records.get(vanished.path()).denied=true;
        FileEntry sameBytesDifferentRevision=fs.add("/shared/z-alias.txt",data,"a",1,1);
        rows.add(sameBytesDifferentRevision);fs.records.get(sameBytesDifferentRevision.path()).revision++;
        rows.add(new FileEntry("/proc/sys/node",false,data.length,0));
        rows.add(new FileEntry("/sys/nested/path",false,data.length,0));
        rows.add(new FileEntry("/dev/zero",false,data.length,0));
        rows.add(new FileEntry("content://media/node",false,data.length,0));
        rows.add(new FileEntry("/shared/../out/nested",false,data.length,0));
        rows.add(new FileEntry("/shared/fake/./nested",false,data.length,0));
        rows.add(new FileEntry("/shared//duplicate/path",false,data.length,0));
        rows.add(new FileEntry("/private/secure.txt",false,data.length,0));
        DuplicateFinder.Result result=DuplicateFinder.scan(rows,fs,NORMAL,()->false);
        check(result.groups.isEmpty(),"disagreement between same-inode aliases removes previously found member");
        check(result.changed==2&&result.inaccessible==1&&result.outOfScope==8,"changed/permission/scope distinguishable");
        check(result.isPartial()&&result.openAttempts==5,"private and virtual rows never reach open");
        check(fs.openHandles==0&&fs.maxHandles==1,"all files closed and discovery opens one descriptor at a time");
    }

    private static void budgetAndStress()throws Exception{
        FakeSource fs=new FakeSource();
        List<FileEntry> rows=new ArrayList<>();
        for(int i=0;i<20_000;i++)rows.add(new FileEntry("/shared/unique-"+i,false,100+i,7));
        byte[] data=bytes("duplicated");
        for(int i=0;i<2049;i++)rows.add(fs.add(String.format(java.util.Locale.ROOT,"/shared/record-%05d",i),
                data,"unique"+i,1,i));
        DuplicateFinder.Result result=DuplicateFinder.scan(rows,fs,NORMAL,()->false);
        check(result.rowsExamined==22_049&&result.openAttempts==2049&&result.groups.size()==1,
                "thousands of sizes are indexed but never opened without a peer");
        check(result.groups.get(0).members.size()==2049&&result.bytesRead==2049L*data.length&&!result.isPartial(),
                "thousands of equal-length copies confirmed within the limits");
        check(fs.openHandles==0&&fs.maxHandles==1,"stress does not leak or fan out descriptors");
        DuplicateFinder.Result rowCap=DuplicateFinder.scan(rows,fs,new DuplicateFinder.Limits(10,20,10_000),()->false);
        check(rowCap.rowLimitReached&&rowCap.openAttempts==0&&rowCap.isPartial(),"row cap includes un-hashed singleton inventory");
        DuplicateFinder.Result openCap=DuplicateFinder.scan(rows,fs,new DuplicateFinder.Limits(22_000+100,2,10_000),()->false);
        check(openCap.openLimitReached&&openCap.openAttempts==2&&openCap.groups.get(0).members.size()==2,
                "open cap returns only positively checked files");
        DuplicateFinder.Result byteCap=DuplicateFinder.scan(rows,fs,new DuplicateFinder.Limits(22_000+100,20,2L*data.length),()->false);
        check(byteCap.byteLimitReached&&byteCap.openAttempts==2&&byteCap.bytesRead==2L*data.length,
                "read budget never opens a third candidate needing a full read");
        DuplicateFinder.Result lessThanTwo=DuplicateFinder.scan(rows,fs,new DuplicateFinder.Limits(22_000+100,20,data.length),()->false);
        check(lessThanTwo.byteLimitReached&&lessThanTwo.openAttempts==0&&lessThanTwo.groups.isEmpty(),
                "one file cannot form a verified pair; do not waste its data read");
    }

    private static void cancellationAndApplyGate()throws Exception{
        FakeSource fs=new FakeSource();
        byte[] data=new byte[132_000];
        FileEntry keep=fs.add("/shared/Documents/same.bin",data,"a",1,1);
        FileEntry candidate=fs.add("/shared/Downloads/same.bin",data,"b",1,1);
        FileEntry hardlink=fs.add("/shared/Downloads/old-same.bin",data,"c",2,1);
        FileEntry backup=fs.add("/shared/Backup/same.bin",data,"d",1,1);
        List<FileEntry> rows=Arrays.asList(keep,candidate,backup,hardlink);
        AtomicBoolean cancel=new AtomicBoolean();
        fs.records.get(backup.path()).onRead=()->cancel.set(true);
        try{DuplicateFinder.scan(rows,fs,NORMAL,cancel::get);throw new AssertionError("did not cancel");}
        catch(InterruptedIOException expected){check(fs.openHandles==0,"mid-stream cancellation closes descriptor");}
        cancel.set(false);
        fs.records.get(backup.path()).onRead=null;
        DuplicateFinder.Group group=DuplicateFinder.scan(rows,fs,NORMAL,()->false).groups.get(0);
        check(group.keep.entry==keep,"authoritative folder selected before backup");
        DuplicateFinder.Member target=member(group,candidate), protectedFile=member(group,backup), alias=member(group,hardlink);
        check(DuplicateFinder.revalidate(group,target,group.keep,fs,()->false),"independent unchanged pair revalidates");
        check(fs.openHandles==0&&fs.maxHandles==2,"fresh proof holds two independent descriptors and closes both");
        check(!DuplicateFinder.revalidate(group,protectedFile,group.keep,fs,()->false),"backup cannot become a cleanup candidate");
        check(!DuplicateFinder.revalidate(group,alias,group.keep,fs,()->false),"hardlink cannot become a cleanup candidate");
        check(!DuplicateFinder.revalidate(group,target,target,fs,()->false),"target cannot serve as own keeper");
        fs.records.get(keep.path()).onRead=()->fs.records.get(candidate.path()).revision++;
        check(!DuplicateFinder.revalidate(group,target,group.keep,fs,()->false),
                "after keeper is read both identities are checked again");
        fs.records.get(keep.path()).onRead=null;
        fs.records.get(candidate.path()).revision=0;
        fs.records.get(keep.path()).data=Arrays.copyOf(data,data.length);fs.records.get(keep.path()).data[1024]=1;
        check(!DuplicateFinder.revalidate(group,target,group.keep,fs,()->false),
                "identical identity and size alone never validate changed keeper bytes");
    }

    private static void realDescriptorBoundary()throws Exception{
        Path own=Files.createTempDirectory("duplicate-engine-");
        try{
            Path a=Files.write(own.resolve("a.txt"),bytes("known equality"));
            Path b=Files.write(own.resolve("b.txt"),bytes("known equality"));
            Path leaf=Files.createSymbolicLink(own.resolve("link.txt"),b.getFileName());
            Path dir=Files.createDirectory(own.resolve("nested"));
            Path inner=Files.write(dir.resolve("inner.txt"),bytes("known equality"));
            Path alias=Files.createSymbolicLink(own.resolve("alias"),dir.getFileName()).resolve(inner.getFileName());
            List<FileEntry> input=new ArrayList<>();
            for(Path path:Arrays.asList(a,b,leaf,inner,alias))input.add(new FileEntry(path.toString(),false,Files.size(path),1));
            DuplicateFinder.Access folder=new DuplicateFinder.Access(){
                @Override public boolean eligible(FileEntry e){return e.parent.startsWith(own.toString());}
                @Override public DuplicateFinder.ReadFile open(FileEntry e)throws IOException{
                    Path direct=java.nio.file.Paths.get(e.path());
                    if(!direct.equals(direct.toRealPath())||!Files.isRegularFile(direct,LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("not a direct regular path");
                    InputStream read=Files.newInputStream(direct,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
                    return new DuplicateFinder.ReadFile(){
                        @Override public DuplicateFinder.Stamp stamp()throws IOException{return stat(direct);}
                        @Override public InputStream stream(){return read;}
                        @Override public boolean stillSame(DuplicateFinder.Stamp before)throws IOException{
                            return direct.equals(direct.toRealPath())&&before.same(stamp());
                        }
                        @Override public void close()throws IOException{read.close();}
                    };
                }
            };
            DuplicateFinder.Result result=DuplicateFinder.scan(input,folder,NORMAL,()->false);
            check(result.groups.size()==1&&result.groups.get(0).members.size()==3&&result.inaccessible==2,
                    "actual streaming skips symlink leaves and ancestors");
            check(Files.isSymbolicLink(leaf)&&Files.isSymbolicLink(own.resolve("alias"))
                    &&Files.readAllBytes(a).length==Files.readAllBytes(b).length,
                    "read-only scan changed no fixture files or symlinks");
        } finally {
            try(java.util.stream.Stream<Path> children=Files.walk(own)){
                for(Path child:(Iterable<Path>)children.sorted(Collections.reverseOrder())::iterator)Files.deleteIfExists(child);
            }
        }
    }

    private static DuplicateFinder.Stamp stat(Path path)throws IOException{
        Map<String,Object> attrs=Files.readAttributes(path,"unix:dev,ino,nlink,ctime,lastModifiedTime,size",LinkOption.NOFOLLOW_LINKS);
        return new DuplicateFinder.Stamp(attrs.get("dev")+":"+attrs.get("ino"),attrs.get("ctime")+":"+attrs.get("lastModifiedTime"),
                ((Number)attrs.get("size")).longValue(),((Number)attrs.get("nlink")).longValue());
    }
    private static DuplicateFinder.Member member(DuplicateFinder.Group group,FileEntry e){
        for(DuplicateFinder.Member m:group.members)if(m.entry==e)return m;
        throw new AssertionError("missing member");
    }
    private static final class FakeSource implements DuplicateFinder.Access{
        final Map<String,Record> records=new HashMap<>();
        int openHandles,maxHandles;
        FileEntry add(String path,byte[] content,String key,long links,long date){
            records.put(path,new Record(content,key,links));
            return new FileEntry(path,false,content.length,date);
        }
        @Override public boolean eligible(FileEntry e){return e.parent.startsWith("/shared");}
        @Override public DuplicateFinder.ReadFile open(FileEntry e)throws IOException{
            Record r=records.get(e.path());
            if(r==null||r.denied)throw new java.nio.file.AccessDeniedException(e.path());
            openHandles++;maxHandles=Math.max(maxHandles,openHandles);
            return new DuplicateFinder.ReadFile(){
                final InputStream stream=new ByteArrayInputStream(r.data){
                    @Override public synchronized int read(byte[] b,int offset,int length){
                        if(r.onRead!=null)r.onRead.run();
                        return super.read(b,offset,length);
                    }
                };
                @Override public DuplicateFinder.Stamp stamp(){
                    return new DuplicateFinder.Stamp(r.key,String.valueOf(r.revision),r.data.length,r.links);
                }
                @Override public InputStream stream(){return stream;}
                @Override public boolean stillSame(DuplicateFinder.Stamp before){return before.same(stamp());}
                @Override public void close(){openHandles--;}
            };
        }
    }
    private static final class Record{
        byte[] data;
        final String key;
        final long links;
        int revision;
        boolean denied;
        Runnable onRead;
        Record(byte[] data,String key,long links){this.data=data;this.key=key;this.links=links;}
    }
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static String hex(byte[] bytes){
        StringBuilder value=new StringBuilder();
        for(byte b:bytes)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return value.toString();
    }
    private static void check(boolean success,String label){assertions++;if(!success)throw new AssertionError(label);}
}
