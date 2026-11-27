package dev.fastsearch.android;

import java.io.FilterInputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/** Read-only, bounded OOXML summary. Opens only package-local XML parts and never follows links. */
final class OfficePreview {
    static final int MAX_PART_BYTES = 3 * 1024 * 1024, MAX_TOTAL_BYTES = 10 * 1024 * 1024;
    static final int MAX_DEPTH = 64, MAX_EVENTS = 300_000, MAX_OUTPUT = 48 * 1024;
    static final int MAX_SHEETS = 8, MAX_ROWS = 40, MAX_COLS = 12, MAX_SLIDES = 20;
    private static final int MAX_SHARED = 10_000, MAX_SHARED_CHARS = 256 * 1024, MAX_SEGMENT = 640;
    private static final long MAX_FILE = 128L * 1024 * 1024;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
            S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
            P = "http://schemas.openxmlformats.org/presentationml/2006/main",
            A = "http://schemas.openxmlformats.org/drawingml/2006/main",
            WS = "http://purl.oclc.org/ooxml/wordprocessingml/main",
            SS = "http://purl.oclc.org/ooxml/spreadsheetml/main",
            PS = "http://purl.oclc.org/ooxml/presentationml/main",
            AS = "http://purl.oclc.org/ooxml/drawingml/main",
            R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
            RS = "http://purl.oclc.org/ooxml/officeDocument/relationships",
            PACKAGE = "http://schemas.openxmlformats.org/package/2006/relationships";

    enum Format {
        WORD("Word document"), SHEET("Excel workbook"), SLIDES("PowerPoint slides");
        final String label;
        Format(String label) { this.label = label; }
    }

    static Format format(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".docx")) return Format.WORD;
        if (lower.endsWith(".xlsx")) return Format.SHEET;
        if (lower.endsWith(".pptx")) return Format.SLIDES;
        return null;
    }
    static String mime(Format f) {
        switch (f) {
            case WORD: return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case SHEET: return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default: return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
    }

    static final class Section {
        final String title, text;
        Section(String title, String text) { this.title = title; this.text = text; }
    }
    static final class Result {
        final Format format;
        final List<Section> sections;
        final boolean limited;
        Result(Format format, List<Section> sections, boolean limited) {
            this.format = format; this.sections = Collections.unmodifiableList(new ArrayList<>(sections)); this.limited = limited;
        }
        int memoryBytes() {
            int bytes = 80;
            for (Section s : sections) bytes += 48 + (s.title.length() + s.text.length()) * 2;
            return bytes;
        }
    }

    static Result read(FileChannel file, Format format) throws IOException {
        if (format == null) throw invalid();
        if (file.size() > MAX_FILE) throw limit();
        ZipDirectory.Listing listing;
        try { listing = ZipDirectory.read(file); }
        catch (IOException badPackage) { PreviewText.cancelled(); throw invalid(); }
        if (listing.truncated || listing.totalEntries > ZipDirectory.MAX_ENTRIES) throw limit();
        Map<String,ZipDirectory.Entry> entries = new HashMap<>();
        for (ZipDirectory.Entry e : listing.entries) {
            if (e.rawName == null || e.directory) continue;
            if (entries.put(e.rawName, e) != null) throw invalid();
        }
        Document doc = new Document(file, entries);
        if (format == Format.WORD) {
            Word word = new Word(doc);
            doc.parse("word/document.xml", word);
            doc.section("Document text", word.text);
        } else {
            boolean sheet = format == Format.SHEET;
            String dir = sheet ? "xl" : "ppt";
            String main = sheet ? "workbook" : "presentation";
            Index index = new Index(doc, sheet);
            doc.parse(dir + "/" + main + ".xml", index);
            if (!index.parts.isEmpty()) {
                String child = sheet ? "worksheets" : "slides";
                Relations rel = new Relations(doc, dir, child, sheet ? "worksheet" : "slide");
                doc.parse(dir + "/_rels/" + main + ".xml.rels", rel);
                Shared shared = null;
                if (sheet) {
                    shared = new Shared(doc);
                    if (entries.containsKey("xl/sharedStrings.xml")) doc.parse("xl/sharedStrings.xml", shared);
                }
                for (Part p : index.parts) {
                    PreviewText.cancelled();
                    String target = rel.parts.get(p.id);
                    if (target == null) { doc.limited = true; continue; }
                    if (doc.output >= MAX_OUTPUT) { doc.limited = true; break; }
                    if (sheet) {
                        Sheet grid = new Sheet(doc, shared.values);
                        doc.parse(target, grid);
                        doc.section(p.title, grid.text);
                    } else {
                        Slide slide = new Slide(doc);
                        doc.parse(target, slide);
                        doc.section(p.title, slide.text);
                    }
                }
            }
            if (doc.sections.isEmpty()) doc.section(sheet ? "Worksheets" : "Slides", new StringBuilder());
        }
        return new Result(format, doc.sections, doc.limited);
    }

    private static final int MAX_GRID_SHEETS=128, MAX_GRID_ROWS=80, MAX_GRID_COLUMNS=16,
            QUICK_GRID_ROWS=12, QUICK_GRID_COLUMNS=6, QUICK_GRID_CELL=160;

    static final class Worksheet {
        final List<String> names;
        final List<List<String>> rows;
        final List<Integer> rowNumbers;
        final int index,columns;
        final String summary;
        final boolean limited;
        Worksheet(List<String> names,int index,GridSheet grid,boolean limited) {
            this.names=Collections.unmodifiableList(new ArrayList<>(names));this.index=index;
            this.rows=Collections.unmodifiableList(grid.rows);this.rowNumbers=Collections.unmodifiableList(grid.numbers);
            this.columns=grid.columns;this.summary=grid.summary.toString();this.limited=limited;
        }
        String title(){return names.get(index);}
    }

    /** Read one public worksheet and its bounded shared-string dictionary; formulas are never parsed. */
    static Worksheet readWorksheet(FileChannel file,int requestedIndex,boolean compact) throws IOException {
        if(file.size()>MAX_FILE)throw limit();
        ZipDirectory.Listing listing;
        try{listing=ZipDirectory.read(file);}catch(IOException bad){PreviewText.cancelled();throw invalid();}
        if(listing.truncated||listing.totalEntries>ZipDirectory.MAX_ENTRIES)throw limit();
        Map<String,ZipDirectory.Entry> entries=new HashMap<>();
        for(ZipDirectory.Entry entry:listing.entries){
            if(entry.rawName!=null&&!entry.directory&&entry.rawName.startsWith("xl/")
                    &&entries.put(entry.rawName,entry)!=null)throw invalid();
        }
        Document doc=new Document(file,entries);
        Index index=new Index(doc,true,MAX_GRID_SHEETS);
        doc.parse("xl/workbook.xml",index);
        Relations rel=new Relations(doc,"xl","worksheets","worksheet");
        doc.parse("xl/_rels/workbook.xml.rels",rel);
        ArrayList<String> names=new ArrayList<>(),targets=new ArrayList<>();
        for(Part part:index.parts){
            String target=rel.parts.get(part.id);
            if(target==null||!entries.containsKey(target)){doc.limited=true;continue;}
            names.add(part.title);targets.add(target);
        }
        if(names.isEmpty())throw invalid();
        int selected=Math.max(0,Math.min(requestedIndex,names.size()-1));
        Shared shared=new Shared(doc);
        if(entries.containsKey("xl/sharedStrings.xml"))doc.parse("xl/sharedStrings.xml",shared);
        GridSheet grid=new GridSheet(doc,shared.values,compact);
        doc.parse(targets.get(selected),grid);
        return new Worksheet(names,selected,grid,doc.limited);
    }

    private static final class GridSheet extends Walker {
        final ArrayList<List<String>> rows=new ArrayList<>();
        final ArrayList<Integer> numbers=new ArrayList<>();
        final StringBuilder value=new StringBuilder(),summary=new StringBuilder();
        final List<String> shared;
        final int rowCap,colCap,cellCap;
        final boolean compact;
        final String[] cells;
        int sheetData,rowDepth,cellDepth,valueDepth,inlineDepth,runDepth,rowNum,lastRow,implicitColumn,col,columns;
        boolean formula,valueSeen,rowInferred,hidden;
        String type;
        GridSheet(Document d,List<String> shared,boolean compact){
            super(d,"worksheet",S,SS);this.shared=shared;this.compact=compact;
            rowCap=compact?QUICK_GRID_ROWS:MAX_GRID_ROWS;
            colCap=compact?QUICK_GRID_COLUMNS:MAX_GRID_COLUMNS;
            cellCap=compact?QUICK_GRID_CELL:MAX_SEGMENT;
            cells=new String[colCap];
        }
        @Override void start(String uri,String n,Attributes attrs){
            if(!match(uri,S,SS))return;
            if(depth==2&&"sheetData".equals(n)){sheetData=depth;return;}
            if(sheetData==0)return;
            if(depth==sheetData+1&&"row".equals(n)){
                rowDepth=depth;implicitColumn=0;
                String seq=attrs.getValue("r");
                rowInferred=seq==null;
                rowNum=positive(seq,1_048_576,seq==null?lastRow+1:0);
                String hiddenFlag=attrs.getValue("hidden");
                hidden="1".equals(hiddenFlag)||"true".equals(hiddenFlag);
                java.util.Arrays.fill(cells,null);
                if(rowNum<=lastRow||rowNum>1_048_576){doc.limited=true;rowNum=0;}
                return;
            }
            if(rowDepth==0||rowNum==0||hidden)return;
            if(depth==rowDepth+1&&"c".equals(n)){
                cellDepth=depth;value.setLength(0);formula=false;valueSeen=false;
                String ref=attrs.getValue("r");type=attrs.getValue("t");
                col=column(ref,implicitColumn);
                if(ref!=null&&col>=0&&col<16_384){
                    int split=ref.length()-1;
                    while(split>=0&&ref.charAt(split)>='0'&&ref.charAt(split)<='9')split--;
                    int addressedRow=positive(ref.substring(split+1),1_048_576,0);
                    if(rowInferred&&implicitColumn==0&&addressedRow>lastRow)rowNum=addressedRow;
                    if(addressedRow!=rowNum)col=16_384;
                }
                if(col>=0&&col<16_384)implicitColumn=Math.max(implicitColumn,col+1);
                return;
            }
            if(cellDepth==0||col<0||col>=colCap||rows.size()>=rowCap)return;
            if(depth==cellDepth+1){
                if("f".equals(n))formula=true;
                else if("v".equals(n)&&!"inlineStr".equals(type)&&!valueSeen){valueSeen=true;valueDepth=depth;}
                else if("is".equals(n)&&"inlineStr".equals(type)&&!valueSeen){valueSeen=true;inlineDepth=depth;}
            }else if(inlineDepth>0&&depth==inlineDepth+1&&"r".equals(n))runDepth=depth;
            if("t".equals(n)&&inlineDepth>0&&(depth==inlineDepth+1||runDepth>0&&depth==runDepth+1))valueDepth=depth;
        }
        @Override void chars(char[] c,int from,int n){
            if(valueDepth>0&&depth==valueDepth)appendChars(value,c,from,n,cellCap);
        }
        @Override void end(String uri,String n){
            if(depth==valueDepth)valueDepth=0;
            if(depth==runDepth)runDepth=0;
            if(depth==inlineDepth)inlineDepth=0;
            if(depth==cellDepth){
                if(col<0||col>=colCap||rows.size()>=rowCap||cells[col]!=null)doc.limited=true;
                else cells[col]=cellValue();
                cellDepth=0;
            }
            if(depth==rowDepth){
                if(rowNum>lastRow)lastRow=rowNum;
                if(hidden)doc.limited=true;
                else if(rowNum>0&&rows.size()<rowCap){
                    int count=0;
                    for(int i=0;i<cells.length;i++)if(cells[i]!=null&&!cells[i].isEmpty())count=i+1;
                    if(count>0){
                        List<String> row=new ArrayList<>(count);
                        for(int i=0;i<count;i++)row.add(cells[i]==null?"":cells[i]);
                        rows.add(Collections.unmodifiableList(row));numbers.add(rowNum);columns=Math.max(columns,count);
                        if(!compact)appendSummary(row);
                    }
                }
                rowDepth=0;
            }
            if(depth==sheetData)sheetData=0;
        }
        private String cellValue(){
            String raw=value.toString();
            if(raw.isEmpty()&&formula)return "[no saved value]";
            if("s".equals(type)){
                int slot=positive(raw,MAX_SHARED,-1);
                if(slot<0||slot>=shared.size()){doc.limited=true;return "[text outside preview]";}
                raw=shared.get(slot);
                if(raw.length()>cellCap){raw=cut(raw,cellCap);doc.limited=true;}
            }else if("b".equals(type))raw="1".equals(raw)?"TRUE":"0".equals(raw)?"FALSE":raw;
            return raw;
        }
        private void appendSummary(List<String> row){
            if(summary.length()>0)append(summary,"\n",MAX_OUTPUT);
            append(summary,Integer.toString(rowNum)+"   ",MAX_OUTPUT);
            boolean first=true;
            for(int i=0;i<row.size();i++){
                String cell=row.get(i);
                if(cell.isEmpty())continue;
                if(!first)append(summary,"  │  ",MAX_OUTPUT);
                append(summary,(char)('A'+i)+" "+cell.replace('\n',' ').replace('\r',' ').replace('\t',' '),MAX_OUTPUT);
                first=false;
            }
        }
    }

    static final class SlidePage {
        final int index,count,width,height,background;
        final List<SlideItem> items;
        final String text;
        final boolean limited;
        SlidePage(int index,int count,int width,int height,int background,List<SlideItem> items,String text,boolean limited){
            this.index=index;this.count=count;this.width=width;this.height=height;this.background=background;
            this.items=Collections.unmodifiableList(new ArrayList<>(items));this.text=text;this.limited=limited;
        }
        int memoryBytes(){
            int bytes=128+text.length()*2;
            for(SlideItem item:items)bytes+=128+item.text.length()*2+(item.image==null?0:item.image.length);
            return bytes;
        }
    }

    static final class SlideItem {
        final int x,y,width,height,fill,stroke,textColor,fontSize;
        final String text,align,geometry,vertical;
        final boolean bold;
        final byte[] image;
        SlideItem(SlideBox b,byte[] image){
            x=b.x;y=b.y;width=b.width;height=b.height;fill=b.fill;stroke=b.stroke;textColor=b.color;
            fontSize=b.font;align=b.align;vertical=b.vertical;bold=b.bold;text=b.text.toString().trim();
            geometry=b.geometry;this.image=image;
        }
    }

    private static final int MAX_SLIDE_ITEMS=160, MAX_PAGE_IMAGES=4*1024*1024, MAX_IMAGE=2*1024*1024,
            MAX_PREVIEW_SLIDES=512, MAX_EMU=60_000_000, DEFAULT_WIDTH=12_192_000, DEFAULT_HEIGHT=6_858_000;

    /** Read only the package index, selected slide, and bounded internal resources needed for it. */
    static SlidePage readSlide(FileChannel file,int requestedIndex) throws IOException {
        if(file.size()>MAX_FILE)throw limit();
        ZipDirectory.Listing listing;
        try{listing=ZipDirectory.read(file);}catch(IOException bad){PreviewText.cancelled();throw invalid();}
        if(listing.truncated)throw limit();
        Map<String,ZipDirectory.Entry> entries=new HashMap<>();
        for(ZipDirectory.Entry e:listing.entries)
            if(e.rawName!=null&&!e.directory&&e.rawName.startsWith("ppt/")&&entries.put(e.rawName,e)!=null)throw invalid();
        Document d=new Document(file,entries);
        PageIndex index=new PageIndex(d);
        d.parse("ppt/presentation.xml",index);
        if(index.slideIds.isEmpty())throw invalid();
        PageRelations deck=new PageRelations(d,"ppt/presentation.xml","slide");
        d.parse("ppt/_rels/presentation.xml.rels",deck);
        int page=Math.max(0,Math.min(requestedIndex,index.slideIds.size()-1));
        String part=deck.parts.get(index.slideIds.get(page));
        if(part==null)throw invalid();
        PageShapes shapes=new PageShapes(d,"sld");
        d.parse(part,shapes);
        PageRelations resources=d.findOptionalRelations(part,"image","slideLayout");
        if(resources==null)resources=new PageRelations(d,part);
        PageShapes layout=d.optionalShapePart(resources.layout,"sldLayout");
        PageRelations layoutRelations=resources.layout==null?null:d.findOptionalRelations(resources.layout,"slideMaster");
        String masterPart=layoutRelations==null?null:layoutRelations.master;
        PageShapes master=d.optionalShapePart(masterPart,"sldMaster");
        PageRelations masterRelations=masterPart==null?null:d.findOptionalRelations(masterPart,"theme");
        ThemeColors colors=new ThemeColors(d);
        if(masterRelations!=null&&masterRelations.theme!=null){
            try{d.parse(masterRelations.theme,colors);}
            catch(IOException unavailable){PreviewText.cancelled();colors.values.clear();d.limited=true;}
        }
        List<SlideItem> items=new ArrayList<>();
        Map<String,byte[]> already=new HashMap<>();
        int remaining=MAX_PAGE_IMAGES;
        StringBuilder summary=new StringBuilder();
        for(SlideBox box:shapes.boxes){
            PreviewText.cancelled();
            String value=box.text.toString().trim();
            if(!value.isEmpty()&&summary.length()<MAX_OUTPUT){
                if(summary.length()>0)summary.append('\n');
                int room=MAX_OUTPUT-summary.length();
                if(value.length()>room)d.limited=true;
                summary.append(cut(value,MAX_OUTPUT-summary.length()));
            }
            if(!box.positioned&&box.placeholder!=null){
                SlideBox inherited=layout==null?null:layout.placeholder(box);
                if((inherited==null||!inherited.positioned)&&master!=null){
                    SlideBox candidate=master.placeholder(inherited==null?box:inherited);
                    if(candidate!=null&&candidate.positioned)inherited=candidate;
                }
                if(inherited!=null&&inherited.positioned){
                    box.position(inherited.x,inherited.y,inherited.width,inherited.height);
                    if(!box.customAnchor)box.vertical=inherited.vertical;
                    if("title".equals(box.placeholder)||"ctrTitle".equals(box.placeholder))box.titleDefaults();
                    if(!box.customAlign&&("subTitle".equals(box.placeholder)||"ctrTitle".equals(box.placeholder)))box.align="ctr";
                }else box.placeholderPosition(index.width,index.height);
            }
            box.fill=colors.lookup(box.fillTheme,box.fill);
            box.stroke=colors.lookup(box.strokeTheme,box.stroke);
            box.color=colors.lookup(box.colorTheme,box.color);
            if(!box.positioned||box.width<1||box.height<1){d.limited=true;continue;}
            byte[] image=null;
            if(box.picture){
                String source=box.imageId==null?null:resources.parts.get(box.imageId);
                if(source!=null&&already.containsKey(source))image=already.get(source);
                else if(source!=null){
                    ZipDirectory.Entry raster=entries.get(source);
                    if(raster!=null&&raster.size>=8&&raster.size<=Math.min(MAX_IMAGE,remaining)){
                        remaining-=(int)raster.size;
                        image=d.raster(source,MAX_IMAGE);
                    }
                    already.put(source,image);
                }
                if(image==null){d.limited=true;continue;}
            }
            if(image!=null||!value.isEmpty()||box.fill!=0||box.stroke!=0)items.add(new SlideItem(box,image));
        }
        int background=colors.lookup(shapes.backgroundTheme,shapes.background);
        if(background==0&&layout!=null)background=colors.lookup(layout.backgroundTheme,layout.background);
        if(background==0&&master!=null)background=colors.lookup(master.backgroundTheme,master.background);
        return new SlidePage(page,index.slideIds.size(),index.width,index.height,background==0?0xffffffff:background,
                items,summary.toString(),d.limited);
    }

    private static final class PageIndex extends Walker {
        final ArrayList<String> slideIds=new ArrayList<>();
        final Set<String> seen=new HashSet<>();
        int list,width=DEFAULT_WIDTH,height=DEFAULT_HEIGHT;
        PageIndex(Document d){super(d,"presentation",P,PS);}
        @Override void start(String uri,String name,Attributes a){
            if(!match(uri,P,PS))return;
            if(depth==2&&"sldIdLst".equals(name)){list=2;return;}
            if(depth==2&&"sldSz".equals(name)){
                width=positive(a.getValue("cx"),MAX_EMU,DEFAULT_WIDTH);
                height=positive(a.getValue("cy"),MAX_EMU,DEFAULT_HEIGHT);
                if(width<1||height<1){width=DEFAULT_WIDTH;height=DEFAULT_HEIGHT;doc.limited=true;}
            }
            if(depth!=3||list==0||!"sldId".equals(name))return;
            String id=relationshipId(a,"id");
            if(id==null||id.length()>100||slideIds.size()>=MAX_PREVIEW_SLIDES||!seen.add(id)){doc.limited=true;return;}
            slideIds.add(id);
        }
        @Override void end(String uri,String name){if(depth==list)list=0;}
    }

    private static final class PageRelations extends Walker {
        final String source;
        final Set<String> kinds=new HashSet<>(),seen=new HashSet<>();
        final Map<String,String> parts=new HashMap<>();
        String layout,layoutId,master,masterId,theme,themeId;
        PageRelations(Document d,String source,String... allowed){
            super(d,"Relationships",PACKAGE,PACKAGE);this.source=source;Collections.addAll(kinds,allowed);
        }
        @Override void start(String uri,String name,Attributes a){
            if(depth!=2||!PACKAGE.equals(uri)||!"Relationship".equals(name))return;
            if(seen.size()>=1024){doc.limited=true;return;}
            String id=a.getValue("Id"),kind=a.getValue("Type"),target=a.getValue("Target"),mode=a.getValue("TargetMode");
            if(id==null||id.length()>100)return;
            if(!seen.add(id)){
                parts.remove(id);
                if(id.equals(layoutId)){layout=null;layoutId=null;}
                if(id.equals(masterId)){master=null;masterId=null;}
                if(id.equals(themeId)){theme=null;themeId=null;}
                doc.limited=true;return;
            }
            if(mode!=null&&!"Internal".equals(mode)||kind==null)return;
            String type=kind.startsWith(R+"/")?kind.substring(R.length()+1):kind.startsWith(RS+"/")?kind.substring(RS.length()+1):"";
            if(!kinds.contains(type))return;
            String path=packagePath(source,target);
            if(path==null)return;
            boolean slide="slide".equals(type),picture="image".equals(type);
            String prefix=slide?"ppt/slides/":picture?"ppt/media/":
                    "slideMaster".equals(type)?"ppt/slideMasters/":"theme".equals(type)?"ppt/theme/":"ppt/slideLayouts/";
            if(!path.startsWith(prefix)||path.indexOf('/',prefix.length())>=0||!doc.entries.containsKey(path))return;
            String lower=path.toLowerCase(Locale.ROOT);
            if(picture?!(lower.endsWith(".png")||lower.endsWith(".jpeg")||lower.endsWith(".jpg")):!lower.endsWith(".xml"))return;
            if(slide||picture)parts.put(id,path);
            else if("slideMaster".equals(type)){if(master==null){master=path;masterId=id;}else doc.limited=true;}
            else if("theme".equals(type)){if(theme==null){theme=path;themeId=id;}else doc.limited=true;}
            else if(layout==null){layout=path;layoutId=id;}else doc.limited=true;
        }
    }
    private static String relationshipId(Attributes a,String local){
        String value=a.getValue(R,local);return value==null?a.getValue(RS,local):value;
    }
    private static String packagePath(String from,String target){
        if(target==null||target.length()>240||target.isEmpty())return null;
        for(int j=0;j<target.length();j++){
            char c=target.charAt(j);
            if(!(c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||c=='/'||c=='.'||c=='_'||c=='-'))return null;
        }
        String combined=target.charAt(0)=='/'?target.substring(1):from.substring(0,from.lastIndexOf('/')+1)+target;
        ArrayList<String> resolved=new ArrayList<>();
        for(String segment:combined.split("/",-1)){
            if(segment.isEmpty())return null;
            if(".".equals(segment))continue;
            if("..".equals(segment)){
                if(resolved.size()<2)return null;
                resolved.remove(resolved.size()-1);
            }else resolved.add(segment);
        }
        return String.join("/",resolved);
    }

    private static final class SlideBox {
        int x,y,width,height,fill,stroke,color=0xff20232a,font=304800;
        boolean positioned,bold,picture,customFont,customBold,customAnchor,customAlign;
        String align="l",vertical="t",geometry="rect",placeholder,placeholderId,imageId,fillTheme,strokeTheme,colorTheme;
        final StringBuilder text=new StringBuilder();
        void position(int x,int y,int width,int height){
            this.x=x;this.y=y;this.width=width;this.height=height;positioned=width>0&&height>0;
        }
        void placeholderPosition(int w,int h){
            boolean title="title".equals(placeholder)||"ctrTitle".equals(placeholder);
            boolean footer="ftr".equals(placeholder)||"dt".equals(placeholder)||"sldNum".equals(placeholder);
            int top=title?("ctrTitle".equals(placeholder)?38:10):footer?88:"subTitle".equals(placeholder)?55:28;
            position(w/12,h*top/100,w*5/6,h*(title?17:footer?7:"subTitle".equals(placeholder)?20:58)/100);
            if(title)titleDefaults();
            if(!customAlign&&("ctrTitle".equals(placeholder)||"subTitle".equals(placeholder)))align="ctr";
        }
        void titleDefaults(){if(!customFont)font=508000;if(!customBold)bold=true;}
    }

    private static final class PageShapes extends Walker {
        final List<SlideBox> boxes=new ArrayList<>();
        int tree,shape,properties,transform,textDepth,para,t,style,fill,line,bg,background,shapeChars;
        String backgroundTheme;
        SlideBox current;
        boolean offset,extent,firstText;
        PageShapes(Document d,String root){super(d,root,P,PS);}
        SlideBox placeholder(SlideBox match){
            SlideBox byType=null;
            for(SlideBox candidate:boxes){
                if(candidate.placeholder==null)continue;
                if(match.placeholderId!=null&&match.placeholderId.equals(candidate.placeholderId))return candidate;
                if(match.placeholder.equals(candidate.placeholder))byType=candidate;
            }
            return byType;
        }
        @Override void start(String uri,String n,Attributes a){
            boolean p=match(uri,P,PS),draw=match(uri,A,AS);
            if(p&&"bg".equals(n)&&depth==3){bg=depth;return;}
            if(bg>0&&draw&&background==0){
                if("srgbClr".equals(n))background=rgb(a.getValue("val"),0);
                if("schemeClr".equals(n))backgroundTheme=a.getValue("val");
            }
            if(p&&"spTree".equals(n)&&depth==3){tree=depth;return;}
            if(tree==0)return;
            if(depth==tree+1&&p){
                if(!"sp".equals(n)&&!"pic".equals(n)){
                    if(!"nvGrpSpPr".equals(n)&&!"grpSpPr".equals(n)&&!"extLst".equals(n))doc.limited=true;
                    return;
                }
                if(boxes.size()>=MAX_SLIDE_ITEMS){doc.limited=true;return;}
                shape=depth;current=new SlideBox();current.picture="pic".equals(n);
                offset=false;extent=false;firstText=true;
                return;
            }
            if(current==null)return;
            SlideBox b=current;
            if(p&&"ph".equals(n)&&depth==shape+3){
                b.placeholder=a.getValue("type");if(b.placeholder==null)b.placeholder="body";
                b.placeholderId=a.getValue("idx");return;
            }
            if(p&&"spPr".equals(n)&&depth==shape+1){properties=depth;return;}
            if(p&&"txBody".equals(n)&&depth==shape+1){textDepth=depth;return;}
            if(draw&&"blip".equals(n)&&b.picture&&depth==shape+2){b.imageId=relationshipId(a,"embed");return;}
            if(!draw)return;
            if(properties!=0){
                if(depth==properties+1){
                    if("xfrm".equals(n)){
                        transform=depth;
                        if(a.getValue("rot")!=null&&!"0".equals(a.getValue("rot")))doc.limited=true;
                    }else if("prstGeom".equals(n)){
                        String value=a.getValue("prst");if(value!=null)b.geometry=value;
                    }else if("solidFill".equals(n))fill=depth;
                    else if("ln".equals(n))line=depth;
                    else if("custGeom".equals(n)||"gradFill".equals(n)||"pattFill".equals(n)||"blipFill".equals(n))doc.limited=true;
                }
                if(transform!=0&&depth==transform+1){
                    if("off".equals(n)){b.x=signed(a.getValue("x"));b.y=signed(a.getValue("y"));offset=true;}
                    if("ext".equals(n)){
                        b.width=positive(a.getValue("cx"),MAX_EMU,0);b.height=positive(a.getValue("cy"),MAX_EMU,0);extent=true;
                    }
                }
                if("srgbClr".equals(n)){
                    if(fill!=0&&depth==fill+1)b.fill=rgb(a.getValue("val"),0);
                    if(line!=0&&depth==line+2)b.stroke=rgb(a.getValue("val"),0);
                }
                if("schemeClr".equals(n)){
                    if(fill!=0&&depth==fill+1)b.fillTheme=a.getValue("val");
                    if(line!=0&&depth==line+2)b.strokeTheme=a.getValue("val");
                }
            }
            if(textDepth==0)return;
            if(depth==textDepth+1&&"bodyPr".equals(n)){
                String anchor=a.getValue("anchor");
                if("ctr".equals(anchor)||"b".equals(anchor)||"t".equals(anchor)){b.vertical=anchor;b.customAnchor=true;}
            }
            if(depth==textDepth+1&&"p".equals(n)){para=depth;if(b.text.length()>0)append(b.text,"\n",4096);}
            if(para==0)return;
            if(depth==para+1&&"pPr".equals(n)&&firstText){
                String value=a.getValue("algn");
                if("ctr".equals(value)||"r".equals(value)||"l".equals(value)){b.align=value;b.customAlign=true;}
            }
            if("br".equals(n))append(b.text,"\n",4096);
            if("t".equals(n))t=depth;
            if(firstText&&("rPr".equals(n)||"defRPr".equals(n))){
                style=depth;
                int size=positive(a.getValue("sz"),400_000,0);
                if(size>0){b.font=Math.min(MAX_EMU,size*127);b.customFont=true;}
                String bold=a.getValue("b");
                if(bold!=null){b.bold="1".equals(bold)||"true".equals(bold);b.customBold=true;}
            }
            if(style!=0&&"srgbClr".equals(n))b.color=rgb(a.getValue("val"),b.color);
            if(style!=0&&"schemeClr".equals(n))b.colorTheme=a.getValue("val");
        }
        @Override void chars(char[] c,int start,int n){
            if(current!=null&&t>0){
                int allowed=Math.min(n,Math.max(0,MAX_OUTPUT-shapeChars));
                if(allowed<n)doc.limited=true;
                if(allowed>0){appendChars(current.text,c,start,allowed,4096);shapeChars+=allowed;firstText=false;}
            }
        }
        @Override void end(String uri,String name){
            if(depth==t)t=0;if(depth==para)para=0;if(depth==style)style=0;
            if(depth==textDepth)textDepth=0;if(depth==transform)transform=0;if(depth==properties)properties=0;
            if(depth==fill)fill=0;if(depth==line)line=0;if(depth==bg)bg=0;if(depth==tree)tree=0;
            if(depth==shape&&current!=null){
                current.positioned=offset&&extent&&current.width>0&&current.height>0;
                boxes.add(current);current=null;shape=0;
            }
        }
    }
    private static final class ThemeColors extends Walker {
        final Map<String,Integer> values=new HashMap<>();
        String slot;
        int palette;
        ThemeColors(Document d){super(d,"theme",A,AS);}
        @Override void start(String uri,String name,Attributes a){
            if(!match(uri,A,AS))return;
            if(depth==3&&"clrScheme".equals(name)){palette=depth;return;}
            if(palette==0)return;
            if(depth==palette+1){slot=name;return;}
            if(depth==palette+2&&slot!=null&&values.size()<16){
                int value=rgb("srgbClr".equals(name)?a.getValue("val"):
                        "sysClr".equals(name)?a.getValue("lastClr"):null,0);
                if(value!=0)values.put(slot,value);
            }
        }
        @Override void end(String uri,String name){
            if(palette!=0&&depth==palette+1)slot=null;
            if(depth==palette)palette=0;
        }
        int lookup(String name,int fallback){
            if(name==null)return fallback;
            String internal="tx1".equals(name)?"dk1":"tx2".equals(name)?"dk2":
                    "bg1".equals(name)?"lt1":"bg2".equals(name)?"lt2":name;
            Integer known=values.get(internal);
            return known==null?scheme(name,fallback):known;
        }
    }
    private static int signed(String raw){
        if(raw!=null&&raw.startsWith("-"))return -positive(raw.substring(1),MAX_EMU,0);
        return positive(raw,MAX_EMU,0);
    }
    private static int rgb(String raw,int fallback){
        if(raw==null||raw.length()!=6)return fallback;
        int out=0;
        for(int i=0;i<6;i++){int value=Character.digit(raw.charAt(i),16);if(value<0)return fallback;out=out*16+value;}
        return 0xff000000|out;
    }
    private static int scheme(String name,int fallback){
        if(name==null)return fallback;
        switch(name){
            case "dk1":case "tx1":return 0xff20232a;
            case "lt1":case "bg1":return 0xffffffff;
            case "dk2":case "tx2":return 0xff40556c;
            case "lt2":case "bg2":return 0xffeeeeea;
            case "accent1":return 0xff4472c4;case "accent2":return 0xffed7d31;
            case "accent3":return 0xffa5a5a5;case "accent4":return 0xffffc000;
            case "accent5":return 0xff5b9bd5;case "accent6":return 0xff70ad47;
            default:return fallback;
        }
    }

    private static final class Document {
        final FileChannel file;
        final Map<String,ZipDirectory.Entry> entries;
        final ArrayList<Section> sections = new ArrayList<>();
        int expanded, events, output;
        boolean limited;
        Document(FileChannel file, Map<String,ZipDirectory.Entry> entries) { this.file = file; this.entries = entries; }

        void section(String title, StringBuilder value) {
            String text = value.toString().trim();
            if (text.isEmpty()) text = "No text to show in this section.";
            int budget = MAX_OUTPUT - output;
            if (budget <= 0) { limited = true; return; }
            if (text.length() > budget) { text = cut(text, budget); limited = true; }
            output += text.length();
            sections.add(new Section(title, text));
        }

        PageRelations findOptionalRelations(String source,String... kinds) throws IOException {
            PageRelations rel=new PageRelations(this,source,kinds);
            int split=source.lastIndexOf('/');
            String part=source.substring(0,split+1)+"_rels/"+source.substring(split+1)+".rels";
            if(!entries.containsKey(part))return rel;
            try{parse(part,rel);return rel;}
            catch(IOException unavailable){PreviewText.cancelled();limited=true;return null;}
        }
        PageShapes optionalShapePart(String part,String type) throws IOException {
            if(part==null)return null;
            PageShapes parsed=new PageShapes(this,type);
            boolean visibleLimited=limited;
            try{parse(part,parsed);limited=visibleLimited;return parsed;}
            catch(IOException unavailable){PreviewText.cancelled();limited=true;return null;}
        }
        byte[] raster(String part,int budget) throws IOException {
            ZipDirectory.Entry entry=entries.get(part);
            if(entry==null||entry.special||entry.encrypted||entry.crc<0||entry.size<8||entry.size>budget
                    ||entry.compressedSize>budget+64*1024L)return null;
            try(PartInput input=new PartInput(ZipDirectory.open(file,entry),entry)){
                byte[] bytes=new byte[(int)entry.size];
                int at=0;
                while(at<bytes.length){int got=input.read(bytes,at,bytes.length-at);if(got<1)throw invalid();at+=got;}
                if(input.read()!=-1)throw invalid();
                input.verify();
                if((bytes[0]&255)==0x89&&bytes[1]=='P'&&bytes[2]=='N'&&bytes[3]=='G'
                        &&bytes[4]==13&&bytes[5]==10&&bytes[6]==26&&bytes[7]==10
                        ||(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255)return bytes;
            }catch(IOException unavailable){PreviewText.cancelled();}
            return null;
        }

        void parse(String part, Walker handler) throws IOException {
            ZipDirectory.Entry entry = entries.get(part);
            if (entry == null || entry.special || entry.encrypted || entry.crc < 0) throw invalid();
            if (entry.size < 0 || entry.size > MAX_PART_BYTES || entry.size > MAX_TOTAL_BYTES - expanded) throw limit();
            expanded += (int)entry.size;
            try (PartInput bytes = new PartInput(ZipDirectory.open(file, entry), entry);
                 PushbackInputStream input = new PushbackInputStream(bytes, 3)) {
                Charset encoding = detectEncoding(input);
                SAXParserFactory factory = SAXParserFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setValidating(false);
                XMLReader xml = factory.newSAXParser().getXMLReader();
                // Disallow DTD/entity markup before the parser sees it, independent of the Android/JDK
                // implementation's supported feature flags. Reject any remaining resolution attempt.
                xml.setEntityResolver((publicId, systemId) -> { throw new SAXException("External Office reference"); });
                xml.setContentHandler(handler);
                xml.setErrorHandler(handler);
                try (GuardedReader safe = new GuardedReader(new InputStreamReader(input, encoding.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))) {
                    xml.parse(new InputSource(safe));
                    if (!handler.root) throw invalid();
                    bytes.verify();
                }
            } catch (IOException e) { throw e; }
            catch (Exception e) {
                for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                    if (cause instanceof Limit) throw (Limit) cause;
                    if (cause.getCause() == cause) break;
                }
                PreviewText.cancelled();
                throw invalid();
            }
        }
    }

    private static Charset detectEncoding(PushbackInputStream input) throws IOException {
        byte[] lead = new byte[3];
        int count = 0;
        for (; count < lead.length; ++count) {
            int value = input.read();
            if (value < 0) break;
            lead[count] = (byte) value;
        }
        int skip = 0;
        Charset encoding = StandardCharsets.UTF_8;
        if (count >= 2 && (lead[0] & 255) == 255 && (lead[1] & 255) == 254) { skip = 2; encoding = StandardCharsets.UTF_16LE; }
        else if (count >= 2 && (lead[0] & 255) == 254 && (lead[1] & 255) == 255) { skip = 2; encoding = StandardCharsets.UTF_16BE; }
        else if (count == 3 && (lead[0] & 255) == 239 && (lead[1] & 255) == 187 && (lead[2] & 255) == 191) skip = 3;
        input.unread(lead, skip, count - skip);
        return encoding;
    }

    private static final class PartInput extends FilterInputStream {
        private final ZipDirectory.Entry entry;
        private final CRC32 crc = new CRC32();
        int count;
        PartInput(InputStream source, ZipDirectory.Entry entry) { super(source); this.entry = entry; }
        @Override public int read() throws IOException {
            PreviewText.cancelled();
            int value = in.read();
            if (value >= 0) { received(1); crc.update(value); }
            return value;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            PreviewText.cancelled();
            int read = in.read(b, off, (int)Math.min(len, entry.size - count + 1));
            if (read > 0) { received(read); crc.update(b, off, read); }
            return read;
        }
        private void received(int bytes) throws IOException {
            if (bytes > entry.size - count) throw invalid();
            count += bytes;
        }
        void verify() throws IOException {
            if (count != entry.size || crc.getValue() != entry.crc) throw invalid();
        }
    }

    private static final class GuardedReader extends FilterReader {
        private static final String DOCTYPE = "<!DOCTYPE", ENTITY = "<!ENTITY";
        private int dtd, entity;
        GuardedReader(InputStreamReader source) { super(source); }
        @Override public int read() throws IOException {
            PreviewText.cancelled();
            int ch = in.read();
            if (ch >= 0) inspect((char)ch);
            return ch;
        }
        @Override public int read(char[] chars, int offset, int length) throws IOException {
            PreviewText.cancelled();
            int count = in.read(chars, offset, length);
            for (int i = 0; i < count; i++) inspect(chars[offset + i]);
            return count;
        }
        @Override public long skip(long count) throws IOException {
            if (count < 0) throw new IllegalArgumentException("Negative skip");
            char[] buffer = new char[(int)Math.min(1024,count)];
            long skipped = 0;
            while (skipped < count) {
                int read = read(buffer,0,(int)Math.min(buffer.length,count-skipped));
                if (read < 0) break;
                skipped += read;
            }
            return skipped;
        }
        @Override public boolean markSupported() { return false; }
        @Override public void mark(int limit) throws IOException { throw new IOException("Office stream cannot rewind."); }
        @Override public void reset() throws IOException { throw new IOException("Office stream cannot rewind."); }
        private void inspect(char ch) throws IOException {
            if (ch == 0) throw invalid();
            dtd = next(DOCTYPE, dtd, ch); entity = next(ENTITY, entity, ch);
            if (dtd == DOCTYPE.length() || entity == ENTITY.length()) throw invalid();
        }
        private static int next(String pattern, int index, char value) {
            return value == pattern.charAt(index) ? index + 1 : value == '<' ? 1 : 0;
        }
    }

    private abstract static class Walker extends DefaultHandler {
        final Document doc;
        final String expected, ns1, ns2;
        int depth;
        boolean root;
        Walker(Document doc, String expected, String ns1, String ns2) {
            this.doc = doc; this.expected = expected; this.ns1 = ns1; this.ns2 = ns2;
        }
        @Override public final void startElement(String uri, String name, String qname, Attributes attrs) throws SAXException {
            event();
            if (++depth > MAX_DEPTH || attrs.getLength() > 96) throw new SAXException(limit());
            if (depth == 1) {
                if (!match(uri, ns1, ns2) || !expected.equals(name)) throw new SAXException(invalid());
                root = true;
            }
            start(uri, name, attrs);
        }
        @Override public final void endElement(String uri, String name, String qname) throws SAXException {
            event(); end(uri, name); depth--;
        }
        @Override public final void characters(char[] chars, int start, int count) throws SAXException {
            event(); chars(chars, start, count);
        }
        @Override public final void error(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
        @Override public final void fatalError(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
        void start(String uri, String name, Attributes attrs) { }
        void end(String uri, String name) { }
        void chars(char[] c, int from, int n) { }
        void append(StringBuilder dest, String text, int max) {
            int remaining = max - dest.length();
            if (text.length() > remaining) { if (remaining > 0) dest.append(cut(text, remaining)); doc.limited = true; }
            else dest.append(text);
        }
        void appendChars(StringBuilder dest, char[] input, int from, int n, int cap) {
            int available = cap - dest.length();
            if (n > available) doc.limited = true;
            int last = from + Math.max(0, Math.min(n, available));
            if (last > from && last < from + n && Character.isHighSurrogate(input[last - 1])) last--;
            for (int i = from; i < last; i++) {
                char ch = input[i];
                // Don't let embedding controls make trusted sheet labels or addresses appear reordered.
                if (ch >= 0x202A && ch <= 0x202E || ch >= 0x2066 && ch <= 0x2069) ch = '\uFFFD';
                dest.append(ch);
            }
        }
        void event() throws SAXException {
            if (++doc.events > MAX_EVENTS) throw new SAXException(limit());
            if (Thread.currentThread().isInterrupted()) throw new SAXException(new IOException("Preview stopped."));
        }
    }

    private static final class Word extends Walker {
        final StringBuilder text = new StringBuilder(), paragraph = new StringBuilder(), row = new StringBuilder(), cell = new StringBuilder();
        int body, para, textNode, rowDepth, cellDepth, blocks, cellCount;
        Word(Document d) { super(d, "document", W, WS); }
        @Override void start(String uri, String n, Attributes a) {
            if (!match(uri,W,WS)) return;
            if (body == 0) { if ("body".equals(n) && depth == 2) body = depth; return; }
            if ("p".equals(n) && para == 0) { para = depth; paragraph.setLength(0); }
            if ("tr".equals(n) && rowDepth == 0) { rowDepth = depth; row.setLength(0); cellCount = 0; }
            if ("tc".equals(n) && rowDepth > 0 && cellDepth == 0) {
                cell.setLength(0); cellDepth = depth;
            }
            if (para == 0) return;
            if ("t".equals(n)) textNode = depth;
            else if ("tab".equals(n)) append(paragraph, "\t", 2048);
            else if ("br".equals(n) || "cr".equals(n)) append(paragraph, "\n", 2048);
        }
        @Override void chars(char[] c, int from, int n) {
            if (textNode > 0) appendChars(paragraph,c,from,n,2048);
        }
        @Override void end(String uri, String n) {
            if (depth == textNode) textNode = 0;
            if (depth == para) {
                String p = paragraph.toString().trim();
                if (!p.isEmpty()) {
                    if (cellDepth > 0) {
                        String value = p.replace('\n',' ');
                        if (cell.length() > 0) append(cell," / ",2048);
                        append(cell,value,2048);
                    } else addBlock(p);
                }
                para = 0;
            }
            if (depth == cellDepth) {
                if (++cellCount <= 24) {
                    if (cellCount > 1) append(row, "  │  ", 2048);
                    append(row,cell.length()==0?"·":cell.toString(),2048);
                } else doc.limited = true;
                cellDepth = 0;
            }
            if (depth == rowDepth) { if (row.length() > 0) addBlock(row.toString()); rowDepth = 0; }
            if (depth == body) body = 0;
        }
        private void addBlock(String block) {
            if (++blocks > 1000) { doc.limited = true; return; }
            if (text.length() > 0) append(text,"\n",MAX_OUTPUT);
            append(text,block,MAX_OUTPUT);
        }
    }

    private static final class Part {
        final String id, title;
        Part(String id, String title) { this.id = id; this.title = title; }
    }
    private static final class Index extends Walker {
        final boolean sheet;
        final List<Part> parts = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        final int cap;
        int position,sheetList;
        Index(Document d, boolean sheet) { this(d,sheet,sheet?MAX_SHEETS:MAX_SLIDES); }
        Index(Document d, boolean sheet,int cap) {
            super(d,sheet?"workbook":"presentation",sheet?S:P,sheet?SS:PS); this.sheet = sheet;this.cap=cap;
        }
        @Override void start(String uri, String name, Attributes attrs) {
            if (!match(uri,ns1,ns2)) return;
            if (sheet&&depth==2&&"sheets".equals(name)){sheetList=depth;return;}
            if (depth != 3 || sheet&&sheetList==0 || !(sheet ? "sheet" : "sldId").equals(name)) return;
            int order = ++position;
            if (parts.size() >= cap) { doc.limited = true; return; }
            // Do not expose the contents of hidden worksheets.
            String state = attrs.getValue("state");
            if (sheet && state != null && !"visible".equals(state)) { doc.limited = true; return; }
            String id = attrs.getValue(R,"id");
            if (id == null) id = attrs.getValue(RS,"id");
            if (id == null || id.length() > 100 || !ids.add(id)) { doc.limited = true; return; }
            String title = sheet ? attrs.getValue("name") : null;
            if (title == null || title.trim().isEmpty()) title = (sheet ? "Sheet " : "Slide ") + order;
            title = ZipDirectory.safeLabel(cut(title, 100));
            parts.add(new Part(id,title));
        }
        @Override void end(String uri,String name){if(depth==sheetList)sheetList=0;}
    }

    private static final class Relations extends Walker {
        final String dir, child, type;
        final Map<String,String> parts = new HashMap<>();
        final Set<String> seen = new HashSet<>();
        Relations(Document d, String dir, String child, String type) {
            super(d,"Relationships",PACKAGE,PACKAGE); this.dir=dir; this.child=child; this.type=type;
        }
        @Override void start(String uri, String name, Attributes attrs) {
            if (depth != 2 || !PACKAGE.equals(uri) || !"Relationship".equals(name)) return;
            if (seen.size() >= 1024) { doc.limited = true; return; }
            String id = attrs.getValue("Id"), kind = attrs.getValue("Type"), target = attrs.getValue("Target"), mode = attrs.getValue("TargetMode");
            if (id == null || id.length() > 100) return;
            if (!seen.add(id)) { parts.remove(id); doc.limited = true; return; }
            if (mode != null && !"Internal".equals(mode) || kind == null || !(kind.equals(R + "/" + type) || kind.equals(RS + "/" + type))
                    || target == null || target.length() > 240) return;
            String path = target.startsWith("/") ? target.substring(1) : dir + "/" + target;
            String prefix = dir + "/" + child + "/";
            if (!path.startsWith(prefix) || !path.endsWith(".xml")) return;
            String base = path.substring(prefix.length(),path.length()-4);
            if (base.isEmpty() || ".".equals(base) || "..".equals(base)) return;
            for (int i = 0; i < base.length(); i++) {
                char c = base.charAt(i);
                if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                        || c == '.' || c == '_' || c == '-')) return;
            }
            parts.put(id,path);
        }
    }

    private static final class Shared extends Walker {
        final List<String> values = new ArrayList<>();
        final StringBuilder value = new StringBuilder();
        int item, textNode, phonetic, chars;
        Shared(Document d) { super(d,"sst",S,SS); }
        @Override void start(String uri, String n, Attributes a) {
            if (!match(uri,S,SS)) return;
            if ("si".equals(n) && depth == 2) { item = depth; value.setLength(0); }
            if (item > 0 && "rPh".equals(n)) phonetic = depth;
            if (item > 0 && phonetic == 0 && "t".equals(n)) textNode = depth;
        }
        @Override void chars(char[] c,int from,int n) {
            if (textNode > 0 && values.size() < MAX_SHARED) appendChars(value,c,from,n,MAX_SEGMENT);
        }
        @Override void end(String uri,String n) {
            if (depth == textNode) textNode = 0;
            if (depth == phonetic) phonetic = 0;
            if (depth == item) {
                item = 0;
                if (values.size() < MAX_SHARED && chars + value.length() <= MAX_SHARED_CHARS) {
                    chars += value.length(); values.add(value.toString());
                } else {
                    // Record that a gap exists, but don't shift any later slot into a different index.
                    doc.limited = true; chars = MAX_SHARED_CHARS + 1;
                }
            }
        }
    }

    private static final class Sheet extends Walker {
        final List<String> shared;
        final StringBuilder text = new StringBuilder(), value = new StringBuilder();
        final String[] cells = new String[MAX_COLS];
        int sheetData, row, cell, valueNode, accepted, col, implicitColumn, rowIndex, rowOrdinal;
        String type;
        boolean formula;
        Sheet(Document d, List<String> shared) { super(d,"worksheet",S,SS); this.shared = shared; }
        @Override void start(String uri,String n,Attributes a) {
            if (!match(uri,S,SS)) return;
            if ("sheetData".equals(n) && depth==2) sheetData = depth;
            if (sheetData==0) return;
            if ("row".equals(n) && depth==sheetData+1) {
                row = depth; implicitColumn = 0; rowOrdinal++;
                rowIndex = positive(a.getValue("r"),1_048_576,rowOrdinal);
                if(rowIndex==0)rowIndex=rowOrdinal;
                java.util.Arrays.fill(cells,null);
            }
            if (row == 0) return;
            if ("c".equals(n) && depth==row+1) {
                cell = depth; type = a.getValue("t"); value.setLength(0); formula = false;
                col = column(a.getValue("r"),implicitColumn);
                implicitColumn = Math.min(16_384, col + 1);
            }
            if (cell == 0 || col < 0 || col >= MAX_COLS || accepted >= MAX_ROWS) return;
            if ("f".equals(n) && depth==cell+1) formula=true; // intentionally never read or run its body
            if ("v".equals(n) && depth==cell+1 || "inlineStr".equals(type) && "t".equals(n)) valueNode=depth;
        }
        @Override void chars(char[] c,int from,int n) {
            if (valueNode>0) appendChars(value,c,from,n,MAX_SEGMENT);
        }
        @Override void end(String uri,String n) {
            if (depth == valueNode) valueNode = 0;
            if (depth == cell) {
                if (col < 0 || col >= MAX_COLS || accepted >= MAX_ROWS) doc.limited = true;
                else if (cells[col] == null) cells[col] = cellValue();
                cell = 0;
            }
            if (depth == row) {
                int count = 0;
                for (String entry : cells) if (entry != null && !entry.isEmpty()) count++;
                if (count > 0 && ++accepted <= MAX_ROWS) {
                    if (text.length() > 0) append(text,"\n",MAX_OUTPUT);
                    append(text,Integer.toString(rowIndex)+"   ",MAX_OUTPUT);
                    boolean first = true;
                    for (int i=0;i<cells.length;i++) {
                        String entry=cells[i];
                        if (entry == null || entry.isEmpty()) continue;
                        if (!first) append(text,"  │  ",MAX_OUTPUT);
                        append(text,(char)('A'+i)+" "+entry,MAX_OUTPUT); first=false;
                    }
                }
                row=0;
            }
            if (depth == sheetData) sheetData=0;
        }
        private String cellValue() {
            String raw = value.toString();
            if ("s".equals(type)) {
                int index = positive(raw,MAX_SHARED,-1);
                if (index < 0 || index >= shared.size()) { doc.limited = true; return "[text outside preview]"; }
                raw = shared.get(index);
            } else if ("b".equals(type)) raw = "1".equals(raw) ? "TRUE" : "0".equals(raw) ? "FALSE" : raw;
            if (raw.isEmpty() && formula) raw = "[formula without saved value]";
            return raw.replace('\n',' ').replace('\r',' ').replace('\t',' ');
        }
    }

    private static final class Slide extends Walker {
        final StringBuilder text = new StringBuilder(), paragraph = new StringBuilder();
        int body, paragraphDepth, textNode, count;
        Slide(Document d) { super(d,"sld",P,PS); }
        @Override void start(String uri,String n,Attributes a) {
            if (match(uri,P,PS) && "cSld".equals(n) && depth==2) body=depth;
            if (body==0 || !match(uri,A,AS)) return;
            if ("p".equals(n) && paragraphDepth==0) { paragraphDepth=depth; paragraph.setLength(0); }
            if (paragraphDepth>0 && "t".equals(n)) textNode=depth;
            if (paragraphDepth>0 && "br".equals(n)) append(paragraph," ",2048);
        }
        @Override void chars(char[] c,int from,int n) {
            if (textNode>0) appendChars(paragraph,c,from,n,2048);
        }
        @Override void end(String uri,String n) {
            if (depth==textNode) textNode=0;
            if (depth==paragraphDepth) {
                String p=paragraph.toString().trim();
                if (!p.isEmpty()) {
                    if (++count > 350) doc.limited=true;
                    else { if (text.length()>0) append(text,"\n",MAX_OUTPUT); append(text,p,MAX_OUTPUT); }
                }
                paragraphDepth=0;
            }
            if (depth==body) body=0;
        }
    }

    private static int column(String ref,int fallback) {
        if (ref == null) return fallback;
        if (ref.length() < 2 || ref.length() > 10) return 16_384;
        int col=0,i=0;
        while (i<ref.length()) {
            char ch=ref.charAt(i);
            if (ch<'A'||ch>'Z') break;
            if (i>=3) return 16_384;
            col=26*col+ch-'A'+1; i++;
        }
        if(i==0||i==ref.length()||positive(ref.substring(i),1_048_576,0)<1) return 16_384;
        return col-1;
    }
    private static int positive(String value,int max,int fallback) {
        if (value==null || value.isEmpty() || value.length()>8) return fallback;
        int result=0;
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c<'0'||c>'9')return fallback;
            result=result*10+c-'0';
            if(result>max)return fallback;
        }
        return result;
    }
    private static boolean match(String uri,String ns1,String ns2) { return ns1.equals(uri) || ns2.equals(uri); }
    private static String cut(String value,int at) {
        if(value.length()<=at)return value;
        if(at>0&&Character.isHighSurrogate(value.charAt(at-1)))at--;
        return value.substring(0,Math.max(0,at));
    }
    private static IOException invalid() {
        return new IOException("This Office file is damaged, encrypted, or uses features the built-in preview doesn't read. Open it in another app.");
    }
    private static Limit limit() { return new Limit(); }
    private static final class Limit extends IOException {
        private static final long serialVersionUID = 1L;
        Limit() { super("This Office file is too complex for a safe built-in preview. Open it in another app."); }
    }

    private OfficePreview() { }
}
