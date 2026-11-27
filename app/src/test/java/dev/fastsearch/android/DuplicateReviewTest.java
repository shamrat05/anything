package dev.fastsearch.android;

import dev.fastsearch.core.DuplicateFinder;
import dev.fastsearch.core.FileEntry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class DuplicateReviewTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        byte[] bytes={4,8,15,16,23,42};
        Fixture lookup=new Fixture();
        DuplicateFinder.Group verified=DuplicateFinder.scan(Arrays.asList(
                lookup.add("Vault/source.txt",bytes,1),
                lookup.add("Backups/retained.txt",bytes,1),
                lookup.add("Downloads/photo.bak",bytes,1),
                lookup.add("Downloads/restore-2024.zip",bytes,1),
                lookup.add("Downloads/linked.txt",bytes,2),
                lookup.add("Downloads/source (1).txt",bytes,1),
                lookup.add("Temp/source.txt",bytes,1),
                lookup.add("Downloads/not-a-copy.txt",new byte[]{4,8,15,16,23,43},1)
        ),lookup,DuplicateFinder.Limits.DEFAULT,()->false).groups.get(0);
        DuplicateReview.Group group=new DuplicateReview.Group("Exact match 1",verified,100);
        check(group.complete&&group.visible.size()==7,"complete proof-backed group contains exact bytes only");
        check(group.keeper().entry.parent.endsWith("/Vault"),"protected original chosen by default");
        check(group.eligibleCount()==2&&group.possibleBytes()==2L*bytes.length
                &&group.possibleBytes()==verified.removableBytesEstimate,"only independently reclaimable extras in estimate");
        DuplicateReview state=new DuplicateReview();state.replace(Collections.singletonList(group));
        check(state.snapshot().isEmpty(),"no selection before a deliberate tap");
        state.selectExtras();
        ArrayList<String> selected=new ArrayList<>();
        for(DuplicateReview.Choice choice:state.snapshot()){
            check(choice.proof==verified&&choice.keeper==group.keeper()&&choice.target!=choice.keeper,
                    "immutable selection snapshot carries exact target/keeper evidence");
            selected.add(choice.target.entry.path());
        }
        check(selected.size()==2&&selected.contains("/shared/Downloads/source (1).txt")
                &&selected.contains("/shared/Temp/source.txt"),"auto-selection protects filenames, folders, the keeper, and links");
        check(state.selectedCount()==2&&state.selectedBytes()==2L*bytes.length,"selection logical bytes");
        DuplicateFinder.Member backup=find(group,"photo.bak"), named=find(group,"restore-2024.zip");
        check(backup.protectedCopy&&named.protectedCopy&&!group.toggle(backup)&&!group.toggle(named),
                "backup filename protections also apply to manual choices");
        check(!DuplicateFinder.revalidate(verified,backup,group.keeper(),lookup,()->false),
                "backup name is blocked by fresh proof gate");
        DuplicateFinder.Member nextKeep=find(group,"source (1).txt");
        check(group.chooseKeep(nextKeep)&&group.keeper()==nextKeep&&!group.isSelected(nextKeep)
                &&state.snapshot().get(0).keeper==nextKeep&&state.snapshot().size()==1,
                "keeper override drops that copy from selection and rewires frozen evidence");
        check(!group.toggle(nextKeep)&&!group.toggle(find(group,"retained.txt"))&&!group.toggle(find(group,"linked.txt")),
                "no path to select the keeper, backups or multiple links");
        check(group.possibleBytes()==bytes.length,"estimate follows the current keeper");
        try{state.snapshot().clear();throw new AssertionError("snapshot is mutable");}
        catch(UnsupportedOperationException expected){check(true,"selection snapshot is immutable");}
        state.clear();check(group.keeper()==nextKeep&&state.snapshot().isEmpty(),"clear keeps this scan’s review preference");
        DuplicateReview.Group partial=new DuplicateReview.Group("Exact match 2",verified,2);
        state.replace(Collections.singletonList(partial));state.selectExtras();
        check(!partial.complete&&partial.eligibleCount()==0&&state.snapshot().isEmpty()
                &&!partial.chooseKeep(partial.visible.get(1))&&!partial.toggle(partial.visible.get(1)),
                "clipped groups never produce selections or keeper changes");
        state.replace(Collections.emptyList());
        check(!state.active()&&state.groupCount()==0&&state.snapshot().isEmpty(),"a new scope invalidates review state");
        check(DuplicateReview.add(Long.MAX_VALUE-4,5)==Long.MAX_VALUE
                &&DuplicateReview.multiply(Long.MAX_VALUE/2+1,2)==Long.MAX_VALUE,"estimates saturate without overflow");
        System.out.println("PASS "+checks+" duplicate review and selection assertions.");
    }
    private static DuplicateFinder.Member find(DuplicateReview.Group group,String name){
        for(DuplicateFinder.Member member:group.visible)if(member.entry.name.equals(name))return member;
        throw new AssertionError("Missing "+name);
    }
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    private static final class Fixture implements DuplicateFinder.Access{
        final Map<FileEntry,byte[]> contents=new HashMap<>();
        final Map<FileEntry,DuplicateFinder.Stamp> identities=new HashMap<>();
        FileEntry add(String name,byte[] data,int links){
            FileEntry entry=new FileEntry("/shared/"+name,false,data.length,1);
            contents.put(entry,data);identities.put(entry,new DuplicateFinder.Stamp(entry.path(),"unchanged",data.length,links));
            return entry;
        }
        @Override public boolean eligible(FileEntry entry){return contents.containsKey(entry);}
        @Override public DuplicateFinder.ReadFile open(FileEntry entry){
            DuplicateFinder.Stamp identity=identities.get(entry);
            ByteArrayInputStream input=new ByteArrayInputStream(contents.get(entry));
            return new DuplicateFinder.ReadFile(){
                @Override public DuplicateFinder.Stamp stamp(){return identity;}
                @Override public InputStream stream(){return input;}
                @Override public boolean stillSame(DuplicateFinder.Stamp before){return identity.same(before);}
                @Override public void close(){}
            };
        }
    }
}
