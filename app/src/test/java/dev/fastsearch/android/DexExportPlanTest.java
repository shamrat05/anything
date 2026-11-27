package dev.fastsearch.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

public final class DexExportPlanTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        yes("Alpha$Child.smali".equals(SmaliExportPlan.suggestedName("dev.example.Alpha$Child")),"normal nested name");
        yes(!SmaliExportPlan.suggestedName("dev.A/\u202EX").contains("/"),"separator sanitized");
        yes(!SmaliExportPlan.suggestedName("dev.A/\u202EX").contains("\u202E"),"bidi marker sanitized");
        yes(SmaliExportPlan.suggestedName("dev.").equals("Class.smali"),"empty leaf");
        char[] longName=new char[1000]; Arrays.fill(longName,'X');
        yes(SmaliExportPlan.suggestedName(new String(longName)).length()==86,"bounds name");
        yes(SmaliExportPlan.smaliName("Alpha (1).smali")&&SmaliExportPlan.smaliName("Alpha.SMALI")&&!SmaliExportPlan.smaliName("Alpha.smali.txt"),"extension");
        yes(SmaliExportPlan.localAuthority(SmaliExportPlan.EXTERNAL)&&SmaliExportPlan.localAuthority(SmaliExportPlan.DOWNLOADS),"known local roots");
        yes(!SmaliExportPlan.localAuthority("com.google.android.apps.docs.storage")&&!SmaliExportPlan.localAuthority("0@"+SmaliExportPlan.EXTERNAL),"cloud and alternate authority rejected");
        String text=".class public Lhello;\n# বাংলা Δ\n";
        byte[] data=SmaliExportPlan.bytes(text);
        yes(text.equals(new String(data,StandardCharsets.UTF_8)),"exact UTF-8 text");
        yes(SmaliExportPlan.digest(SmaliExportPlan.bytes("abc")).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),"digest reference");
        yes(!SmaliExportPlan.digest(data).equals(SmaliExportPlan.digest(SmaliExportPlan.bytes(text+"x"))),"changed preview detected");
        reject(""); reject(null); reject("\uD800");
        char[] large=new char[SmaliExportPlan.MAX_SOURCE_CHARS+1]; Arrays.fill(large,'x'); reject(new String(large));
        yes(DexEntryPicker.ordered(Arrays.asList("classes10.dex","classes2.dex",null,"","classes2.dex","classes.dex","other.dex"))
                .equals(Arrays.asList("classes.dex","classes2.dex","classes10.dex","other.dex")),"numeric distinct entries");
        yes(DexEntryPicker.ordered(Collections.singletonList("DEX")).equals(Collections.singletonList("DEX")),"standalone entry");
        System.out.println("PASS "+checks+" DEX export and picker assertions.");
    }
    private static void reject(String text) throws Exception {
        try { SmaliExportPlan.bytes(text); throw new AssertionError("accepted invalid output"); }
        catch(IOException expected){ checks++; }
    }
    private static void yes(boolean okay,String label) { if(!okay)throw new AssertionError(label); checks++; }
}
