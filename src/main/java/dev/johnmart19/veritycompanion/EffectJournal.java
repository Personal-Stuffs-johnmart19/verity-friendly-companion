package dev.johnmart19.veritycompanion;
import java.util.*;

/** Bounded successful mutations only. Payloads are cloned by the Minecraft adapter. */
final class EffectJournal<T> {
 record Entry<T>(String id,T before,T after,Object identity,long tick) {}
 private final Map<UUID,Deque<Entry<T>>> entries=new LinkedHashMap<>();
 void record(UUID player,Entry<T> entry) {
  if(!entries.containsKey(player)&&entries.size()>=128)entries.remove(entries.keySet().iterator().next());
  var rows=entries.computeIfAbsent(player,k->new ArrayDeque<>());rows.addLast(entry);
  while(rows.size()>16)rows.removeFirst();
 }
 Entry<T> find(UUID player,String id) {
  var rows=entries.get(player);if(rows==null)return null;
  var it=rows.descendingIterator();while(it.hasNext()){var e=it.next();if(id.isEmpty()||id.equals("last")||id.equals(e.id()))return e;}return null;
 }
 void consume(UUID player,Entry<T> entry){var rows=entries.get(player);if(rows!=null){rows.remove(entry);if(rows.isEmpty())entries.remove(player);}}
 void clear(UUID player){entries.remove(player);}
 void clear(){entries.clear();}
 static boolean owns(Object recorded,Object current,Object expected,Object actual){return recorded==current&&Objects.equals(expected,actual);}
 /** addEffect's boolean excludes some successful hidden-stack updates; compare cloned full states. */
 static boolean shouldRecord(Object before,Object after){return after!=null&&!Objects.equals(before,after);}
}
