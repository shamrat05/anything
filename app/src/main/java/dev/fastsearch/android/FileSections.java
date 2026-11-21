package dev.fastsearch.android;

import dev.fastsearch.core.FileEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Small section metadata over already indexed rows. Never owns file contents or thumbnails. */
final class FileSections {
    static final FileSections EMPTY=new FileSections(new String[0],new int[0]);
    final String[] labels;
    private final int[] starts;
    private FileSections(String[] labels,int[] starts){this.labels=labels;this.starts=starts;}
    static FileSections build(List<FileEntry> rows,int sort){
        ArrayList<String> labels=new ArrayList<>();ArrayList<Integer> starts=new ArrayList<>();
        ZoneId zone=sort==3?ZoneId.systemDefault():null;
        DateTimeFormatter dates=sort==3?DateTimeFormatter.ofPattern("d MMMM yyyy",Locale.getDefault()):null;
        long dayStart=Long.MIN_VALUE,dayEnd=Long.MIN_VALUE;String dayLabel=null;
        String previous=null,nameLabel=null;int previousFirst=-1;
        for(int i=0;i<rows.size();i++){
            if((i&511)==0&&Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            FileEntry entry=rows.get(i);String label;
            if(sort==3){
                if(entry.modified<=0)label="Date unknown";
                else{if(entry.modified<dayStart||entry.modified>=dayEnd){LocalDate day=Instant.ofEpochMilli(entry.modified).atZone(zone).toLocalDate();
                        dayStart=day.atStartOfDay(zone).toInstant().toEpochMilli();dayEnd=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();dayLabel=dates.format(day);}
                    label=dayLabel;}
            }
            else if(sort==2)label=entry.size<0?"Size unknown":entry.size==0?"No recorded size":entry.size<1024?"Under 1 KB":entry.size<1024*1024?"1 KB to 1 MB":
                    entry.size<10*1024*1024?"1 to 10 MB":entry.size<100*1024*1024?"10 to 100 MB":entry.size<1024L*1024*1024?"100 MB to 1 GB":"1 GB and above";
            else if(sort==1)label=entry.parent.isEmpty()?"/":entry.parent;
            else{int first=entry.name.isEmpty()?'#':entry.name.codePointAt(0);
                if(first!=previousFirst){nameLabel=Character.isLetter(first)?new String(Character.toChars(first)).toUpperCase(Locale.ROOT):Character.isDigit(first)?"0-9":"#";previousFirst=first;}
                label=nameLabel;}
            if(!label.equals(previous)){labels.add(label);starts.add(i);previous=label;}
        }
        int[] positions=new int[starts.size()];for(int i=0;i<positions.length;i++)positions[i]=starts.get(i);
        return new FileSections(labels.toArray(new String[0]),positions);
    }
    int sectionAt(int position){if(starts.length==0)return 0;int found=Arrays.binarySearch(starts,Math.max(0,position));return found>=0?found:Math.max(0,-found-2);}
    int positionAt(int section){return starts.length==0?0:starts[Math.max(0,Math.min(starts.length-1,section))];}
    String labelAt(int position){return labels.length==0?"":labels[sectionAt(position)];}
}
