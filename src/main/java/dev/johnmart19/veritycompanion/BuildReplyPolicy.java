package dev.johnmart19.veritycompanion;
import java.util.Locale;
/** Allow missing construction details to be discussed without inventing completed work. */
final class BuildReplyPolicy {
 static boolean clarification(String reply,boolean mutationObserved){
  if(mutationObserved||reply==null||reply.length()>1500)return false;
  String s=reply.toLowerCase(Locale.ROOT);
  if(!s.contains("?")||s.matches("(?s).*(построен|построил|построила|готово|готов дом|дом готов|завершен|сделано|built|completed|finished|construction is done|all done).*"))return false;
  return s.matches("(?s).*(из чего|что именно|како[йегую]|где|сколько|размер|материал|площадк|место|стиль|what|where|which|how (?:big|wide|tall|many)|material|dimensions|location|style).*" )
   &&!s.matches("(?s).*(подтверд|соглас|разреш|точно|are you sure|confirm|permission|authorize).*" );
 }
}
