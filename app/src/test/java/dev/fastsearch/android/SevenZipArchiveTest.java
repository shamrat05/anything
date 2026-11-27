package dev.fastsearch.android;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.zip.CRC32;

public final class SevenZipArchiveTest {
    private static final Path FIXTURES=Paths.get("app/src/test/fixtures/sevenzip");
    private static int assertions;
    public static void main(String[] args) throws IOException {
        Path root=Files.createTempDirectory("sevenzip-test-");
        try {
            for(String name:new String[]{"copy","lzma","lzma2-solid"}){
                Path source=fixture(name);
                SevenZipArchive.Listing listed=inspect(source);
                check(listed.count==5,"independently encoded "+name+" directory has all entries");
                check(listed.entries.stream().anyMatch(e->e.name.equals("docs/雪.txt")&&!e.directory&&e.size>0),
                        "Unicode entry is visible");
                check(listed.entries.stream().anyMatch(e->e.name.equals("empty-dir")&&e.directory),"empty folder is visible");
                SevenZipArchive.Entry wanted=listed.entries.stream().filter(e->e.name.equals("docs/雪.txt")).findFirst().get();
                java.io.ByteArrayOutputStream member=new java.io.ByteArrayOutputStream();
                try(FileChannel channel=FileChannel.open(source)){SevenZipArchive.copyEntry(channel,wanted,member);}
                check(new String(member.toByteArray(),StandardCharsets.UTF_8).equals("Note from a synthetic 7z fixture.\n"),
                        "selected member preview verifies exact bytes for "+name);
                Path out=Files.createDirectory(root.resolve(name));
                pull(source,out);
                check(Files.isDirectory(out.resolve("empty-dir")),"empty directory preserved");
                check(Files.isRegularFile(out.resolve("empty.txt"))&&Files.size(out.resolve("empty.txt"))==0,
                        "empty file distinguished from empty directory");
                check(new String(Files.readAllBytes(out.resolve("docs/雪.txt")),StandardCharsets.UTF_8)
                        .equals("Note from a synthetic 7z fixture.\n"),"exact Unicode file bytes");
                byte[] actual=Files.readAllBytes(out.resolve("top.bin"));
                check(actual.length==256*437,"exact decompressed size");
                boolean all=true; for(int i=0;i<actual.length;i++)if(actual[i]!=(byte)i)all=false;
                check(all,"exact binary data in "+name);
                Files.write(out.resolve("docs/雪.txt"),new byte[]{99});
                refuse(()->pull(source,out),null);
                check(Arrays.equals(Files.readAllBytes(out.resolve("docs/雪.txt")),new byte[]{99}),"never replaces an existing file");
            }

            Path target=Files.createDirectory(root.resolve("bad-output"));
            for(String bad:new String[]{"encrypted","symlink","filtered"}) {
                refuse(()->inspect(fixture(bad)),"unsupported");
                refuse(()->pull(fixture(bad),target),"unsupported");
            }
            refuse(()->inspect(fixture("large-window")),"decoder window");
            try(java.util.stream.Stream<Path> children=Files.list(target)){
                check(children.count()==0,"all metadata refusals precede staging writes");
            }

            Path copy=fixture("copy"), bad=root.resolve("modified.7z");
            byte[] original=Files.readAllBytes(copy), bytes=original.clone();
            bytes[32]^=5;
            Files.write(bad,bytes);
            check(inspect(bad).count==5,"listing never decompresses entry contents");
            refuse(()->pull(bad,target),"integrity");
            ArchiveExtraction.removeStaging(target);
            Files.createDirectory(target);

            bytes=original.clone();
            bytes[bytes.length-2]^=3;
            Files.write(bad,bytes);
            refuse(()->inspect(bad),"integrity");
            Files.write(bad,Arrays.copyOf(original,64));
            refuse(()->inspect(bad),"split");

            bytes=original.clone();
            byte[] name="docs/雪.txt".getBytes(StandardCharsets.UTF_16LE);
            int at=find(bytes,name);
            check(at>32,"raw filename present in plain header");
            System.arraycopy("../x/雪.txt".getBytes(StandardCharsets.UTF_16LE),0,bytes,at,name.length);
            recheckHeader(bytes);
            Files.write(bad,bytes);
            refuse(()->pull(bad,target),"unsafe");
            check(!Files.exists(root.resolve("x")),"no writes outside private tree");

            Thread.currentThread().interrupt();
            try {refuse(()->inspect(copy),"Preview stopped");}
            finally {Thread.interrupted();}
            try(java.util.stream.Stream<Path> children=Files.list(target)){
                check(children.count()==0,"malformed paths and cancellation leave staging untouched");
            }
        } finally { ArchiveExtraction.removeStaging(root); }
        System.out.println("PASS "+assertions+" 7z curated reader, decoder, and staging checks.");
    }

    private static int find(byte[] bytes,byte[] sample){
        for(int i=0;i<=bytes.length-sample.length;i++){
            int j=0;while(j<sample.length&&bytes[i+j]==sample[j])j++;
            if(j==sample.length)return i;
        }
        return -1;
    }
    private static void recheckHeader(byte[] bytes) {
        ByteBuffer buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int start=32+(int)buffer.getLong(12);
        CRC32 crc=new CRC32();crc.update(bytes,start,bytes.length-start);buffer.putInt(28,(int)crc.getValue());
        crc.reset();crc.update(bytes,12,20);buffer.putInt(8,(int)crc.getValue());
    }
    private interface Run {void run()throws IOException;}
    private static void refuse(Run run,String fragment) throws IOException {
        try { run.run(); }
        catch(IOException failure) {
            check(fragment==null||String.valueOf(failure.getMessage()).contains(fragment),"refusal for "+fragment+", actual "+failure.getMessage());
            return;
        }
        throw new AssertionError("Accepted archive that should be refused: "+fragment);
    }
    private static Path fixture(String name){return FIXTURES.resolve(name+".7z");}
    private static SevenZipArchive.Listing inspect(Path path)throws IOException{
        try(FileChannel channel=FileChannel.open(path)){return SevenZipArchive.inspect(channel);}
    }
    private static void pull(Path path,Path out)throws IOException{
        try(FileChannel channel=FileChannel.open(path)){SevenZipArchive.extract(channel,out);}
    }
    private static void check(boolean success,String detail){
        assertions++;if(!success)throw new AssertionError(detail);
    }
}
