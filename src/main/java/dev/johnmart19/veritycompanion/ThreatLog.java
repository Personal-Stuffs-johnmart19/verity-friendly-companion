package dev.johnmart19.veritycompanion;

import java.util.*;

/** Short-lived damage evidence, stored without live entity references or world mutations. */
final class ThreatLog {
 static final int MAX_PLAYERS=128,MAX_EVENTS=12;
 static final long TTL_MILLIS=45_000;
 record Actor(String uuid,String type,String name,double x,double y,double z) {}
 record Entry(long time,String dimension,String direction,String damageType,float damage,
              double playerX,double playerY,double playerZ,Actor attacker,Actor directEntity) {}
 private final LinkedHashMap<UUID,ArrayDeque<Entry>> players=new LinkedHashMap<>();
 synchronized void add(UUID player,Entry event,long now){
  prune(now);
  if(!Float.isFinite(event.damage())||event.damage()<=0||event.time()>now||now-event.time()>TTL_MILLIS)return;
  ArrayDeque<Entry> queue=players.get(player);
  if(queue==null){if(players.size()>=MAX_PLAYERS)players.remove(players.keySet().iterator().next());queue=new ArrayDeque<>();players.put(player,queue);}
  while(queue.size()>=MAX_EVENTS)queue.removeFirst();queue.addLast(event);
 }
 synchronized List<Entry> recent(UUID player,String dimension,long now,int limit){
  prune(now);ArrayDeque<Entry> queue=players.get(player);if(queue==null)return List.of();
  List<Entry> result=new ArrayList<>();var iterator=queue.descendingIterator();
  while(iterator.hasNext()&&result.size()<Math.max(0,Math.min(MAX_EVENTS,limit))){Entry event=iterator.next();if(event.dimension().equals(dimension))result.add(event);}
  return List.copyOf(result);
 }
 synchronized boolean damagedBy(UUID player,String dimension,UUID attacker,long now){
  String id=attacker.toString();return recent(player,dimension,now,MAX_EVENTS).stream().anyMatch(e->e.direction().equals("taken")&&e.attacker()!=null&&e.attacker().uuid().equals(id));
 }
 private void prune(long now){players.values().forEach(q->q.removeIf(e->now<e.time()||now-e.time()>TTL_MILLIS));players.values().removeIf(ArrayDeque::isEmpty);}
 synchronized void clear(UUID player){players.remove(player);}
 synchronized void clear(){players.clear();}
}
