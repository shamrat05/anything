package dev.fastsearch.android;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Bounded read-only CSV / TSV cells. Values are literal text and are never evaluated. */
final class CsvPreview {
    enum Format {
        CSV(',',"CSV","text/csv"), TSV('\t',"TSV","text/tab-separated-values");
        final char delimiter;
        final String label,mime;
        Format(char delimiter,String label,String mime){this.delimiter=delimiter;this.label=label;this.mime=mime;}
    }
    static final int MAX_ROWS=80, MAX_COLS=16, MAX_CELL=640;
    static final int QUICK_ROWS=12, QUICK_COLS=6, QUICK_CELL=160;

    static final class Result {
        final List<List<String>> rows;
        final int columns;
        final boolean limited;
        final Format format;
        Result(Format format,List<List<String>> rows,int columns,boolean limited){
            this.format=format;this.rows=Collections.unmodifiableList(rows);
            this.columns=columns;this.limited=limited;
        }
        int memoryBytes(){
            int bytes=64;
            for(List<String> row:rows){
                bytes+=48;
                for(String value:row)bytes+=48+2*value.length();
            }
            return bytes;
        }
    }
    static final class Malformed extends IOException {
        private static final long serialVersionUID=1L;
        Malformed(){super("This file has an incomplete quoted field or an invalid field separator.");}
    }
    static Format format(String name){
        if(name==null)return null;
        String lower=name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".csv")?Format.CSV:lower.endsWith(".tsv")?Format.TSV:null;
    }

    static Result parse(PreviewText.Result source,Format format,boolean compact)throws IOException{
        if(format==null)throw new IllegalArgumentException("A table format is required.");
        final int maxRows=compact?QUICK_ROWS:MAX_ROWS,maxColumns=compact?QUICK_COLS:MAX_COLS,
                maxCell=compact?QUICK_CELL:MAX_CELL;
        String value=source.text;
        char delimiter=format.delimiter;
        int at=0;
        // Excel can explicitly specify a CSV delimiter in the first line. No locale guessing.
        if(format==Format.CSV&&value.length()>=6&&value.regionMatches(true,0,"sep=",0,4)
                &&",;\t|".indexOf(value.charAt(4))>=0&&(value.charAt(5)=='\r'||value.charAt(5)=='\n')){
            delimiter=value.charAt(4);at=6;
            if(value.charAt(5)=='\r'&&at<value.length()&&value.charAt(at)=='\n')at++;
        }
        List<List<String>> rows=new ArrayList<>();
        ArrayList<String> row=new ArrayList<>();
        StringBuilder cell=new StringBuilder();
        boolean quoted=false,afterQuote=false,fieldStart=true,rowStarted=false,limited=source.truncated;
        int columns=0,steps=0;
        while(at<value.length()){
            if((steps++&255)==0)PreviewText.cancelled();
            char ch=value.charAt(at++);
            if(quoted){
                if(ch=='"'){
                    if(at<value.length()&&value.charAt(at)=='"'){at++;ch='"';}
                    else{quoted=false;afterQuote=true;continue;}
                }else if(ch=='\r'){
                    if(at<value.length()&&value.charAt(at)=='\n')at++;
                    ch='\n';
                }
                if(row.size()<maxColumns){
                    if(cell.length()<maxCell)cell.append(ch);else limited=true;
                }else limited=true;
                continue;
            }
            if(ch==delimiter||ch=='\r'||ch=='\n'){
                if(row.size()<maxColumns)flush(row,cell);else limited=true;
                cell.setLength(0);afterQuote=false;fieldStart=true;rowStarted=true;
                if(ch!=delimiter){
                    if(ch=='\r'&&at<value.length()&&value.charAt(at)=='\n')at++;
                    columns=Math.max(columns,row.size());rows.add(Collections.unmodifiableList(row));
                    row=new ArrayList<>();rowStarted=false;
                    if(rows.size()>=maxRows){
                        limited|=at<value.length();
                        return new Result(format,rows,columns,limited);
                    }
                }
                continue;
            }
            if(afterQuote){
                if(ch==' '||ch=='\t')continue;
                throw new Malformed();
            }
            if(ch=='"'&&fieldStart){quoted=true;fieldStart=false;rowStarted=true;continue;}
            rowStarted=true;fieldStart=false;
            if(row.size()<maxColumns){
                if(cell.length()<maxCell)cell.append(ch);else limited=true;
            }else limited=true;
        }
        PreviewText.cancelled();
        if(!source.truncated){
            if(quoted)throw new Malformed();
            if(rowStarted){
                if(row.size()<maxColumns)flush(row,cell);else limited=true;
                columns=Math.max(columns,row.size());rows.add(Collections.unmodifiableList(row));
            }
        }
        return new Result(format,rows,columns,limited);
    }

    private static void flush(List<String> row,StringBuilder cell){
        int length=cell.length();
        if(length>0&&Character.isHighSurrogate(cell.charAt(length-1)))length--;
        row.add(cell.substring(0,length));
    }
    private CsvPreview(){}
}
