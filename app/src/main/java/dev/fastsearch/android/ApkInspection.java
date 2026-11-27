package dev.fastsearch.android;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Build;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.security.cert.CertificateFactory;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/** Reads only a prepared, immutable private preview APK. These are facts, not a safety verdict. */
final class ApkInspection {
    private static final String ANDROID="http://schemas.android.com/apk/res/android";
    private static final long MAX_SIGNING_BYTES=128L*1024*1024;
    private static final int MAX_PERMISSIONS=128,MAX_SPLITS=40,MAX_SIGNERS=16;

    static final class Report {
        final String permissions,certificates,nativeABIs,splits,checksum;
        Report(String permissions,String certificates,String nativeABIs,String splits,String checksum){
            this.permissions=permissions;this.certificates=certificates;this.nativeABIs=nativeABIs;
            this.splits=splits;this.checksum=checksum;
        }
    }

    private ApkInspection() {}

    static Report read(Context context,ApkInstallAction.Details apk,File snapshot,FileChannel fd) throws IOException {
        PreviewText.cancelled();
        PackageManager pm=context.getPackageManager();
        String path=snapshot.getAbsolutePath();
        PackageInfo metadata=archive(pm,path,PackageManager.GET_PERMISSIONS,apk.packageName);
        String permissions=permissions(metadata);
        String splits=splits(pm,metadata,apk,path);
        String abis;
        try{
            ApkInspectionFacts.NativeABIs nativeInfo=ApkInspectionFacts.nativeABIs(ZipDirectory.read(fd));
            abis=nativeInfo.names.isEmpty()?(nativeInfo.partial?"None found in the portion inspected.":"No native library entries listed.")
                    :String.join(", ",nativeInfo.names);
            if(nativeInfo.partial)abis+="\nZIP directory scan was limited; other entries may exist.";
        }catch(IOException|RuntimeException invalid){
            PreviewText.cancelled();
            abis="The native library entries could not be fully read.";
        }
        PreviewText.cancelled();
        String signer=signers(pm,apk,path);
        PreviewText.cancelled();
        return new Report(permissions,signer,abis,splits,apk.fileSha256);
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo archive(PackageManager pm,String path,int flags,String packageName){
        try{
            PackageInfo info=pm.getPackageArchiveInfo(path,flags);
            return info!=null&&packageName.equals(info.packageName)?info:null;
        }catch(RuntimeException unavailable){return null;}
    }

    private static String permissions(PackageInfo info){
        if(info==null)return "Android could not read the requested permissions.";
        String[] raw=info.requestedPermissions;
        if(raw==null||raw.length==0)return "No permissions requested in this APK.";
        TreeSet<String> shown=new TreeSet<>();
        boolean partial=raw.length>MAX_PERMISSIONS;
        for(int i=0;i<Math.min(MAX_PERMISSIONS,raw.length);i++){
            String name=clean(raw[i],180);
            if(name==null)partial=true;else shown.add(name);
        }
        String result=raw.length+" reported as requested · "+"This does not mean the app was granted them.";
        if(!shown.isEmpty())result+="\n\n"+String.join("\n",shown);
        return partial?result+"\n\nSome declarations were not shown.":result;
    }

    @SuppressWarnings("deprecation")
    private static String signers(PackageManager pm,ApkInstallAction.Details apk,String path){
        if(apk.size>MAX_SIGNING_BYTES)return "Not checked: signer inspection is limited to APKs up to 128 MiB.";
        PackageInfo info=archive(pm,path,Build.VERSION.SDK_INT>=28
                ?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES,apk.packageName);
        Signature[] signatures=null;
        try {
            if(info!=null)signatures=Build.VERSION.SDK_INT>=28
                    ?(info.signingInfo==null?null:info.signingInfo.getApkContentsSigners()):info.signatures;
        }catch(RuntimeException ignored){ /* Report unavailable below. */ }
        if(signatures==null||signatures.length==0)
            return "Android did not report a signer certificate. The APK may be unsigned, changed or unreadable.";
        Set<String> fingerprints=new LinkedHashSet<>();
        try {
            CertificateFactory factory=CertificateFactory.getInstance("X.509");
            for(int i=0;i<Math.min(signatures.length,MAX_SIGNERS);i++){
                if(Thread.currentThread().isInterrupted())break;
                try{
                    if(signatures[i]==null)continue;
                    byte[] value=signatures[i].toByteArray();
                    if(value.length==0||value.length>64*1024)continue;
                    ByteArrayInputStream source=new ByteArrayInputStream(value);
                    byte[] certificate=factory.generateCertificate(source).getEncoded();
                    if(source.available()!=0)continue;
                    fingerprints.add(ApkInspectionFacts.hex(ApkInspectionFacts.sha256().digest(certificate),true));
                }catch(java.security.cert.CertificateException|RuntimeException invalid){ /* Skip unreadable signer. */ }
            }
        }catch(java.security.cert.CertificateException unavailable){ /* Report unavailable below. */ }
        if(fingerprints.isEmpty())return "Android reported a signer, but its certificate fingerprint could not be read.";
        String available=String.join("\n\n",fingerprints);
        if(signatures.length!=fingerprints.size())available+="\n\nSome reported signer certificates were not individually listed.";
        return available;
    }

    private static String splits(PackageManager pm,PackageInfo info,ApkInstallAction.Details apk,String path){
        LinkedHashSet<String> parts=new LinkedHashSet<>();
        String result=apk.splitRequired?"Android metadata indicates additional split files may be needed."
                :"No split requirement was detected in this APK.";
        if(info==null||info.applicationInfo==null)return result+"\nDetailed split declarations are unavailable.";
        boolean limited=false;
        addNamed(parts,"Platform split",info.splitNames);
        ApplicationInfo application=info.applicationInfo;
        application.sourceDir=path;application.publicSourceDir=path;
        try{
            Resources resources=pm.getResourcesForApplication(application);
            try(XmlResourceParser xml=resources.getAssets().openXmlResourceParser("AndroidManifest.xml")){
                int n=0;
                while(n<12_000&&xml.next()!=XmlPullParser.END_DOCUMENT){
                    n++;
                    if(Thread.currentThread().isInterrupted())break;
                    if(xml.getEventType()!=XmlPullParser.START_TAG)continue;
                    if(xml.getDepth()==1&&"manifest".equals(xml.getName())){
                        add(parts,"Split name",xml.getAttributeValue(null,"split"));
                        String configuration=xml.getAttributeValue(null,"configForSplit");
                        if(configuration==null)configuration=xml.getAttributeValue(ANDROID,"configForSplit");
                        add(parts,"Configuration for",configuration);
                        add(parts,"Required split types",xml.getAttributeValue(ANDROID,"requiredSplitTypes"));
                        add(parts,"Provided split types",xml.getAttributeValue(ANDROID,"splitTypes"));
                    }else if(xml.getDepth()==2&&"uses-split".equals(xml.getName()))
                        add(parts,"Declared dependency",xml.getAttributeValue(ANDROID,"name"));
                }
                limited=n>=12_000;
            }
        }catch(Exception unavailable){
            result+="\nDetailed manifest split declarations are unavailable.";
        }
        if(!parts.isEmpty())result+="\n\n"+String.join("\n",parts);
        if(limited||parts.size()>=MAX_SPLITS)result+="\nSplit declarations were limited.";
        return result;
    }

    private static void addNamed(Set<String> values,String type,String[] names){
        if(names==null)return;
        for(int i=0;i<Math.min(names.length,MAX_SPLITS);i++)add(values,type,names[i]);
    }
    private static void add(Set<String> values,String title,String raw){
        if(values.size()>=MAX_SPLITS)return;
        String value=clean(raw,180);
        if(value!=null)values.add(title+": "+value);
    }
    private static String clean(String raw,int max){
        if(raw==null||raw.length()>max)return null;
        for(int i=0;i<raw.length();i++){
            char c=raw.charAt(i);
            if(Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)return null;
        }
        String value=raw.trim();
        return value.isEmpty()?null:value;
    }
}
