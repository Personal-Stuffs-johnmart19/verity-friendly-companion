package dev.johnmart19.veritycompanion;

import java.util.*;

/** Ephemeral per-player observations; no chat text or persisted world data. */
final class RecentWorldEventLog {
 static final int MAX_PLAYERS=128, MAX_EVENTS=24;
 static final long TTL_MILLIS=10*60*1000L;
 record Entry(long time,String dimension,UUID entity,String type,String name,String evidence,String rarity,
              int x,int y,int z,boolean loadedFromDisk) {}
 private final LinkedHashMap<UUID,ArrayDeque<Entry>> players=new LinkedHashMap<>();
 synchronized void add(UUID player,Entry event,long now){
  prune(now);
  ArrayDeque<Entry> queue=players.get(player);
  if(queue==null){
   if(players.size()>=MAX_PLAYERS)players.remove(players.keySet().iterator().next());
   queue=new ArrayDeque<>();players.put(player,queue);
  }
  // A chunk reload/duplicate join must not flood the player's recent context.
  for(Entry previous:queue)if(previous.entity().equals(event.entity())&&previous.dimension().equals(event.dimension()))return;
  while(queue.size()>=MAX_EVENTS)queue.removeFirst();
  queue.addLast(event);
 }
 synchronized List<Entry> recent(UUID player,String dimension,long now,int limit){
  prune(now);ArrayDeque<Entry> queue=players.get(player);if(queue==null)return List.of();
  List<Entry> result=new ArrayList<>();var iterator=queue.descendingIterator();
  while(iterator.hasNext()&&result.size()<Math.max(0,Math.min(MAX_EVENTS,limit))){Entry e=iterator.next();if(e.dimension().equals(dimension))result.add(e);}
  return List.copyOf(result);
 }
 private void prune(long now){players.values().forEach(q->q.removeIf(e->now-e.time()>TTL_MILLIS||now<e.time()));players.values().removeIf(ArrayDeque::isEmpty);}
 synchronized void clear(UUID player){players.remove(player);}
 synchronized void clear(){players.clear();}
 static String safeText(String value,int maximum){
  if(value==null)return "";StringBuilder result=new StringBuilder();
  value.codePoints().filter(c->!Character.isISOControl(c)&&Character.getType(c)!=Character.FORMAT).limit(maximum).forEach(result::appendCodePoint);
  return result.toString();
 }
}
