package dev.fastsearch.android;

import dev.fastsearch.core.DuplicateFinder;
import dev.fastsearch.core.FileEntry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static dev.fastsearch.android.DuplicateCleanupPreflight.Status.*;

public final class DuplicateCleanupPreflightTest {
    private static int assertions;

    public static void main(String[] args)throws Exception{
        Files fake=new Files();
        FileEntry first=fake.file("Documents/a.bin",1);
        FileEntry second=fake.file("Downloads/b.bin",1);
        FileEntry third=fake.file("Temp/c.bin",1);
        FileEntry backup=fake.file("Backups/d.bin",1);
        FileEntry hard=fake.file("Downloads/e.bin",2);
        List<DuplicateFinder.Group> groups=DuplicateFinder.scan(Arrays.asList(first,second,third,backup,hard),
                fake,DuplicateFinder.Limits.DEFAULT,()->false).groups;
        check(groups.size()==1&&groups.get(0).members.size()==5,"make an exact observed synthetic duplicate group");
        DuplicateFinder.Group group=groups.get(0);
        DuplicateFinder.Member a=member(group,first), b=member(group,second), c=member(group,third),
                d=member(group,backup), e=member(group,hard);
        DuplicateReview.Choice keepAextraB=new DuplicateReview.Choice(group,a,b),
                               keepAextraC=new DuplicateReview.Choice(group,a,c);
        List<DuplicateReview.Choice> valid=Arrays.asList(keepAextraB,keepAextraC);
        DuplicateCleanupPreflight.VerifyPair real=(choice,stop)->
                DuplicateFinder.revalidate(choice.proof,choice.target,choice.keeper,fake,stop);

        fake.reads.set(0);
        DuplicateCleanupPreflight.Report report=DuplicateCleanupPreflight.check(valid,real,()->false);
        check(status(report,MATCHED_AT_CHECK,MATCHED_AT_CHECK)&&!report.limitReached&&fake.reads.get()==16
                &&report.reservedReadBytes==16,"rehash both sides and account for the keeper being read twice");
        try{report.checks.clear();throw new AssertionError("mutable receipt");}
        catch(UnsupportedOperationException good){check(true,"reports cannot be modified");}

        fake.reads.set(0);
        byte[] oldTarget=fake.content.put(second,new byte[]{1,2,3,0});
        check(status(DuplicateCleanupPreflight.check(valid,real,()->false),STALE_OR_UNAVAILABLE,MATCHED_AT_CHECK),
                "unchanged same-size metadata does not hide target digest mismatch");
        fake.content.put(second,oldTarget);
        byte[] oldKeeper=fake.content.put(first,new byte[]{1,2,3,0});
        check(status(DuplicateCleanupPreflight.check(valid,real,()->false),STALE_OR_UNAVAILABLE,STALE_OR_UNAVAILABLE),
                "changed keeper blocks each selected extra");
        fake.content.put(first,oldKeeper);
        DuplicateFinder.Stamp original=fake.stamps.put(second,new DuplicateFinder.Stamp("different inode","modified",4,1));
        check(status(DuplicateCleanupPreflight.check(valid,real,()->false),STALE_OR_UNAVAILABLE,MATCHED_AT_CHECK),
                "same content at replaced pathname still needs a new review");
        fake.stamps.put(second,original);

        DuplicateCleanupPreflight.VerifyPair none=(choice,stop)->{throw new AssertionError("unexpected file reads");};
        check(status(DuplicateCleanupPreflight.check(Arrays.asList(keepAextraB,new DuplicateReview.Choice(group,b,c)),
                none,()->false),CONFLICTING_SELECTION,CONFLICTING_SELECTION),"keeper selected elsewhere invalidates batch");
        check(status(DuplicateCleanupPreflight.check(Arrays.asList(keepAextraB,keepAextraB),none,()->false),
                CONFLICTING_SELECTION,CONFLICTING_SELECTION),"repeat selection invalidates batch");
        check(status(DuplicateCleanupPreflight.check(Collections.singletonList(new DuplicateReview.Choice(group,a,d)),
                none,()->false),CONFLICTING_SELECTION),"backup targets remain prohibited even when submitted manually");
        check(status(DuplicateCleanupPreflight.check(Collections.singletonList(new DuplicateReview.Choice(group,a,e)),
                none,()->false),CONFLICTING_SELECTION),"multiply-linked target remains prohibited");
        check(status(DuplicateCleanupPreflight.check(Collections.singletonList(new DuplicateReview.Choice(group,d,c)),
                real,()->false),MATCHED_AT_CHECK),"backup choice may remain the surviving keeper");
        check(status(DuplicateCleanupPreflight.check(Collections.singletonList(null),none,()->false),
                CONFLICTING_SELECTION),"invalid immutable snapshot fails closed");

        DuplicateCleanupPreflight.Report limited=DuplicateCleanupPreflight.check(valid,real,()->false,2,9);
        check(status(limited,MATCHED_AT_CHECK,NOT_CHECKED)&&limited.limitReached&&limited.reservedReadBytes==8,
                "budget skip is not a verified candidate");
        DuplicateCleanupPreflight.Report tooMany=DuplicateCleanupPreflight.check(valid,none,()->false,1,100);
        check(tooMany.checks.isEmpty()&&tooMany.limitReached&&tooMany.requested==2&&tooMany.reservedReadBytes==0,
                "row cap stops before running a byte verifier");
        check(status(DuplicateCleanupPreflight.check(valid,(choice,stop)->{throw new IOException("Unreadable");},()->false),
                STALE_OR_UNAVAILABLE,STALE_OR_UNAVAILABLE),"inaccessible files never become matches");
        cancel(()->DuplicateCleanupPreflight.check(valid,none,()->true));
        AtomicBoolean stopping=new AtomicBoolean();
        cancel(()->DuplicateCleanupPreflight.check(valid,(choice,stop)->{stopping.set(true);return true;},stopping::get));
        check(fake.content.size()==5&&Arrays.equals(fake.content.get(second),new byte[]{1,2,3,4}),
                "all tests preserve synthetic files and have no write route through the verifier");
        System.out.println("PASS "+assertions+" read-only duplicate preflight assertions.");
    }

    private static DuplicateFinder.Member member(DuplicateFinder.Group group,FileEntry entry){
        for(DuplicateFinder.Member member:group.members)if(member.entry==entry)return member;
        throw new AssertionError("Missing fixture.");
    }
    private static boolean status(DuplicateCleanupPreflight.Report actual,DuplicateCleanupPreflight.Status...expected){
        List<DuplicateCleanupPreflight.Status> actualStatuses=new ArrayList<>();
        for(DuplicateCleanupPreflight.Check check:actual.checks)actualStatuses.add(check.status);
        return actualStatuses.equals(Arrays.asList(expected));
    }
    private static void cancel(Io action)throws Exception {
        try{action.run();throw new AssertionError("Cancellation was ignored.");}
        catch(InterruptedIOException expected){check(true,"responsive cancellation");}
    }
    interface Io {void run()throws Exception;}
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);assertions++;}

    private static final class Files implements DuplicateFinder.Access {
        final Map<FileEntry,byte[]> content=new HashMap<>();
        final Map<FileEntry,DuplicateFinder.Stamp> stamps=new HashMap<>();
        final AtomicInteger reads=new AtomicInteger();
        FileEntry file(String name,int links){
            byte[] bytes={1,2,3,4};
            FileEntry entry=new FileEntry("/shared/"+name,false,bytes.length,1);
            content.put(entry,bytes);stamps.put(entry,new DuplicateFinder.Stamp(entry.path(),"scan",bytes.length,links));
            return entry;
        }
        public boolean eligible(FileEntry entry){return content.containsKey(entry);}
        public DuplicateFinder.ReadFile open(FileEntry entry){
            final DuplicateFinder.Stamp opened=stamps.get(entry);
            InputStream counted=new ByteArrayInputStream(content.get(entry)){
                @Override public int read(byte[] b,int off,int n){
                    int consumed=super.read(b,off,n);
                    if(consumed>0)reads.addAndGet(consumed);
                    return consumed;
                }
            };
            return new DuplicateFinder.ReadFile(){
                public DuplicateFinder.Stamp stamp(){return opened;}
                public InputStream stream(){return counted;}
                public boolean stillSame(DuplicateFinder.Stamp proof){return opened.same(proof)&&proof.same(stamps.get(entry));}
                public void close(){}
            };
        }
    }
}
