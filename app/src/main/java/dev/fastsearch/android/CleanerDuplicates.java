package dev.fastsearch.android;

import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import dev.fastsearch.core.DuplicateFinder;
import dev.fastsearch.core.FileEntry;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

/** Normal-permission shared storage only. Never reopens through root or a provider fallback. */
final class CleanerDuplicates {
    private static final class LocalAccess implements DuplicateFinder.Access {
        // Resolve the process user's primary location once, not during the index-row loop.
        final String primary=Environment.getExternalStorageDirectory().getAbsolutePath();
        final String backing=primary.startsWith("/storage/emulated/")
                ? "/data/media/"+new File(primary).getName() : null;
        private boolean within(String path,String root){return path.equals(root)||path.startsWith(root+"/");}
        @Override public boolean eligible(FileEntry entry){
            String path=entry.parent;
            if(within(path,primary))return publicFolder(path,primary.length());
            if(backing!=null&&within(path,backing))return publicFolder(path,backing.length());
            if(!path.startsWith("/storage/"))return false;
            int ending=path.indexOf('/',9);
            if(ending<0)ending=path.length();
            int length=ending-9;
            // Direct mounted FAT/exFAT or adopted volume; never another Android user's tree.
            if(length!=9&&length!=36)return false;
            for(int i=0;i<length;i++){
                char ch=path.charAt(9+i);
                boolean dash=length==9?i==4:i==8||i==13||i==18||i==23;
                if(dash?ch!='-':!(ch>='0'&&ch<='9'||ch>='A'&&ch<='F'||ch>='a'&&ch<='f'))return false;
            }
            return publicFolder(path,ending);
        }
        private boolean publicFolder(String path,int mountEnd){
            if(path.length()==mountEnd)return true;
            int relative=mountEnd+1;
            if(!segment(path,relative,"android"))return true;
            if(!segment(path,relative+8,"media"))return false;
            int firstPackage=relative+14;
            return firstPackage>=path.length()
                    ||!(segment(path,firstPackage,"android")
                        ||segment(path,firstPackage,"com.google.android.providers.media.module")
                        ||path.regionMatches(true,firstPackage,"com.android.",0,12));
        }
        private boolean segment(String path,int start,String token){
            int end=start+token.length();
            return end<=path.length()&&path.regionMatches(true,start,token,0,token.length())
                    &&(end==path.length()||path.charAt(end)=='/');
        }
        @Override public DuplicateFinder.ReadFile open(FileEntry entry)throws IOException{
            if(!eligible(entry))throw new IOException("Outside shared storage.");
            String indexed=entry.path();
            // Root indexing names the shared tree by its backing path. Read only via the current
            // Android user's ordinary FUSE mount. A denied open remains denied; never try the backing file.
            String path=backing!=null&&within(indexed,backing)
                    ? primary+indexed.substring(backing.length()) : indexed;
            try{
                // Reject symlink ancestors and non-regular paths before opening anything.
                if(!path.equals(new File(path).getCanonicalPath())||!OsConstants.S_ISREG(Os.lstat(path).st_mode))
                    throw new IOException("Not a regular direct path.");
                // openLocal adds NOFOLLOW, NONBLOCK and checks the descriptor's direct pathname.
                return new DirectFile(path,SafeFileAccess.openLocal(path));
            }catch(IOException unavailable){throw unavailable;}
            catch(Exception unavailable){throw new IOException("File is not readable as a regular shared-storage file.",unavailable);}
        }
    }

    static DuplicateFinder.Result scan(Iterable<FileEntry> entries,BooleanSupplier cancel)throws IOException{
        return scan(entries,DuplicateFinder.Limits.DEFAULT,cancel);
    }
    static DuplicateFinder.Result scan(Iterable<FileEntry> entries,DuplicateFinder.Limits limits,
                                       BooleanSupplier cancel)throws IOException{
        return DuplicateFinder.scan(entries,new LocalAccess(),limits,cancel);
    }

    static boolean revalidate(DuplicateFinder.Group group,DuplicateFinder.Member target,
                              DuplicateFinder.Member keeper,BooleanSupplier cancel)throws IOException{
        return DuplicateFinder.revalidate(group,target,keeper,new LocalAccess(),cancel);
    }

    /** Background-only. No request for write access, no mutation, never a safe-to-trash token. */
    static DuplicateCleanupPreflight.Report preflight(java.util.List<DuplicateReview.Choice> snapshot,
                                                     BooleanSupplier cancel)throws IOException{
        LocalAccess access=new LocalAccess();
        return DuplicateCleanupPreflight.check(snapshot,(choice,stop)->
                DuplicateFinder.revalidate(choice.proof,choice.target,choice.keeper,access,stop),cancel);
    }

    private static final class DirectFile implements DuplicateFinder.ReadFile{
        final String path;
        final ParcelFileDescriptor descriptor;
        final ParcelFileDescriptor.AutoCloseInputStream input;
        DirectFile(String path,ParcelFileDescriptor descriptor){
            this.path=path;this.descriptor=descriptor;
            input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
        }
        @Override public DuplicateFinder.Stamp stamp()throws IOException{
            try{return of(Os.fstat(descriptor.getFileDescriptor()));}
            catch(ErrnoException unavailable){throw new IOException("File descriptor metadata is unavailable.",unavailable);}
        }
        @Override public InputStream stream(){return input;}
        @Override public boolean stillSame(DuplicateFinder.Stamp before)throws IOException{
            if(!before.same(stamp())||!path.equals(new File(path).getCanonicalPath()))return false;
            try{
                return before.same(of(Os.lstat(path)))
                        &&path.equals(Os.readlink("/proc/self/fd/"+descriptor.getFd()));
            }catch(ErrnoException unavailable){return false;}
        }
        @Override public void close()throws IOException{input.close();}
    }

    private static DuplicateFinder.Stamp of(StructStat stat)throws IOException{
        if(!OsConstants.S_ISREG(stat.st_mode)||stat.st_size<0)throw new IOException("Not a regular file.");
        return new DuplicateFinder.Stamp(stat.st_dev+":"+stat.st_ino,RootFileProvider.identity(stat),stat.st_size,stat.st_nlink);
    }
    private CleanerDuplicates(){}
}
