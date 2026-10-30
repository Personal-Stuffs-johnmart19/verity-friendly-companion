package dev.johnmart19.veritycompanion;

import java.util.*;

/** Immutable, bounded view policy; no game state or mutating quest APIs. */
final class QuestQuery {
 record Row(String id,String chapter,String title,boolean completed,boolean available,int progress) {}
 record Page(List<Row> rows,int matched,int offset,int next) {}
 static Page page(List<Row> snapshot,String query,String chapter,String status,int offset,int limit) {
  if(!Set.of("all","available","completed","incomplete","in_progress","locked").contains(status))
   throw new IllegalArgumentException("status: all, available, completed, incomplete, in_progress or locked");
  if(query.length()>160||chapter.length()>160)throw new IllegalArgumentException("Quest filters must be at most 160 characters");
  if(offset<0||offset>100000||limit<1||limit>30)throw new IllegalArgumentException("offset: 0..100000; limit: 1..30");
  String q=query.toLowerCase(Locale.ROOT),c=chapter.toLowerCase(Locale.ROOT);
  List<Row> matches=snapshot.stream().filter(r->(r.id()+" "+r.title()).toLowerCase(Locale.ROOT).contains(q))
   .filter(r->r.chapter().toLowerCase(Locale.ROOT).contains(c)).filter(r->switch(status){
    case "available"->r.available()&&!r.completed();case "completed"->r.completed();case "incomplete"->!r.completed();
    case "in_progress"->!r.completed()&&r.progress()>0;case "locked"->!r.completed()&&!r.available();default->true;
   }).toList();
  int end=(int)Math.min(matches.size(),(long)offset+limit),start=Math.min(offset,matches.size());
  return new Page(List.copyOf(matches.subList(start,end)),matches.size(),offset,end<matches.size()?end:-1);
 }
 static String bounded(String text,int max){return text.length()<=max?text:text.substring(0,max)+" [truncated]";}
 static boolean adapterPresent(ClassLoader loader){
  try{Class.forName("dev.ftb.mods.ftbquests.quest.ServerQuestFile",false,loader);return true;}
  catch(ClassNotFoundException|LinkageError missing){return false;}
 }
}
