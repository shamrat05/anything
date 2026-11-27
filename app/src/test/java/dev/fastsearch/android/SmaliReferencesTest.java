package dev.fastsearch.android;

import java.util.List;

public final class SmaliReferencesTest {
    private static int assertions;
    public static void main(String[] args) {
        String lines=".class public Ldev/A;\n.super Ljava/lang/Object;\n"
                + "const-string v0, \"Ignored Ldev/Fake; \\\"Ldev/Escaped;\"\n"
                + "# Ldev/Fake;->hidden()V\n"
                + "invoke-static {v0}, Ldev/B;->pick(I[Ljava/lang/String;)I\n"
                + "sget-object v0, Ldev/B;->value:[Ldev/A;\n"
                + "check-cast v0, [Ldev/Inner$Part;\n";
        List<SmaliReferences.Ref> found=SmaliReferences.scan(lines);
        check(found.size()==5,"no string/comment links");
        check(found.get(1).owner.equals("Ljava/lang/Object;"),"external owner retained for lookup");
        for(SmaliReferences.Ref ref:found){
            check(SmaliReferences.at(found,ref.start)==ref && SmaliReferences.at(found,ref.end-1)==ref,"exact click boundaries");
            check(lines.substring(ref.start,ref.end).equals(ref.label()),"in-place original text");
        }
        check(SmaliReferences.at(found,lines.indexOf("Ignored"))==null,"no ordinary text click");
        String destination=".method public pick(Ljava/lang/String;)I\n.end method\n"
                + "  .method public static pick(I[Ljava/lang/String;)I\n.end method\n"
                + ".field public value:Ldev/A;\n  .field public static value:[Ldev/A; = null\n";
        anchor(destination,found.get(2),"pick(I[Ljava/lang/String;)I");
        anchor(destination,found.get(3),"value:[Ldev/A;");
        check(SmaliReferences.declaration(".method static pick(I)I\n.end method\n",found.get(2))==null,"never jumps to wrong overload");
        check(SmaliReferences.declaration(destination,found.get(1))==null,"class-only links don't claim a member");
        check(SmaliReferences.scan("Broken Ldev//Bad; Ldev/Unclosed").isEmpty(),"malformed owner rejection");
        SmaliReferences.Ref init=SmaliReferences.scan("invoke-direct {}, Ldev/B;-><init>()V").get(0);
        anchor(".method public constructor <init>()V\n.end method",init,"<init>()V");
        String odd=Character.toString((char)0x60)+"a name"+(char)0x60;
        anchor(".method public static "+odd+"()V\n",SmaliReferences.scan("Ldev/B;->"+odd+"()V").get(0),odd+"()V");
        StringBuilder large=new StringBuilder();
        for(int i=0;i<4000;i++)large.append("Ldev/A; ");
        check(SmaliReferences.scan(large).size()==2048,"reference cap");
        System.out.println("PASS "+assertions+" reconstructed Smali reference assertions");
    }
    private static void anchor(String text,SmaliReferences.Ref ref,String signature) {
        SmaliReferences.Anchor a=SmaliReferences.declaration(text,ref);
        check(a!=null && text.substring(a.start,a.end).equals(signature),"exact anchor "+signature);
    }
    private static void check(boolean value,String description) {
        if(!value)throw new AssertionError(description);
        assertions++;
    }
}
