package dev.johnmart19.veritycompanion;

import java.util.*;

/** Last observed confirmed death per UUID, retained across replacement entities and dimensions. */
final class DeathLog {
 static final long TTL_MILLIS=10*60*1000L;
 static final int MAX_PLAYERS=128;
 record Entry(long time,String dimension,String damageType,String attackerUuid,String attackerType,String attackerName,
              String directEntityType,String deathMessage,double x,double y,double z) {}
 private final LinkedHashMap<UUID,Entry> players=new LinkedHashMap<>();
 synchronized void add(UUID player,Entry entry,long now){
  prune(now);if(now<entry.time()||now-entry.time()>TTL_MILLIS)return;
  players.remove(player);if(players.size()>=MAX_PLAYERS)players.remove(players.keySet().iterator().next());players.put(player,entry);
 }
 synchronized Optional<Entry> recent(UUID player,long now){prune(now);return Optional.ofNullable(players.get(player));}
 private void prune(long now){players.values().removeIf(e->now<e.time()||now-e.time()>TTL_MILLIS);}
 synchronized void clear(UUID player){players.remove(player);}
 synchronized void clear(){players.clear();}
 static boolean forQuestion(String text){return text!=null&&text.toLowerCase(Locale.ROOT).matches("(?s).*(что\\s+(?:произошло|случилось)|почему.*(?:умер|умир|погиб|смерт)|умер|погиб|смерт|разбил.*насмерть|what happened|why.*(?:die|dead)|death|died|dead).*");}
}
