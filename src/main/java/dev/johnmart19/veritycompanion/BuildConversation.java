package dev.johnmart19.veritycompanion;
import java.util.*;
/** Short-lived per-player intent; actual build existence always comes from the disk journal. */
public final class BuildConversation {
 private static final long WINDOW=600_000L;private static final int MAX_PLAYERS=256;
 private record Turn(long time,String human,boolean building,boolean undo,boolean followup,boolean journal,String pendingRequest){}
 private static final Map<UUID,Turn> TURNS=new LinkedHashMap<>();
 private BuildConversation(){}
 private static void sweep(long now){TURNS.entrySet().removeIf(entry->now<entry.getValue().time||now-entry.getValue().time>=WINDOW);}
 private static String raw(String human){return human==null?"":human;}
 private static boolean clarification(String text){
  if(text.length()>120||text.matches("(?s).*[\"'«»„“”`?\\n\\r].*")||text.matches("(?s).*(?<!\\p{L})(?:не|нет|без|отмена|отмени|стоп|not|no|never|cancel|stop|если|if|потом|later)(?!\\p{L}).*"))return false;
  return text.matches("(?:из|of|from|out of) [\\p{L}\\p{N}_:/-]+(?: [\\p{L}\\p{N}_:/-]+){0,4}[.!]*")
   ||text.matches("(?:здесь|тут|рядом со мной|возле меня|на этой площадке|here|right here|near me|next to me|on this spot)[.!]*");
 }
 public static synchronized void begin(UUID player,String human,boolean hasAppliedJournal,long now){sweep(now);if(player==null)return;if(human!=null&&human.length()>1024){TURNS.remove(player);return;}String text=BuildLanguage.normalized(human),current=raw(human);
  boolean undo=BuildLanguage.isUndo(human)&&hasAppliedJournal;boolean direct=BuildLanguage.isBuild(human);boolean followup=hasAppliedJournal&&BuildLanguage.isModification(human);
  Turn prior=TURNS.get(player);boolean continuation=!direct&&!hasAppliedJournal&&prior!=null&&!prior.pendingRequest.isEmpty()&&clarification(text);
  String pending=!hasAppliedJournal?(direct?current:continuation?prior.pendingRequest:""):"";
  if(TURNS.size()>=MAX_PLAYERS&&!TURNS.containsKey(player))TURNS.remove(TURNS.keySet().iterator().next());
  TURNS.put(player,new Turn(continuation?prior.time:now,current,direct||followup||continuation,undo,followup,hasAppliedJournal,pending));
 }
 private static Turn current(UUID player,String human,long now){sweep(now);Turn turn=TURNS.get(player);return turn!=null&&turn.human.equals(raw(human))?turn:null;}
 public static synchronized boolean allowsBuild(UUID player,String human,long now){Turn turn=current(player,human,now);return turn!=null&&turn.building;}
 public static synchronized boolean allowsUndo(UUID player,String human,long now){Turn turn=current(player,human,now);return turn!=null&&turn.undo;}
 public static synchronized String context(UUID player,String human,long now){return context(player,human,now,"en");}
 public static synchronized String context(UUID player,String human,long now,String language){Turn turn=current(player,human,now);if(turn==null)return "";
  if(turn.undo)return "BUILD INTENT: The current human explicitly requests removing the last journaled build. Use undo_build for only this player's verified journal. Do not claim that nothing was built. Never delete unrelated world blocks.";
  if(turn.followup)return "BUILD INTENT: The current human explicitly requests modifying the last journaled build. This is valid building consent without repeating 'build'. Use actual journal bounds and preserve unrelated player blocks. Do not require a special keyword or an additional yes.";
  if(turn.building&&!turn.pendingRequest.isEmpty()&&!turn.pendingRequest.equals(turn.human))return "BUILD INTENT: This human message clarifies material or location for their recent construction request: "+turn.pendingRequest+". Continue that requested construction using the human's clarification; no repeated build keyword or confirmation is required. No previous completed build is established.";
  if(turn.building)return "BUILD INTENT: The current human explicitly requests construction. A request to make a beautiful house is valid construction consent. Survey and construct a recognizable house, rather than answering with a promise.";
  return turn.journal?"BUILD INTENT: A journal exists, but this message does not authorize construction or removal. Bare yes or ordinary chat does not authorize world changes.":"BUILD INTENT: No verified applied journal was provided for this dimension. Ask a short clarification for an unspecified modification; do not invent a previous build.";
 }
 public static synchronized void clear(UUID player){TURNS.remove(player);}
 public static synchronized void clearAll(){TURNS.clear();}
}
