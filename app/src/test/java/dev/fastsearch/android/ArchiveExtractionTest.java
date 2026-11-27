package dev.fastsearch.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ArchiveExtractionTest {
    private static int assertions;
    private static void check(boolean condition, String detail) {
        assertions++;
        if(!condition) throw new AssertionError(detail);
    }

    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("archive-extraction-test-");
        try {
            Path zip=root.resolve("notes.zip");
            write(zip,"Docs/","Docs/雪.txt","Empty/");
            Path output=Files.createDirectory(root.resolve("output"));
            extract(zip,output);
            check(Files.isDirectory(output.resolve("Empty")),"empty directory preserved");
            check(new String(Files.readAllBytes(output.resolve("Docs/雪.txt")),StandardCharsets.UTF_8)
                    .equals("test bytes"),"literal name and exact bytes extracted");
            expectFailure(zip,output);
            check(new String(Files.readAllBytes(output.resolve("Docs/雪.txt")),StandardCharsets.UTF_8)
                    .equals("test bytes"),"existing file not overwritten");

            Path invalid=Files.createDirectory(root.resolve("invalid"));
            for(String name:new String[]{"../outside.txt","/absolute.txt","x/../bad.txt","x//bad.txt","x\\bad.txt","x:\\bad.txt"}) {
                write(zip,"good.txt",name);
                expectFailure(zip,invalid);
                check(count(invalid)==0,"whole archive validated before writing "+name);
            }
            check(!Files.exists(root.resolve("outside.txt")),"nothing written outside the fresh tree");
            write(zip,"path","path/conflict.txt");
            expectFailure(zip,invalid);
            check(count(invalid)==0,"file/directory collision rejected before writing");

            write(zip,"file.txt");
            byte[] damaged=Files.readAllBytes(zip);
            for(int i=0;i<damaged.length-46;i++){
                if(damaged[i]==0x50&&damaged[i+1]==0x4b&&damaged[i+2]==1&&damaged[i+3]==2){
                    damaged[i+16]^=1;break;
                }
            }
            Files.write(zip,damaged,StandardOpenOption.TRUNCATE_EXISTING);
            expectFailure(zip,invalid);
            ArchiveExtraction.removeStaging(invalid);
            check(!Files.exists(invalid),"partial private staging is cleaned after integrity failure");
            check(ArchiveExtraction.supports("zip")&&!ArchiveExtraction.supports("apk")
                    &&!ArchiveExtraction.supports("rar"),"only supported container types expose extraction");
        } finally { ArchiveExtraction.removeStaging(root); }
        System.out.println("PASS " + assertions + " archive extraction checks.");
    }

    private static void write(Path zip,String... names)throws IOException {
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))) {
            for(String name:names){
                out.putNextEntry(new ZipEntry(name));
                if(!name.endsWith("/"))out.write("test bytes".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    private static long count(Path directory)throws IOException {
        try(java.util.stream.Stream<Path> children=Files.list(directory)){return children.count();}
    }

    private static void extract(Path zip,Path out)throws IOException {
        try(FileChannel input=FileChannel.open(zip,StandardOpenOption.READ)) {ArchiveExtraction.extract(input,out);}
    }

    private static void expectFailure(Path zip,Path out)throws IOException {
        boolean refused=false;
        try { extract(zip,out); } catch(IOException expected) { refused=true; }
        check(refused,"unsafe or damaged archive was rejected");
    }
}
