package dev.fastsearch.android;

import dev.fastsearch.core.DuplicateFinder;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** A fresh read-only review, not permission or assurance that a later write would be safe. */
final class DuplicateCleanupPreflight {
    static final int MAX_PAIRS=32;
    static final long MAX_REVIEW_BYTES=128L*1024*1024;

    @FunctionalInterface
    interface VerifyPair {
        boolean matches(DuplicateReview.Choice choice,BooleanSupplier cancelled)throws IOException;
    }

    enum Status { MATCHED_AT_CHECK, STALE_OR_UNAVAILABLE, NOT_CHECKED, CONFLICTING_SELECTION }

    static final class Check {
        final DuplicateReview.Choice choice;
        final Status status;
        Check(DuplicateReview.Choice choice,Status status){this.choice=choice;this.status=status;}
    }

    static final class Report {
        final int requested;
        final List<Check> checks;
        final boolean limitReached;
        final long reservedReadBytes;
        Report(int requested,List<Check> checks,boolean limitReached,long reservedReadBytes){
            this.requested=requested;
            this.checks=Collections.unmodifiableList(new ArrayList<>(checks));
            this.limitReached=limitReached;
            this.reservedReadBytes=reservedReadBytes;
        }
    }

    static Report check(List<DuplicateReview.Choice> snapshot,VerifyPair verify,
                        BooleanSupplier cancelled)throws IOException{
        return check(snapshot,verify,cancelled,MAX_PAIRS,MAX_REVIEW_BYTES);
    }

    static Report check(List<DuplicateReview.Choice> snapshot,VerifyPair verify,BooleanSupplier cancelled,
                        int maxPairs,long maxBytes)throws IOException{
        if(maxPairs<0||maxPairs>MAX_PAIRS||maxBytes<0||maxBytes>MAX_REVIEW_BYTES)
            throw new IllegalArgumentException("Review budget must remain bounded.");
        stop(cancelled);
        int count=snapshot.size();
        if(count>maxPairs)return new Report(count,Collections.emptyList(),true,0);
        List<DuplicateReview.Choice> choices=new ArrayList<>(snapshot);
        Set<String> targets=new HashSet<>(),targetPaths=new HashSet<>(),keepers=new HashSet<>(),keeperPaths=new HashSet<>();
        boolean invalid=false;
        for(DuplicateReview.Choice choice:choices){
            stop(cancelled);
            if(!hasProof(choice)){invalid=true;continue;}
            String target=choice.target.evidence.objectKey, path=choice.target.entry.path();
            invalid|=!targets.add(target);
            invalid|=!targetPaths.add(path);
            keepers.add(choice.keeper.evidence.objectKey);
            keeperPaths.add(choice.keeper.entry.path());
        }
        List<Check> results=new ArrayList<>(count);
        if(invalid||!Collections.disjoint(targets,keepers)||!Collections.disjoint(targetPaths,keeperPaths)){
            for(DuplicateReview.Choice choice:choices)results.add(new Check(choice,Status.CONFLICTING_SELECTION));
            return new Report(count,results,false,0);
        }
        long reserved=0;
        boolean capped=false;
        for(DuplicateReview.Choice choice:choices){
            stop(cancelled);
            // Both exact proofs may stream the full file. Double using a budget check first.
            long size=choice.proof.sizePerFile;
            if(size>(maxBytes-reserved)/2){
                results.add(new Check(choice,Status.NOT_CHECKED));capped=true;continue;
            }
            reserved+=size*2;
            boolean same=false;
            try{same=verify.matches(choice,cancelled);}
            catch(InterruptedIOException interrupted){throw interrupted;}
            catch(IOException|SecurityException unavailable){/* report unavailable without leaking file paths */}
            stop(cancelled);
            results.add(new Check(choice,same?Status.MATCHED_AT_CHECK:Status.STALE_OR_UNAVAILABLE));
        }
        return new Report(count,results,capped,reserved);
    }

    private static boolean hasProof(DuplicateReview.Choice choice){
        if(choice==null||choice.proof==null||choice.target==null||choice.keeper==null)return false;
        DuplicateFinder.Member target=choice.target,keeper=choice.keeper;
        return target!=keeper&&!target.protectedCopy&&target.evidence.linkCount==1&&keeper.evidence.linkCount>=1
                &&!target.entry.directory&&!keeper.entry.directory
                &&!target.evidence.objectKey.equals(keeper.evidence.objectKey)
                &&!target.entry.pathEquals(keeper.entry.path())
                &&choice.proof.sizePerFile>0
                &&choice.proof.sizePerFile==target.evidence.size&&choice.proof.sizePerFile==keeper.evidence.size
                &&choice.proof.members.contains(target)&&choice.proof.members.contains(keeper);
    }

    private static void stop(BooleanSupplier cancelled)throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())
            throw new InterruptedIOException("The duplicate recheck was cancelled.");
    }

    private DuplicateCleanupPreflight(){}
}
