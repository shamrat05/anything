package dev.fastsearch.android;

import java.util.List;

public final class ArchiveFolderTreeTest {
    private static int checks;
    public static void main(String[] args){
        ArchiveFolderTree tree=new ArchiveFolderTree();
        Object exact=new Object();
        tree.add("pkg/deep/雪.txt",false,exact,true,"8 B");
        tree.add("pkg/empty/",true,null,false,"");
        tree.add("pkg/",true,null,false,"");
        tree.add("README",false,new Object(),true,"0 B");
        tree.add("pkg/deep/Unsupported",false,new Object(),false,"encrypted");
        check(tree.root.visible().size()==2,"only direct children at root");
        check(tree.root.visible().get(0).folder&&"pkg".equals(tree.root.visible().get(0).leaf),"folders first");
        List<ArchiveFolderTree.Node> packageRows=tree.folder("pkg").visible();
        check(packageRows.size()==2&&"deep".equals(packageRows.get(0).leaf),"implicit and explicit folders");
        check(tree.folder("pkg/empty").visible().isEmpty(),"explicit empty directory remains navigable");
        List<ArchiveFolderTree.Node> deepest=tree.folder("pkg/deep").visible();
        ArchiveFolderTree.Node member=deepest.get(1);
        check(!member.folder&&member.entry==exact&&member.enabled&&"pkg/deep/雪.txt".equals(member.path()),"original exact member");
        check(!deepest.get(0).enabled,"unsupported member is not actionable");
        check(tree.folder("pkg/deep/never").path().equals("pkg/deep"),"nearest existing folder restores");
        check(tree.folder("pkg/deep/雪.txt").path().equals("pkg/deep"),"file cannot be current folder");
        for(String invalid:new String[]{"","/abs","../root","safe/../root","./odd","safe//bad","\u202espoof","C:/hello",
                "bad\\path","bad\nname","bad\u0000","run/","\ud800","a/b////"})tree.add(invalid,false,exact,true,"");
        check(tree.omitted&&tree.root.visible().size()==2,"unsafe paths do not create phantom folders");
        ArchiveFolderTree collision=new ArchiveFolderTree();
        collision.add("one/file.txt",false,exact,true,"");
        collision.add("one/file.txt",false,new Object(),true,"");
        collision.add("文件/thing",false,exact,true,"");
        collision.add("文件",false,new Object(),true,"");
        collision.add("name",false,exact,true,"");
        collision.add("name/child",false,exact,true,"");
        collision.add("caf\u00e9/one",false,exact,true,"");
        collision.add("cafe\u0301/two",false,exact,true,"");
        check(collision.omitted&&collision.root.visible().size()==1
                &&collision.folder("one").visible().isEmpty(),"hide duplicates and path or Unicode collisions");
        check(collision.folder("文件").path().isEmpty()&&collision.folder("cafe\u0301").path().isEmpty(),"blocked folders cannot restore");
        ArchiveFolderTree bound=new ArchiveFolderTree();
        for(int i=0;i<25_000;i++)bound.add("root-"+i+"/a/b/c/d/e/f",false,exact,true,"");
        check(bound.bounded&&bound.root.visible().size()==1170,"total nodes capped with no phantom branches");
        StringBuilder deep=new StringBuilder("hello");
        for(int i=0;i<45;i++)deep.append("/a");
        bound.add(deep.toString(),false,exact,true,"");
        check(bound.omitted&&bound.folder("hello").path().isEmpty(),"over-depth name not allocated");
        System.out.println("PASS "+checks+" virtual archive folder assertions.");
    }
    private static void check(boolean pass,String label){if(!pass)throw new AssertionError(label);checks++;}
}
