package dev.fastsearch.core;
import java.util.*;
public final class RenameRulesTest {
    private static int checks;
    private static FileEntry file(String path){return new FileEntry(path,false,1,1);}
    private static void check(boolean condition){if(!condition)throw new AssertionError("Check "+checks);checks++;}
    private static void reject(Runnable action){try{action.run();throw new AssertionError("Accepted invalid plan");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args){
        List<FileEntry> files=Arrays.asList(file("/a/one.jpg"),file("/a/two.png"));
        Map<String,String> plan=RenameRules.plan(files,RenameRules.Mode.NUMBER,"Photo ","",9,3,true);
        check(new ArrayList<>(plan.values()).equals(Arrays.asList("Photo 009.jpg","Photo 010.png")));
        check(RenameRules.plan(files,RenameRules.Mode.ADD,"pre-","-post",0,1,true).get("/a/one.jpg").equals("pre-one-post.jpg"));
        check(RenameRules.plan(files,RenameRules.Mode.REPLACE,"o","x",0,1,true).get("/a/one.jpg").equals("xne.jpg"));
        check(RenameRules.plan(files,RenameRules.Mode.ADD,"","!",0,1,false).get("/a/one.jpg").equals("one.jpg!"));
        check(RenameRules.plan(Collections.singletonList(file("/.env")),RenameRules.Mode.ADD,"pre","",0,1,true).get("/.env").equals("pre.env"));
        check(RenameRules.plan(Collections.singletonList(new FileEntry("/dir.ext",true,0,0)),RenameRules.Mode.ADD,"","-new",0,1,true).get("/dir.ext").equals("dir.ext-new"));
        check(RenameRules.plan(Collections.singletonList(file("/a/a.jpg")),RenameRules.Mode.NAME,"b.png","",0,1,true).get("/a/a.jpg").equals("b.png"));
        reject(()->RenameRules.plan(files,RenameRules.Mode.NAME,"same","",0,1,true));
        reject(()->RenameRules.plan(files,RenameRules.Mode.REPLACE,"","",0,1,true));
        reject(()->RenameRules.plan(Arrays.asList(file("/a/a"),file("/a/b")),RenameRules.Mode.REPLACE,"a","b",0,1,true));
        reject(()->RenameRules.plan(Arrays.asList(new FileEntry("/a",true,0,0),file("/a/b")),RenameRules.Mode.ADD,"pre","",0,1,true));
        reject(()->RenameRules.plan(Arrays.asList(file("/a"),file("/a")),RenameRules.Mode.ADD,"pre","",0,1,true));
        reject(()->RenameRules.plan(Collections.emptyList(),RenameRules.Mode.ADD,"a","",0,1,true));
        reject(()->RenameRules.plan(Collections.nCopies(513,file("/a")),RenameRules.Mode.ADD,"a","",0,1,true));
        reject(()->RenameRules.plan(files,RenameRules.Mode.NUMBER,"","",-1,1,true));
        reject(()->RenameRules.plan(files,RenameRules.Mode.NUMBER,"","",0,10,true));
        for(String name:new String[]{"",".","..","a/b","a\0b",String.join("",Collections.nCopies(128,"é"))})reject(()->RenameRules.validate(name));
        RenameRules.validate(String.join("",Collections.nCopies(255,"a")));checks++;
        System.out.println("RenameRulesTest: "+checks+" checks passed");
    }
}
