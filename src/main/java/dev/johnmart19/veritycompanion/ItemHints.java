package dev.johnmart19.veritycompanion;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Conservative supply-name hints; callers must still resolve IDs in the actual item registry. */
public final class ItemHints {
 private static final Map<String,String> ALIASES=new HashMap<>();
 static {
  alias("golden_apple","golden apple","golden apples","золотое яблоко","золотые яблоки","золотых яблок","золотого яблока");
  alias("enchanted_golden_apple","enchanted golden apple","enchanted golden apples","зачарованное золотое яблоко","зачарованные золотые яблоки","зачарованных золотых яблок");
  alias("apple","apple","apples","яблоко","яблоки","яблок");
  alias("bread","bread","хлеб","хлеба");
  alias("cooked_beef","cooked beef","steak","steaks","стейк","стейки","стейков","жареная говядина","жареной говядины");
  alias("cooked_porkchop","cooked porkchop","жареная свинина","жареной свинины");
  alias("cooked_chicken","cooked chicken","жареная курица","жареной курицы");
  alias("arrow","arrow","arrows","стрела","стрелы","стрел");
  alias("torch","torch","torches","факел","факелы","факелов");
  alias("oak_log","oak log","oak logs","дубовое бревно","дубовые бревна","дубовых бревен");
  alias("birch_log","birch log","birch logs","березовое бревно","березовые бревна","березовых бревен");
  alias("spruce_log","spruce log","spruce logs","еловое бревно","еловые бревна","еловых бревен");
  alias("oak_planks","oak planks","дубовые доски","дубовых досок");
  alias("cobblestone","cobblestone","булыжник","булыжника");
  alias("coal","coal","уголь","угля");
  alias("iron_ingot","iron ingot","iron ingots","железный слиток","железные слитки","железных слитков");
  alias("gold_ingot","gold ingot","gold ingots","золотой слиток","золотые слитки","золотых слитков");
 }
 private ItemHints() {}

 /** Match whole phrases only. Unknown/modded names remain available for registry searching. */
 public static String normalizeQuery(String query){
  if(query==null)return "";
  String trimmed=query.strip();
  return ALIASES.getOrDefault(key(trimmed),trimmed);
 }

 /** Suggestions describe choices, and never select an item for an ambiguous request. */
 public static List<String> suggestions(String query){
  return switch(key(query)){
   case "food","еда","еды","пища","пищи" -> List.of("minecraft:bread","minecraft:cooked_beef");
   case "log","logs","бревно","бревна","бревен" -> List.of("minecraft:oak_log","minecraft:birch_log","minecraft:spruce_log");
   default -> List.of();
  };
 }

 /** A stack request uses the resolved item's real stack size and is rejected in full if too large. */
 public static int stacksCount(int requestedCount,Integer stacks,int itemStackSize){
  if(itemStackSize<1)throw new IllegalArgumentException("Item stack size must be positive");
  if(stacks!=null&&(stacks<1||stacks>64))throw new IllegalArgumentException("Choose 1..64 stacks per delivery");
  long count=stacks==null?requestedCount:(long)stacks*itemStackSize;
  if(count<1||count>4096)throw new IllegalArgumentException("Choose 1..4096 items per delivery");
  if((count+itemStackSize-1L)/itemStackSize>64)throw new IllegalArgumentException("Delivery exceeds 64 stacks; split it into smaller requests");
  return (int)count;
 }

 private static void alias(String path,String... names){
  String id="minecraft:"+path;
  ALIASES.put(key(path),id);
  ALIASES.put(key(id),id);
  for(String name:names)ALIASES.put(key(name),id);
 }
 /** Shared normalization for localized catalog labels; never strips item modifiers. */
 public static String key(String query){
  if(query==null)return "";
  return query.toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("§[0-9a-fk-or]","").replaceAll("[.,!?;\\\"'«»“”()\\[\\]{}]"," ").strip().replaceAll("\\s+"," ");
 }
}
