package dev.fastsearch.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.TextUtils;

import java.io.IOException;
import java.util.Locale;

/** Worker-rendered slide. No network, process execution, or active document features. */
final class PresentationSlide {
    private static final int MAX_ITEMS=180,MAX_SHAPE_TEXT=1800,MAX_DRAWN_TEXT=16_000,MAX_BITMAPS=20;
    private static final int MAX_IMAGE_BYTES=4*1024*1024, MAX_SOURCE_EDGE=16_384;
    final Picture picture;
    final int index,count;
    final String text;
    final boolean limited;

    private PresentationSlide(Picture picture,int index,int count,String text,boolean limited){
        this.picture=picture;this.index=index;this.count=count;this.text=text==null?"":text;this.limited=limited;
    }

    static PresentationSlide render(OfficePreview.SlidePage source,boolean compact)throws IOException {
        if(source.width<=0||source.height<=0||source.count<1||source.index<0||source.index>=source.count)
            throw new IOException("This slide has invalid dimensions or position.");
        int maximum=compact?760:1440;
        float scale=(float)maximum/Math.max(source.width,source.height);
        int width=Math.max(1,Math.round(source.width*scale)),height=Math.max(1,Math.round(source.height*scale));
        Picture picture=new Picture();
        Canvas canvas=picture.beginRecording(width,height);
        boolean limited=source.limited;
        int pixelBudget=compact?480_000:2_500_000, drawnText=0,bitmaps=0,items=0;
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        RectF box=new RectF();
        try{
            canvas.drawColor(source.background==Color.TRANSPARENT?Color.WHITE:source.background);
            canvas.clipRect(0,0,width,height);
            for(OfficePreview.SlideItem item:source.items){
                PreviewText.cancelled();
                if(++items>MAX_ITEMS){limited=true;break;}
                float x=item.x*scale,y=item.y*scale,w=item.width*scale,h=item.height*scale;
                if(item.width<=0||item.height<=0||x>=width||y>=height||x+w<=0||y+h<=0)continue;
                if(w>width*8f||h>height*8f){limited=true;continue;}
                box.set(x,y,x+w,y+h);
                String geometry=item.geometry==null?"rect":item.geometry;
                if((item.fill>>>24)!=0){
                    paint.setStyle(Paint.Style.FILL);paint.setColor(item.fill);
                    limited|=!drawShape(canvas,box,paint,geometry);
                }
                if(item.image!=null){
                    Bitmap bitmap=null;
                    if(bitmaps<MAX_BITMAPS&&pixelBudget>=128)
                        bitmap=picture(item.image,w,h,pixelBudget,compact);
                    if(bitmap==null)limited=true;
                    else{
                        bitmaps++;
                        pixelBudget-=bitmap.getWidth()*bitmap.getHeight();
                        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);
                        canvas.drawBitmap(bitmap,null,box,paint);
                        // The recorded Picture retains the bitmap. Never recycle while a view may draw it.
                    }
                }
                if((item.stroke>>>24)!=0){
                    paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1,width/1040f));
                    paint.setColor(item.stroke);limited|=!drawShape(canvas,box,paint,geometry);
                }
                String text=item.text;
                if(text!=null&&!text.isEmpty()){
                    int remaining=Math.min(MAX_SHAPE_TEXT,MAX_DRAWN_TEXT-drawnText);
                    if(remaining<=0){limited=true;continue;}
                    if(text.length()>remaining){
                        if(Character.isHighSurrogate(text.charAt(remaining-1)))remaining--;
                        text=text.substring(0,remaining);limited=true;
                    }
                    drawnText+=text.length();
                    limited|=drawText(canvas,item,box,text,scale,width);
                }
            }
        }finally{picture.endRecording();}
        return new PresentationSlide(picture,source.index,source.count,source.text,limited);
    }

    private static boolean drawShape(Canvas canvas,RectF box,Paint paint,String geometry){
        switch(geometry){
            case "rect":case "":canvas.drawRect(box,paint);return true;
            case "roundRect":{
                float radius=Math.min(box.width(),box.height())/7f;
                canvas.drawRoundRect(box,radius,radius,paint);return true;
            }
            case "ellipse":canvas.drawOval(box,paint);return true;
            case "line":canvas.drawLine(box.left,box.top,box.right,box.bottom,paint);return true;
            case "triangle":case "diamond":{
                Path outline=new Path();
                outline.moveTo(box.centerX(),box.top);
                outline.lineTo(box.right,"triangle".equals(geometry)?box.bottom:box.centerY());
                if("diamond".equals(geometry))outline.lineTo(box.centerX(),box.bottom);
                outline.lineTo(box.left,"triangle".equals(geometry)?box.bottom:box.centerY());
                outline.close();canvas.drawPath(outline,paint);return true;
            }
            default:canvas.drawRect(box,paint);return false;
        }
    }

    private static Bitmap picture(byte[] data,float worldWidth,float worldHeight,int pixelBudget,boolean compact){
        if(data.length==0||data.length>MAX_IMAGE_BYTES)return null;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(data,0,data.length,options);
        String mime=options.outMimeType;
        if(!"image/jpeg".equals(mime)&&!"image/png".equals(mime)&&!"image/webp".equals(mime)
                &&!"image/gif".equals(mime)&&!"image/bmp".equals(mime))return null;
        int width=options.outWidth,height=options.outHeight;
        if(width<=0||height<=0||width>MAX_SOURCE_EDGE||height>MAX_SOURCE_EDGE)return null;
        int sample=1,maxEdge=compact?680:1400;
        long maxPixels=Math.min(pixelBudget,compact?260_000:950_000);
        int wanted=(int)Math.min(maxEdge,Math.max(150,Math.max(worldWidth,worldHeight)*1.5f));
        while(ceil(width,sample)>wanted||ceil(height,sample)>wanted
                ||(long)ceil(width,sample)*ceil(height,sample)>maxPixels)sample*=2;
        options.inJustDecodeBounds=false;options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap output=BitmapFactory.decodeByteArray(data,0,data.length,options);
        if(output!=null&&((long)output.getWidth()*output.getHeight()>maxPixels
                ||output.getWidth()>maxEdge||output.getHeight()>maxEdge)){
            output.recycle();return null;
        }
        return output;
    }
    private static int ceil(int value,int factor){return (value+factor-1)/factor;}

    private static boolean drawText(Canvas canvas,OfficePreview.SlideItem item,RectF box,String text,float scale,int slideWidth){
        float margin=Math.max(2,slideWidth/170f);
        int width=Math.min(slideWidth*2,Math.max(0,(int)(box.width()-2*margin)));
        if(width<2||box.height()<margin*2)return true;
        TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF,item.bold?Typeface.BOLD:Typeface.NORMAL));
        paint.setColor((item.textColor>>>24)==0?Color.BLACK:item.textColor);
        float defaultSize=Math.max(10,slideWidth/44f);
        float size=item.fontSize>0?item.fontSize*scale:defaultSize;
        paint.setTextSize(Math.max(3,Math.min(slideWidth*.36f,size)));
        String align=item.align==null?"":item.align.toLowerCase(Locale.ROOT);
        Layout.Alignment alignment="center".equals(align)||"ctr".equals(align)?Layout.Alignment.ALIGN_CENTER
                :"right".equals(align)||"r".equals(align)?Layout.Alignment.ALIGN_OPPOSITE:Layout.Alignment.ALIGN_NORMAL;
        int maxLines=Math.max(1,Math.min(40,(int)((box.height()-2*margin)/Math.max(3,paint.getFontSpacing()))));
        StaticLayout build=StaticLayout.Builder.obtain(text,0,text.length(),paint,width)
                .setAlignment(alignment).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(false).setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build();
        int saved=canvas.save();
        canvas.clipRect(box);
        float freeHeight=Math.max(0,box.height()-2*margin-build.getHeight());
        float vertical="ctr".equals(item.vertical)?freeHeight/2f:"b".equals(item.vertical)?freeHeight:0;
        canvas.translate(box.left+margin,box.top+margin+vertical);
        build.draw(canvas);
        canvas.restoreToCount(saved);
        int last=build.getLineCount()-1;
        return last>=0&&(build.getEllipsisCount(last)>0||build.getLineEnd(last)<text.length());
    }
}
