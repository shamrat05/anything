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

        void parse(String part, Walker handler) throws IOException {
            ZipDirectory.Entry entry = entries.get(part);
            if (entry == null || entry.special || entry.encrypted || entry.crc < 0) throw invalid();
            if (entry.size < 0 || entry.size > MAX_PART_BYTES || entry.size > MAX_TOTAL_BYTES - expanded) throw limit();
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
                    expanded += bytes.count;
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
        int position;
        Index(Document d, boolean sheet) {
            super(d,sheet?"workbook":"presentation",sheet?S:P,sheet?SS:PS); this.sheet = sheet;
        }
        @Override void start(String uri, String name, Attributes attrs) {
            if (depth != 3 || !match(uri,ns1,ns2) || !(sheet ? "sheet" : "sldId").equals(name)) return;
            int order = ++position;
            if (parts.size() >= (sheet ? MAX_SHEETS : MAX_SLIDES)) { doc.limited = true; return; }
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
