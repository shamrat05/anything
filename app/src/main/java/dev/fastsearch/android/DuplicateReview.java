package dev.fastsearch.android;

import dev.fastsearch.core.DuplicateFinder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Non-mutating, per-scan choices backed by exact duplicate evidence. */
final class DuplicateReview {
    static final class Choice {
        final DuplicateFinder.Group proof;
        final DuplicateFinder.Member keeper,target;
        Choice(DuplicateFinder.Group proof,DuplicateFinder.Member keeper,DuplicateFinder.Member target){
            this.proof=proof;this.keeper=keeper;this.target=target;
        }
    }

    static final class Group {
        final String label;
        final DuplicateFinder.Group proof;
        final List<DuplicateFinder.Member> visible;
        final boolean complete;
        private final Set<DuplicateFinder.Member> visibleSet;
        private final int independentUnprotected;
        private final LinkedHashSet<DuplicateFinder.Member> selected=new LinkedHashSet<>();
        private DuplicateFinder.Member keeper;

        Group(String label,DuplicateFinder.Group proof,int limit){
            this.label=label;this.proof=proof;
            int count=Math.min(proof.members.size(),Math.max(0,limit));
            complete=count==proof.members.size();
            visible=Collections.unmodifiableList(new ArrayList<>(proof.members.subList(0,count)));
            visibleSet=new HashSet<>(visible);
            int available=0;
            for(DuplicateFinder.Member member:visible)if(removableCopy(member))available++;
            independentUnprotected=available;
            keeper=proof.keep;
        }

        DuplicateFinder.Member keeper(){return keeper;}
        boolean includes(DuplicateFinder.Member member){return visibleSet.contains(member);}
        boolean eligible(DuplicateFinder.Member member){
            return complete&&member!=null&&includes(member)&&member!=keeper
                    &&removableCopy(member)&&!member.evidence.objectKey.equals(keeper.evidence.objectKey);
        }

        boolean chooseKeep(DuplicateFinder.Member member){
            if(!complete||member==keeper||!includes(member))return false;
            keeper=member;
            selected.remove(member);
            selected.removeIf(other->!eligible(other));
            return true;
        }

        boolean toggle(DuplicateFinder.Member member){
            if(!eligible(member))return false;
            if(!selected.remove(member))selected.add(member);
            return true;
        }
        boolean isSelected(DuplicateFinder.Member member){return selected.contains(member);}
        int selectedCount(){return selected.size();}
        long selectedBytes(){return multiply(proof.sizePerFile,selected.size());}
        void clear(){selected.clear();}

        int eligibleCount(){return complete?independentUnprotected-(removableCopy(keeper)?1:0):0;}
        private static boolean removableCopy(DuplicateFinder.Member copy){
            return !copy.protectedCopy&&copy.evidence.linkCount==1;
        }
        long possibleBytes(){return multiply(proof.sizePerFile,eligibleCount());}
        void selectExtras(){if(complete)for(DuplicateFinder.Member member:visible)if(eligible(member))selected.add(member);}
    }

    private List<Group> groups=Collections.emptyList();
    void replace(List<Group> next){groups=Collections.unmodifiableList(new ArrayList<>(next));for(Group group:groups)group.clear();}
    boolean active(){return !groups.isEmpty();}
    int groupCount(){return groups.size();}
    boolean matches(Group group){return groups.contains(group);}
    void selectExtras(){for(Group group:groups)group.selectExtras();}
    void selectExtras(java.util.function.Predicate<DuplicateFinder.Member> visible){
        for(Group group:groups)for(DuplicateFinder.Member member:group.visible)
            if(visible.test(member)&&group.eligible(member)&&!group.isSelected(member))group.toggle(member);
    }
    void clear(){for(Group group:groups)group.clear();}
    int selectedCount(){
        int count=0;for(Group group:groups)count+=group.selectedCount();return count;
    }
    int eligibleCount(){
        int count=0;for(Group group:groups)count+=group.eligibleCount();return count;
    }
    long selectedBytes(){
        long bytes=0;for(Group group:groups)bytes=add(bytes,group.selectedBytes());return bytes;
    }
    long possibleBytes(){
        long bytes=0;for(Group group:groups)bytes=add(bytes,group.possibleBytes());return bytes;
    }
    List<Choice> snapshot(){
        List<Choice> immutable=new ArrayList<>();
        for(Group group:groups)for(DuplicateFinder.Member target:group.selected)
            if(group.eligible(target))immutable.add(new Choice(group.proof,group.keeper,target));
        return Collections.unmodifiableList(immutable);
    }
    static long add(long first,long second){return Long.MAX_VALUE-first<second?Long.MAX_VALUE:first+second;}
    static long multiply(long value,int count){return count==0?0:value>Long.MAX_VALUE/count?Long.MAX_VALUE:value*count;}
}
