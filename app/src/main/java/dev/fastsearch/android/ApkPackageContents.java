package dev.fastsearch.android;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.util.Locale;
import java.util.zip.CRC32;

/** Package filenames are never filesystem destinations. Only bounded literal text and images decode. */
final class ApkPackageContents {
    enum Format { NONE, TEXT, XML, IMAGE, RESOURCE_TABLE }
    private static final int MAX_TEXT = 512 * 1024;
    private static final int MAX_IMAGE = 4 * 1024 * 1024;

    private ApkPackageContents() {}

    static String type(ZipDirectory.Entry entry) {
        if (entry.directory) return "Folder";
        if (entry.special) return "Special ZIP entry";
        if (entry.encrypted) return "Encrypted entry";
        if ("AndroidManifest.xml".equals(entry.name)) return "Android manifest";
        if ("resources.arsc".equals(entry.name)) return "Compiled resources";
        if (ZipDirectory.isDexEntry(entry.name)) return "DEX bytecode";
        String extension = extension(entry.name);
        if ("so".equals(extension)) return "Native library";
        if (format(entry) == Format.IMAGE) return "Image";
        if (format(entry) == Format.TEXT) return "Text";
        if (format(entry) == Format.XML) return "XML";
        return extension.isEmpty() ? "File" : extension.toUpperCase(Locale.ROOT) + " file";
    }

    static Format format(ZipDirectory.Entry entry) {
        String raw = entry.rawName;
        if (entry.directory || entry.special || entry.encrypted || raw == null || !raw.equals(entry.name)
                || raw.indexOf('\\') >= 0 || (entry.method != 0 && entry.method != 8)) return Format.NONE;
        for (String part : raw.split("/",-1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part) || part.indexOf(':')>=0) return Format.NONE;
        }
        if("resources.arsc".equals(raw))return Format.RESOURCE_TABLE;
        switch (extension(raw)) {
            case "png": case "jpg": case "jpeg": case "webp": case "gif": case "bmp":
                return Format.IMAGE;
            case "xml": return Format.XML;
            case "txt": case "md": case "json": case "csv": case "properties":
            case "ini": case "cfg": case "yaml": case "yml": case "html": case "htm":
            case "js": case "css": case "java": case "kt": case "py": case "smali":
                return Format.TEXT;
            default:
                return Format.NONE;
        }
    }

    static byte[] readVerified(FileChannel file, ZipDirectory.Entry entry, Format type) throws IOException {
        if (type == Format.NONE || type != format(entry)) throw new IOException("No preview for this entry type.");
        int max=type==Format.TEXT?MAX_TEXT:type==Format.XML?ApkBinaryXml.MAX_BYTES
                :type==Format.RESOURCE_TABLE?ApkResources.MAX_BYTES:MAX_IMAGE;
        if (entry.size < 0 || entry.size > max || entry.compressedSize > max + 64L * 1024) {
            throw new IOException(type == Format.TEXT ? "Text entries over 512 KiB aren’t previewed."
                                                       : type==Format.XML?"XML entries over 1 MiB aren’t previewed."
                    :type==Format.RESOURCE_TABLE?"Resource tables over 8 MiB aren’t previewed."
                    :"Images over 4 MiB aren’t previewed.");
        }
        if (entry.crc < 0) throw new IOException("Cannot verify this ZIP entry.");
        byte[] bytes = new byte[(int)entry.size];
        CRC32 crc = new CRC32();
        try (InputStream input = ZipDirectory.open(file,entry)) {
            for (int start=0; start<bytes.length;) {
                PreviewText.cancelled();
                int count=input.read(bytes,start,Math.min(32*1024,bytes.length-start));
                if(count<=0)throw new IOException("The package entry is incomplete.");
                crc.update(bytes,start,count);
                start+=count;
            }
            if(input.read()!=-1||crc.getValue()!=entry.crc)throw new IOException("The package entry failed its size or checksum check.");
        }
        return bytes;
    }

    static String decodeXml(byte[] verified)throws IOException{return decodeXml(verified,null);}
    static String decodeXml(byte[] verified,ApkResources.Table table)throws IOException {
        if(ApkBinaryXml.looksLike(verified))return ApkBinaryXml.decode(verified,table==null?null:table::name);
        PreviewText.Result plain=PreviewText.read(new ByteArrayInputStream(verified));
        if(!plain.text.trim().startsWith("<"))throw new IOException("This package entry is not readable XML.");
        return plain.truncated?plain.text+"\n… Preview shortened.":plain.text;
    }

    private static String extension(String name) {
        int dot=name.lastIndexOf('.');
        if(dot<=name.lastIndexOf('/')||dot==name.length()-1) return "";
        String suffix=name.substring(dot+1);
        for(int i=0;i<suffix.length();i++) {
            char c=suffix.charAt(i);
            if(!((c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9')))return "";
        }
        return suffix.length()<=12?suffix.toLowerCase(Locale.ROOT):"";
    }
}
