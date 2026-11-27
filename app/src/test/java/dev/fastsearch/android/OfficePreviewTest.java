package dev.fastsearch.android;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class OfficePreviewTest {
    private static int checks;
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String S="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String P="http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String A="http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String R="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String REL="http://schemas.openxmlformats.org/package/2006/relationships";

    public static void main(String[] args) throws Exception {
        check(OfficePreview.format("letter.DOCX")==OfficePreview.Format.WORD,"DOCX case independent");
        check(OfficePreview.format("macro.docm")==null&&OfficePreview.format("early.xls")==null,"legacy and macro formats aren't interpreted");
        check(OfficePreview.mime(OfficePreview.Format.SHEET).endsWith("spreadsheetml.sheet"),"keep true spreadsheet MIME");
        word(); sheets(); worksheetGrids(); slides(); slidePages(); limits();
        System.out.println("PASS "+checks+" read-only Office checks.");
    }

    private static void word() throws Exception {
        Map<String,byte[]> zip = entries("word/document.xml", word("<w:p><w:r><w:t>Forecast &amp; results</w:t></w:r><w:r><w:tab/><w:t>June</w:t></w:r></w:p>"
                +"<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Alpha</w:t></w:r></w:p></w:tc><w:tc><w:p/></w:tc>"
                +"<w:tc><w:p><w:r><w:t>Gamma</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"));
        zip.put("word/vbaProject.bin", "Do not run".getBytes(StandardCharsets.UTF_8));
        OfficePreview.Result result=read(zip,OfficePreview.Format.WORD);
        check(!result.limited&&result.sections.size()==1,"Word main part");
        check(result.sections.get(0).text.equals("Forecast & results\tJune\nAlpha  │  ·  │  Gamma"),"preserve plain text and empty table cells");
        check(!result.sections.get(0).text.contains("Do not run"),"never read macro resources");
        byte[] data=word("<w:p><w:r><w:t>বাংলা text</w:t></w:r></w:p>").getBytes(StandardCharsets.UTF_16LE);
        byte[] bom=new byte[data.length+2];bom[0]=(byte)255;bom[1]=(byte)254;System.arraycopy(data,0,bom,2,data.length);
        zip.put("word/document.xml",bom);
        check(read(zip,OfficePreview.Format.WORD).sections.get(0).text.equals("বাংলা text"),"UTF-16 BOM and Bangla survive");
    }

    private static void sheets() throws Exception {
        Map<String,byte[]> zip=entries("xl/workbook.xml","<workbook xmlns=\""+S+"\" xmlns:r=\""+R+"\"><sheets>"
                +"<sheet name=\"Summary\" r:id=\"r7\"/><sheet name=\"Private\" state=\"hidden\" r:id=\"r8\"/>"
                +"<sheet name=\"External\" r:id=\"r9\"/></sheets></workbook>");
        zip.putAll(entries("xl/_rels/workbook.xml.rels",rels(rel("r7","worksheet","/xl/worksheets/results.xml","")
                +rel("r8","worksheet","worksheets/hidden.xml","")+rel("r9","worksheet","https://example.invalid/test","External"))));
        zip.putAll(entries("xl/sharedStrings.xml","<sst xmlns=\""+S+"\"><si><r><t>Unit</t></r><r><t> sales</t></r></si></sst>"));
        zip.putAll(entries("xl/worksheets/hidden.xml","<worksheet xmlns=\""+S+"\"><sheetData><row><c t=\"inlineStr\"><is><t>Hidden data</t></is></c></row></sheetData></worksheet>"));
        zip.putAll(entries("xl/worksheets/results.xml","<worksheet xmlns=\""+S+"\"><sheetData>"
                +"<row r=\"1000000\"><c r=\"A1000000\" t=\"s\"><v>0</v></c><c r=\"D1000000\"><f>1+2</f><v>3</v></c>"
                +"<c r=\"E1000000\"><f>8+9</f></c><c r=\"L1000000\" t=\"b\"><v>1</v></c><c r=\"XFD1000000\"><v>999</v></c></row>"
                +"<row r=\"1000001\"><c r=\"B1000001\" t=\"inlineStr\"><is><t>Inline</t></is></c></row></sheetData></worksheet>"));
        OfficePreview.Result result=read(zip,OfficePreview.Format.SHEET);
        check(result.limited&&result.sections.size()==1,"hidden and external worksheets excluded");
        check(result.sections.get(0).title.equals("Summary"),"worksheet name comes from manifest");
        String body=result.sections.get(0).text;
        check(body.startsWith("1000000   A Unit sales")&&body.contains("1000001   B Inline"),"sparse and inline values without dense allocation");
        check(body.contains("D 3")&&body.contains("E [formula without saved value]")&&!body.contains("1+2")&&!body.contains("8+9"),"saved formula value only");
        check(body.contains("L TRUE")&&!body.contains("999")&&!body.contains("Hidden data"),"bounded columns and hidden sheet isolation");
    }

    private static void worksheetGrids() throws Exception {
        Map<String,byte[]> parts=entries("xl/workbook.xml","<workbook xmlns='"+S+"' xmlns:r='"+R+"'><sheets>"
                +"<sheet name='Private' state='veryHidden' r:id='private'/><sheet name='Overview' r:id='summary'/>"
                +"<sheet name='Foreign' r:id='foreign'/><sheet name='Broken' r:id='broken'/>"
                +"<sheet name='Details' r:id='details'/></sheets></workbook>");
        parts.putAll(entries("xl/_rels/workbook.xml.rels",rels(rel("private","worksheet","worksheets/private.xml","")
                +rel("summary","worksheet","/xl/worksheets/summary.xml","")
                +rel("foreign","worksheet","https://example.invalid/spreadsheet.xml","External")
                +rel("broken","worksheet","worksheets/broken.xml","")
                +rel("details","worksheet","worksheets/detail.xml",""))));
        parts.putAll(entries("xl/sharedStrings.xml","<sst xmlns='"+S+"'><si><r><t>Shared</t></r>"
                +"<rPh><t>Not a cell value</t></rPh><r><t> বাংলা</t></r></si></sst>"));
        parts.putAll(entries("xl/worksheets/private.xml",grid("<row><c><v>private data</v></c></row>")));
        parts.putAll(entries("xl/worksheets/broken.xml","<!DOCTYPE worksheet SYSTEM 'https://example.invalid/no'>"+grid("")));
        parts.putAll(entries("xl/vbaProject.bin","Never opened"));
        parts.putAll(entries("xl/worksheets/summary.xml",grid(
                "<row r='2'><c r='A2' t='s'><v>0</v></c><c r='C2' t='inlineStr'><is><t xml:space='preserve'> =literal </t></is></c>"
                +"<c r='E2'><f>Not readable or executed</f><v>19.5</v></c><c r='F2'><f>NO CACHE</f></c>"
                +"<c r='H2' t='b'><v>1</v></c><c r='K2' t='e'><v>#DIV/0!</v></c>"
                +"<c r='P2' t='str'><v>https://example.invalid/literal</v></c><c r='Q2'><v>too far</v></c></row>"
                +"<row r='4' hidden='1'><c r='A4'><v>hidden row</v></c></row>"
                +"<row r='20'><c r='B20' t='inlineStr'><is><r><t>Leading</t></r><rPh><t>Phonetic</t></rPh>"
                +"<r><t>&#10;trailing</t></r></is></c><c r='D21'><v>wrong row</v></c>"
                +"<c r='F20' t='s'><v>99</v></c></row>"
                +"<row><c r='A1000001'><v>0</v></c><c><v>2</v></c></row>")));
        StringBuilder detail=new StringBuilder();
        for(int row=1;row<=85;row++)detail.append("<row r='").append(row).append("'><c r='A").append(row)
                .append("' t='inlineStr'><is><t>").append(row==1?repeat("a",159)+"★"+repeat("b",621):"value")
                .append("</t></is></c></row>");
        parts.putAll(entries("xl/worksheets/detail.xml",grid(detail.toString())));
        OfficePreview.Worksheet full=worksheet(parts,0,false);
        check(full.names.equals(java.util.Arrays.asList("Overview","Broken","Details")),"selector preserves workbook order excluding hidden and external sheets");
        check(full.title().equals("Overview")&&full.index==0,"starts in first allowed worksheet");
        check(full.rowNumbers.equals(java.util.Arrays.asList(2,20,1_000_001)),"worksheet row labels preserve sparse coordinates");
        check(full.rows.get(0).get(0).equals("Shared বাংলা"),"shared rich strings preserve scripts and skip phonetic guides");
        check(full.rows.get(0).get(1).isEmpty()&&full.rows.get(0).get(2).equals(" =literal "),"blank columns and literal inline values");
        check(full.rows.get(0).get(4).equals("19.5")&&full.rows.get(0).get(5).equals("[no saved value]"),"only saved formula result is read");
        check(full.rows.get(0).get(7).equals("TRUE")&&full.rows.get(0).get(10).equals("#DIV/0!"),"boolean and error cell values");
        check(full.columns==16&&full.rows.get(0).get(15).equals("https://example.invalid/literal"),"last allowed column and literal URI");
        check(full.rows.get(1).get(1).equals("Leading\ntrailing")&&full.rows.get(1).get(3).isEmpty(),
                "parse rich inline strings and reject mismatched coordinates");
        check(full.rows.get(1).get(5).equals("[text outside preview]")&&full.rows.get(2).get(1).equals("2"),"unavailable string slot and implicit next column");
        check(full.summary.contains("20   B Leading trailing")&&!full.summary.contains("Not readable or executed")
                &&!full.summary.contains("hidden row")&&!full.summary.contains("too far")&&full.limited,"bounded flat copy text carries no hidden or executable content");
        OfficePreview.Worksheet quick=worksheet(parts,0,true);
        check(quick.title().equals(full.title())&&quick.columns==6&&quick.rows.get(0).size()==6&&quick.summary.isEmpty(),"quick grid reuses worksheet reader with compact caps");
        OfficePreview.Worksheet switched=worksheet(parts,2,false);
        check(switched.title().equals("Details")&&switched.rowNumbers.get(79)==80&&switched.limited,"switch worksheets lazily and cap full row count");
        check(switched.rows.get(0).get(0).length()==640,"cap stored characters per full cell");
        OfficePreview.Worksheet shortGrid=worksheet(parts,408,true);
        check(shortGrid.title().equals("Details")&&shortGrid.rows.size()==12&&shortGrid.rows.get(0).get(0).length()==160,
                "clamp selected index and cap compact rows and characters");
        check(shortGrid.rows.get(0).get(0).endsWith("★"),"trim text at safe boundary");
        try{worksheet(parts,1,false);throw new AssertionError("DTD allowed in selected sheet");}
        catch(IOException expected){checks++;}
        parts.putAll(entries("xl/worksheets/broken.xml",grid("")));
        OfficePreview.Worksheet empty=worksheet(parts,1,false);
        check(empty.columns==0&&empty.rows.isEmpty()&&empty.summary.isEmpty(),"empty worksheet is a valid selectable page");
        try{worksheet(entries("xl/workbook.xml","<workbook xmlns='"+S+"'/>"),0,true);throw new AssertionError("broken workbook accepted");}
        catch(IOException expected){checks++;}
    }

    private static String grid(String rows){return "<worksheet xmlns='"+S+"'><sheetData>"+rows+"</sheetData></worksheet>";}
    private static OfficePreview.Worksheet worksheet(Map<String,byte[]> parts,int index,boolean compact) throws IOException {
        Path temp=zip(parts);
        try(FileChannel file=FileChannel.open(temp,StandardOpenOption.READ)){return OfficePreview.readWorksheet(file,index,compact);}
        finally{Files.delete(temp);}
    }

    private static void slides() throws Exception {
        Map<String,byte[]> zip=entries("ppt/presentation.xml","<p:presentation xmlns:p=\""+P+"\" xmlns:r=\""+R+"\">"
                +"<p:sldIdLst><p:sldId id=\"290\" r:id=\"b\"/><p:sldId id=\"291\" r:id=\"a\"/></p:sldIdLst></p:presentation>");
        zip.putAll(entries("ppt/_rels/presentation.xml.rels",rels(rel("a","slide","slides/slide1.xml","")+rel("b","slide","slides/slide22.xml",""))));
        zip.putAll(entries("ppt/slides/slide1.xml",slide("Second", "Next steps")));
        zip.putAll(entries("ppt/slides/slide22.xml",slide("First", "Status summary")));
        OfficePreview.Result result=read(zip,OfficePreview.Format.SLIDES);
        check(!result.limited&&result.sections.size()==2,"two slides");
        check(result.sections.get(0).title.equals("Slide 1")&&result.sections.get(0).text.equals("First\nStatus summary")
                &&result.sections.get(1).text.equals("Second\nNext steps"),"presentation order instead of ZIP order");
    }

    private static void slidePages() throws IOException {
        Map<String,byte[]> parts=entries("ppt/presentation.xml","<p:presentation xmlns:p=\""+P+"\" xmlns:r=\""+R+"\">"
                +"<p:sldIdLst><p:sldId r:id=\"mine\"/><p:sldId r:id=\"bad\"/></p:sldIdLst>"
                +"<p:sldSz cx=\"9000000\" cy=\"4000000\"/></p:presentation>");
        parts.putAll(entries("ppt/_rels/presentation.xml.rels",rels(
                rel("mine","slide","./slides/slide1.xml","")+rel("bad","slide","/ppt/slides/slide2.xml",""))));
        String picture="<p:pic><p:blipFill><a:blip r:embed=\"asset\"/></p:blipFill><p:spPr>"
                +"<a:xfrm><a:off x=\"20\" y=\"30\"/><a:ext cx=\"400000\" cy=\"500000\"/></a:xfrm></p:spPr></p:pic>";
        String imageOnly="<p:sld xmlns:p=\""+P+"\" xmlns:a=\""+A+"\" xmlns:r=\""+R+"\"><p:cSld><p:spTree>"
                +picture+"</p:spTree></p:cSld></p:sld>";
        parts.putAll(entries("ppt/slides/slide1.xml",imageOnly));
        parts.putAll(entries("ppt/slides/slide2.xml","<!DOCTYPE sld><sld/>"));
        parts.putAll(entries("ppt/slides/_rels/slide1.xml.rels",rels(rel("asset","image","../media/fake.png",""))));
        byte[] pngMagic={(byte)0x89,'P','N','G',13,10,26,10};
        parts.put("ppt/media/fake.png",pngMagic);
        OfficePreview.SlidePage imagePage=page(parts,0);
        check(imagePage.count==2&&imagePage.width==9_000_000&&imagePage.height==4_000_000,
                "image-only deck reads only selected page, not next corrupt part");
        check(imagePage.items.size()==1&&imagePage.items.get(0).image.length==pngMagic.length
                &&imagePage.items.get(0).x==20&&imagePage.text.isEmpty(),"image-only slide exposes bounded PNG");
        try{page(parts,1);throw new AssertionError("unsafe DTD accepted");}catch(IOException expected){checks++;}
        parts.putAll(entries("ppt/slides/_rels/slide1.xml.rels",rels(rel("asset","image","https://example.invalid/out.png","External"))));
        OfficePreview.SlidePage external=page(parts,0);
        check(external.items.isEmpty()&&external.limited,"external image ignored");
        parts.putAll(entries("ppt/slides/_rels/slide1.xml.rels",rels(rel("asset","image","../../ppt/media/fake.png",""))));
        check(page(parts,0).items.isEmpty(),"part relationship never leaves ppt root");
        parts.putAll(entries("ppt/slides/_rels/slide1.xml.rels",rels(rel("asset","image","../media/%66ake.png",""))));
        check(page(parts,0).items.isEmpty(),"ambiguous percent-encoded paths rejected");
        parts.putAll(entries("ppt/slides/_rels/slide1.xml.rels",rels(rel("asset","image","../media/fake.png","")
                +rel("asset","image","/ppt/media/fake.png",""))));
        check(page(parts,0).items.isEmpty(),"ambiguous duplicate image reference not followed");
        parts.putAll(entries("ppt/slides/_rels/slide1.xml.rels",rels(rel("asset","image","../media/fake.png",""))));
        parts.put("ppt/media/fake.png",new byte[2*1024*1024+1]);
        check(page(parts,0).items.isEmpty(),"over-budget embedded image ignored");
        parts.putAll(entries("ppt/slides/slide1.xml","<p:sld xmlns:p=\""+P+"\" xmlns:a=\""+A+"\"><p:cSld><p:bg><p:bgPr>"
                +"<a:solidFill><a:srgbClr val=\"F4F6FB\"/></a:solidFill></p:bgPr></p:bg><p:spTree><p:sp>"
                +"<p:spPr><a:xfrm><a:off x=\"685800\" y=\"5029200\"/><a:ext cx=\"5029200\" cy=\"457200\"/></a:xfrm>"
                +"<a:prstGeom prst=\"roundRect\"/><a:solidFill><a:schemeClr val=\"accent2\"/></a:solidFill></p:spPr>"
                +"<p:txBody><a:bodyPr anchor=\"ctr\"/><a:p><a:pPr algn=\"ctr\"/><a:r>"
                +"<a:rPr sz=\"2000\" b=\"1\"><a:solidFill><a:srgbClr val=\"29425C\"/></a:solidFill></a:rPr>"
                +"<a:t>Coordinate check: Team A</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>"));
        parts.putAll(entries("ppt/slides/_rels/slide1.xml.rels",rels(rel("layout","slideLayout","../slideLayouts/slideLayout1.xml",""))));
        parts.putAll(entries("ppt/slideLayouts/slideLayout1.xml","<p:sldLayout xmlns:p=\""+P+"\"/>"));
        parts.putAll(entries("ppt/slideLayouts/_rels/slideLayout1.xml.rels",rels(rel("master","slideMaster","../slideMasters/slideMaster1.xml",""))));
        parts.putAll(entries("ppt/slideMasters/slideMaster1.xml","<p:sldMaster xmlns:p=\""+P+"\"/>"));
        parts.putAll(entries("ppt/slideMasters/_rels/slideMaster1.xml.rels",rels(rel("theme","theme","../theme/theme1.xml",""))));
        parts.putAll(entries("ppt/theme/theme1.xml","<a:theme xmlns:a=\""+A+"\"><a:themeElements><a:clrScheme>"
                +"<a:accent2><a:srgbClr val=\"C0504D\"/></a:accent2></a:clrScheme></a:themeElements></a:theme>"));
        OfficePreview.SlidePage visual=page(parts,0);
        check(visual.background==0xfff4f6fb&&visual.items.size()==1&&!visual.limited,"visual background and one display shape");
        OfficePreview.SlideItem item=visual.items.get(0);
        check(item.x==685800&&item.y==5029200&&item.width==5029200&&item.height==457200,"visual layout geometry");
        check("roundRect".equals(item.geometry)&&item.fill==0xffc0504d&&item.textColor==0xff29425c,
                "render shape with its actual authored theme color");
        check(item.fontSize==254000&&item.bold&&"ctr".equals(item.align)&&"ctr".equals(item.vertical)
                &&"Coordinate check: Team A".equals(item.text),"literal label with approximate text styling");
    }
    private static OfficePreview.SlidePage page(Map<String,byte[]> parts,int index) throws IOException {
        Path temp=zip(parts);
        try(FileChannel file=FileChannel.open(temp,StandardOpenOption.READ)){return OfficePreview.readSlide(file,index);}
        finally{Files.delete(temp);}
    }

    private static void limits() throws Exception {
        reject(entries("word/document.xml","<!DOCTYPE w:document>"+word("<w:p/>")),OfficePreview.Format.WORD,"reject DTD");
        reject(entries("word/document.xml","<!--"+repeat("x",4091)+"--><!DOCTYPE w:document>"+word("<w:p/>")),OfficePreview.Format.WORD,"reject DTD around chunk boundary");
        reject(entries("word/document.xml",word(repeat("<w:p>",80)+repeat("</w:p>",80))),OfficePreview.Format.WORD,"reject deep XML");
        reject(entries("word/document.xml",word("<w:p><w:r><w:t>"+repeat("z",OfficePreview.MAX_PART_BYTES)+"</w:t></w:r></w:p>")),OfficePreview.Format.WORD,"reject oversized XML part");
        reject(entries("word/document.xml","<w:document xmlns:w=\"https://example.invalid\">text</w:document>"),OfficePreview.Format.WORD,"reject wrong document namespace");
        reject(entries("word/document.xml","<w:document xmlns:w=\""+W+"\"><w:body><w:p></w:body></w:document>"),OfficePreview.Format.WORD,"reject ill-formed XML");
        reject(entries("word/not_document.xml",word("<w:p/>")),OfficePreview.Format.WORD,"require expected part");
        Map<String,byte[]> numerous=entries("word/document.xml",word("<w:p/>"));
        for(int i=0;i<ZipDirectory.MAX_ENTRIES;i++)numerous.put("unread-"+i,new byte[0]);
        reject(numerous,OfficePreview.Format.WORD,"reject oversized central directory entries");
        OfficePreview.Result clipped=read(entries("word/document.xml",word(repeat("<w:p><w:r><w:t>"+repeat("a",700)+"</w:t></w:r></w:p>",100))),OfficePreview.Format.WORD);
        check(clipped.limited&&clipped.sections.get(0).text.length()<=OfficePreview.MAX_OUTPUT,"character cap produces partial summary");
        Path temp=zip(entries("word/document.xml",word("<w:p/>")));
        try {
            byte[] all=Files.readAllBytes(temp);
            for(int i=0;i<all.length-20;i++) {
                if((all[i]&255)==0x50&&(all[i+1]&255)==0x4b&&(all[i+2]&255)==1&&(all[i+3]&255)==2) {
                    all[i+16]^=1;
                    break;
                }
            }
            Files.write(temp,all);
            try(FileChannel ch=FileChannel.open(temp,StandardOpenOption.READ)){
                try{OfficePreview.read(ch,OfficePreview.Format.WORD);throw new AssertionError("wrong CRC accepted");}
                catch(IOException expected){checks++;}
            }
        } finally { Files.delete(temp); }
        Thread.currentThread().interrupt();
        try {
            reject(entries("word/document.xml",word("<w:p/>")),OfficePreview.Format.WORD,"stop on interruption");
            check(Thread.currentThread().isInterrupted(),"keep interruption set");
        } finally { Thread.interrupted(); }
    }

    private static String word(String body) { return "<w:document xmlns:w=\""+W+"\"><w:body>"+body+"</w:body></w:document>"; }
    private static String slide(String first,String second) {
        return "<p:sld xmlns:p=\""+P+"\" xmlns:a=\""+A+"\"><p:cSld><p:spTree><p:sp><p:txBody>"
                +"<a:p><a:r><a:t>"+first+"</a:t></a:r></a:p><a:p><a:r><a:t>"+second+"</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>";
    }
    private static String rels(String body) { return "<Relationships xmlns=\""+REL+"\">"+body+"</Relationships>"; }
    private static String rel(String id,String type,String target,String mode) {
        return "<Relationship Id=\""+id+"\" Type=\""+R+"/"+type+"\" Target=\""+target+"\""+(mode.isEmpty()?"":" TargetMode=\""+mode+"\"")+"/>";
    }
    private static String repeat(String s,int n) { StringBuilder b=new StringBuilder(s.length()*n); for(int i=0;i<n;i++)b.append(s); return b.toString(); }
    private static Map<String,byte[]> entries(String name,String xml) {
        Map<String,byte[]> result=new LinkedHashMap<>();result.put(name,xml.getBytes(StandardCharsets.UTF_8)); return result;
    }
    private static Path zip(Map<String,byte[]> entries) throws IOException {
        Path tmp=Files.createTempFile("anything-office-preview",".zip");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(tmp))){
            for(Map.Entry<String,byte[]> entry:entries.entrySet()){
                zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();
            }
        }
        return tmp;
    }
    private static OfficePreview.Result read(Map<String,byte[]> entries,OfficePreview.Format format) throws IOException {
        Path tmp=zip(entries);
        try(FileChannel file=FileChannel.open(tmp,StandardOpenOption.READ)){ return OfficePreview.read(file,format); }
        finally{Files.delete(tmp);}
    }
    private static void reject(Map<String,byte[]> entries,OfficePreview.Format format,String label) throws IOException {
        try { read(entries,format); throw new AssertionError(label); }
        catch(IOException expected){ checks++; }
    }
    private static void check(boolean pass,String label) { if(!pass)throw new AssertionError(label);checks++; }
}
