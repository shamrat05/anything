package dev.fastsearch.android;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class CsvPreviewTest {
    private static int assertions;
    public static void main(String[] args)throws Exception{
        check(CsvPreview.format("notes.CSV")==CsvPreview.Format.CSV,"CSV extension, case independent");
        check(CsvPreview.format("items.TSV")==CsvPreview.Format.TSV,"TSV extension, case independent");
        check(CsvPreview.format("backup.csv.backup")==null&&CsvPreview.format("report.xlsx")==null,"unrelated formats stay outside");
        check("text/csv".equals(CsvPreview.Format.CSV.mime)&&"text/tab-separated-values".equals(CsvPreview.Format.TSV.mime),"dedicated external handler MIMEs");
        CsvPreview.Result result=preview("Name,Note,Amount\r\nAlice,\"comma, preserved\",42\r\n"
                +"Bob,\"says \"\"hello\"\"\r\nnext line\",",CsvPreview.Format.CSV,false);
        same(3,result.columns,"widest row establishes columns");
        same(3,result.rows.size(),"CRLF and quoted CRLF are distinguished");
        row(result,0,"Name","Note","Amount");
        row(result,1,"Alice","comma, preserved","42");
        row(result,2,"Bob","says \"hello\"\nnext line","");
        check(!result.limited,"complete compact CSV not reported partial");
        result=preview("a\tb\tc\n1\t\"x\ty\"\t\n\t\t",CsvPreview.Format.TSV,false);
        row(result,0,"a","b","c");row(result,1,"1","x\ty","");row(result,2,"","","");
        result=preview("sep=;\r\nItem;Cost;Ready\nPen;12,4;yes\n",CsvPreview.Format.CSV,false);
        row(result,0,"Item","Cost","Ready");row(result,1,"Pen","12,4","yes");
        check(result.rows.size()==2,"only explicit delimiter directive is skipped");
        row(preview("a;b\n",CsvPreview.Format.CSV,false),0,"a;b");
        row(preview("\"x\" \t,description with 6\" board",CsvPreview.Format.CSV,false),0,"x","description with 6\" board");
        row(preview(",x,,\n",CsvPreview.Format.CSV,false),0,"","x","","");
        result=preview("a,b\n\nfield",CsvPreview.Format.CSV,false);
        row(result,0,"a","b");row(result,1,"");row(result,2,"field");
        check(preview("",CsvPreview.Format.CSV,false).rows.isEmpty(),"empty document");
        check(preview("a,b\n",CsvPreview.Format.CSV,false).rows.size()==1,"a terminator does not invent another row");
        row(preview("=SUM(A1:A2),+1,@value,https://example.invalid/a",CsvPreview.Format.CSV,false),0,
                "=SUM(A1:A2)","+1","@value","https://example.invalid/a");
        byte[] utf16="বাংলা\t東京\n".getBytes(StandardCharsets.UTF_16);
        row(CsvPreview.parse(PreviewText.read(new ByteArrayInputStream(utf16)),CsvPreview.Format.TSV,false),0,"বাংলা","東京");
        reject("\"unterminated output");reject("\"closed\"invalid");

        String many=repeat("a,b,c,d,e,f,g\n",CsvPreview.MAX_ROWS+3);
        result=preview(many,CsvPreview.Format.CSV,false);
        check(result.limited&&result.rows.size()==CsvPreview.MAX_ROWS&&result.columns==7,"full row limit reported");
        result=preview(many,CsvPreview.Format.CSV,true);
        check(result.limited&&result.rows.size()==CsvPreview.QUICK_ROWS&&result.columns==CsvPreview.QUICK_COLS,
                "quick preview independent row and column limits");
        check(!preview(repeat("a\n",CsvPreview.QUICK_ROWS),CsvPreview.Format.CSV,true).limited,
                "exact compact row limit retains accurate completeness");
        result=preview(repeat("b,",CsvPreview.MAX_COLS+2)+"\n",CsvPreview.Format.CSV,false);
        check(result.limited&&result.columns==CsvPreview.MAX_COLS,"full width limit reported");
        result=preview(repeat("a",CsvPreview.QUICK_CELL-1)+"🙂,next",CsvPreview.Format.CSV,true);
        check(result.limited&&result.rows.get(0).get(0).length()==CsvPreview.QUICK_CELL-1,
                "clip never splits a supplementary Unicode character");
        row(result,0,repeat("a",CsvPreview.QUICK_CELL-1),"next");
        result=CsvPreview.parse(new PreviewText.Result("good,row\n\"unfinished","UTF-8",true),
                CsvPreview.Format.CSV,false);
        check(result.limited&&result.rows.size()==1,"truncated input omits incomplete last row instead of guessing");
        result=CsvPreview.parse(new PreviewText.Result("good,row\ntruncated,val","UTF-8",true),
                CsvPreview.Format.CSV,false);
        check(result.limited&&result.rows.size()==1,"truncated unquoted data also omits incomplete row");
        try{result.rows.add(Collections.emptyList());throw new AssertionError("mutable rows");}
        catch(UnsupportedOperationException expected){assertions++;}
        try{result.rows.get(0).add("wrong");throw new AssertionError("mutable cells");}
        catch(UnsupportedOperationException expected){assertions++;}
        check(result.memoryBytes()>64,"structured payload included in cache accounting");
        try{
            PreviewText.read(new ByteArrayInputStream(new byte[]{'X',0,'Y'}));
            throw new AssertionError("Binary data passed the text reader");
        }catch(java.io.IOException expected){check(true,"binary input never becomes delimited cells");}
        Thread.currentThread().interrupt();
        try{
            try{
                CsvPreview.parse(new PreviewText.Result("a,b","UTF-8",false),CsvPreview.Format.CSV,false);
                throw new AssertionError("Cancellation ignored");
            }catch(java.io.IOException expected){check(Thread.currentThread().isInterrupted(),"cancellation preserves interrupt");}
        }finally{Thread.interrupted();}
        System.out.println("PASS "+assertions+" bounded CSV/TSV preview assertions.");
    }
    private static CsvPreview.Result preview(String value,CsvPreview.Format type,boolean compact)throws Exception{
        return CsvPreview.parse(PreviewText.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))),type,compact);
    }
    private static void reject(String bad)throws Exception{
        try{preview(bad,CsvPreview.Format.CSV,false);throw new AssertionError("Accepted invalid quoting");}
        catch(CsvPreview.Malformed expected){assertions++;}
    }
    private static void row(CsvPreview.Result result,int row,String... values){
        List<String> expected=Arrays.asList(values);
        same(expected,result.rows.get(row),"row "+(row+1));
    }
    private static String repeat(String value,int count){StringBuilder text=new StringBuilder();while(count-->0)text.append(value);return text.toString();}
    private static void same(Object expected,Object actual,String label){check(expected.equals(actual),label+" expected "+expected+", got "+actual);}
    private static void check(boolean pass,String label){assertions++;if(!pass)throw new AssertionError(label);}
}
