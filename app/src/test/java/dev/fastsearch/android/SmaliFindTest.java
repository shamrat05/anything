package dev.fastsearch.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public final class SmaliFindTest {
    private static int passed;
    public static void main(String[] args) {
        match(".method Foo()V\n.method foo()I","FOO",8,23);
        match("a[0] == a[0]","[0]",1,9);
        match("[][]","[]",0,2);
        match("abc","",new int[0]);
        match("A\nB a\nb","a\nb",0,4);
        match("a*A-Σςσ","σ",4,5,6);
        match("banana","ana",1);
        match("😀 .method","method",4);
        char[] many = new char[SmaliFind.MAX_TEXT+1];
        Arrays.fill(many,'.');
        int[] hits = SmaliFind.matches(new String(many),".");
        yes(hits.length == SmaliFind.MAX_TEXT && hits[hits.length-1] == SmaliFind.MAX_TEXT-1,"bounded large repeated input");
        yes(SmaliFind.matches(new String(many),new String(many,0,SmaliFind.MAX_QUERY+1)).length==0,"query cap");
        yes(SmaliFind.navigate(1,1,2)==0 && SmaliFind.navigate(0,-1,2)==1,"wraps both directions");
        yes(SmaliFind.navigate(-1,1,2)==0 && SmaliFind.navigate(-1,-1,2)==1 && SmaliFind.navigate(0,1,0)==-1,"empty or first navigation");
        Random random = new Random(42);
        for(int i=0;i<500;i++) {
            String text=random(random,random.nextInt(100)), needle=random(random,1+random.nextInt(9));
            List<Integer> expected=new ArrayList<>();
            for(int at=0; at+needle.length()<=text.length();) {
                if(text.regionMatches(true,at,needle,0,needle.length())) { expected.add(at); at+=needle.length(); }
                else at++;
            }
            yes(Arrays.equals(SmaliFind.matches(text,needle),expected.stream().mapToInt(Integer::intValue).toArray()),"oracle trial " + i);
        }
        System.out.println("PASS " + passed + " Smali find assertions.");
    }
    private static String random(Random r,int count) {
        String source = "aAbB[-] Σσς";
        StringBuilder t=new StringBuilder();
        for(int i=0;i<count;i++)t.append(source.charAt(r.nextInt(source.length())));
        return t.toString();
    }
    private static void match(String text,String query,int... wanted) {
        int[] actual = SmaliFind.matches(text,query);
        yes(Arrays.equals(actual,wanted),query+" expected="+Arrays.toString(wanted)+" actual="+Arrays.toString(actual));
    }
    private static void yes(boolean value,String label) { if(!value)throw new AssertionError(label); passed++; }
}
