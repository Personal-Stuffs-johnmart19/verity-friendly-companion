package dev.johnmart19.veritycompanion;

import java.util.*;

/** Lossless bounded detail pages. Offsets address chunks, so no authored text is discarded. */
final class QuestDetailPage {
 record Line(String section,String text) {}
 record Page(List<String> rows,int matched,int offset,int next) {}
 static final int CHUNK=900;
 static Page page(List<Line> lines,String section,int offset,int limit) {
  if(!Set.of("all","text","requirements","tasks","rewards").contains(section))throw new IllegalArgumentException("section: all, text, requirements, tasks or rewards");
  QuestQuery.page(List.of(),"","","all",offset,limit);
  List<String> chunks=new ArrayList<>();
  for(Line line:lines)if(section.equals("all")||line.section().equals(section)) {
   String value=line.text();
   if(value.isEmpty())chunks.add("");
   for(int start=0;start<value.length();) {
    int end=Math.min(value.length(),start+CHUNK);
    if(end<value.length()&&Character.isHighSurrogate(value.charAt(end-1)))end--;
    chunks.add(value.substring(start,end));start=end;
   }
  }
  int start=Math.min(offset,chunks.size()),end=(int)Math.min(chunks.size(),(long)offset+limit);
  return new Page(List.copyOf(chunks.subList(start,end)),chunks.size(),offset,end<chunks.size()?end:-1);
 }
}
