package dev.johnmart19.veritycompanion;
import java.util.*;
import java.nio.charset.StandardCharsets;
/** Prioritized bounded previews; truncation is explicit and cannot silently erase requested evidence. */
final class SnapshotSections {
 private record Section(String label,String value,int priority,int cap){}
 private final List<Section> sections=new ArrayList<>();
 private final String header;private final int budget;
 SnapshotSections(String header,int budget){this.header=header;this.budget=budget;}
 void add(String label,String value,int priority,int cap){sections.add(new Section(label,value,priority,cap));}
 static String prefix(String text,int budget){
  int used=0,end=0;while(end<text.length()){
   int point=text.codePointAt(end),bytes=point<=127?1:point<=2047?2:point<=65535?3:4;
   if(used+bytes>budget)break;used+=bytes;end+=Character.charCount(point);
  }return text.substring(0,end);
 }
 static int bytes(String text){return text.getBytes(StandardCharsets.UTF_8).length;}
 String render(){
  String marker="\n[Snapshot previews may omit details. Use the named paginated tool for complete/current data; omitted facts are unknown.]";
  int remaining=budget-bytes(marker);StringBuilder out=new StringBuilder(prefix(header,Math.max(0,remaining)));
  sections.sort(Comparator.comparingInt(Section::priority).reversed());
  for(Section section:sections){
   String label="\n"+section.label()+": ",shortened=" [preview truncated]";
   int available=Math.min(section.cap(),remaining-bytes(out.toString())-bytes(label)-bytes(shortened));
   if(available<96)continue;
   String value=prefix(section.value(),available);out.append(label).append(value);if(value.length()<section.value().length())out.append(shortened);
  }
  return out.append(marker).toString();
 }
}
