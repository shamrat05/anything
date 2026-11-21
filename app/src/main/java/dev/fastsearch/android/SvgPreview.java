package dev.fastsearch.android;

import android.graphics.Picture;
import android.util.Xml;
import com.caverock.androidsvg.SVG;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.zip.GZIPInputStream;
import org.xmlpull.v1.XmlPullParser;

/** Bounded vector-only SVG/SVGZ decoding without external resources or entity expansion. */
final class SvgPreview {
    static final int MAX_BYTES=512*1024;
    static {SVG.setInternalEntitiesEnabled(false);SVG.deregisterExternalFileResolver();}
    static final class Result {
        final Picture picture;final byte[] source;
        Result(Picture picture,byte[] source){this.picture=picture;this.source=source;}
    }
    static Result read(InputStream input)throws Exception{
        PushbackInputStream peek=new PushbackInputStream(input,2);int a=peek.read(),b=peek.read();if(b>=0)peek.unread(b);if(a>=0)peek.unread(a);
        InputStream decoded=a==0x1f&&b==0x8b?new GZIPInputStream(peek):peek;
        ByteArrayOutputStream data=new ByteArrayOutputStream(8192);byte[] buffer=new byte[8192];int count;
        try(InputStream content=decoded){while((count=content.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("Preview cancelled.");
            if(data.size()+count>MAX_BYTES)throw new IOException("This SVG exceeds the 512 KiB preview limit.");data.write(buffer,0,count);}}
        byte[] bytes=data.toByteArray();XmlPullParser xml=Xml.newPullParser();xml.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);xml.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false);xml.setInput(new ByteArrayInputStream(bytes),null);
        int nodes=0,uses=0;boolean root=false;
        for(int token=xml.nextToken();token!=XmlPullParser.END_DOCUMENT;token=xml.nextToken()){
            if(Thread.currentThread().isInterrupted())throw new IOException("Preview cancelled.");
            if(token==XmlPullParser.DOCDECL&&xml.getText().contains("["))throw new IOException("SVG entity declarations are not supported.");
            if(token!=XmlPullParser.START_TAG)continue;
            if(!root){if(!"svg".equals(xml.getName()))throw new IOException("This file is not an SVG image.");root=true;}
            if(++nodes>10000||xml.getDepth()>64)throw new IOException("This SVG is too complex to preview.");
            if("image".equals(xml.getName()))throw new IOException("SVG raster images need another app. Use Open to continue.");
            if("use".equals(xml.getName())&&++uses>256)throw new IOException("This SVG has too many repeated shapes.");
        }
        if(!root)throw new IOException("This SVG is empty.");
        SVG svg=SVG.getFromInputStream(new ByteArrayInputStream(bytes));float ratio=svg.getDocumentAspectRatio();
        if(!(ratio>0)||Float.isInfinite(ratio))ratio=1;
        float declared=svg.getDocumentWidth();int width=declared>0?Math.max(1,Math.min(2048,Math.round(declared))):1024;
        int height=Math.max(1,Math.round(width/ratio));if(height>2048){height=2048;width=Math.max(1,Math.round(height*ratio));}
        Picture picture=svg.renderToPicture(width,height);
        if(picture.getWidth()<=0||picture.getHeight()<=0)throw new IOException("This SVG has no viewable dimensions.");
        return new Result(picture,bytes);
    }
    private SvgPreview(){}
}
