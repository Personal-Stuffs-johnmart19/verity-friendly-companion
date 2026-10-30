package dev.johnmart19.veritycompanion;

import java.util.*;
import java.util.regex.*;

/** Destructive consent comes only from the original human request, never model arguments. */
final class CombatPolicy {
 static final int DEFAULT_RADIUS=16,MAX_RADIUS=48,MAX_TARGETS=32;
 static String normalize(String text){return text==null?"":text.toLowerCase(Locale.ROOT).replace('ё','е').replace('’','\'').strip().replaceAll("\\s+"," ");}
 static String command(String original){
  if(original==null||original.indexOf('\n')>=0||original.indexOf('\r')>=0)return "";
  String s=normalize(original);
  if(s.isBlank()||s.length()>512||s.matches("(?s).*[\"«»„“”`?\\n\\r].*")||s.startsWith("'")||s.endsWith("'")||s.matches("(?s).*(?:^| )'[^']+'.*"))return "";
  if(s.matches("(?s).*(?:^|[^\\p{L}])(?:не|нет|без|нельзя|not|no|never|don't|dont|do not|stop|cancel|отмена|отмени|перестань|игрок\\p{L}*|player\\p{L}*|everyone|everybody|жител\\p{L}*|villager\\p{L}*|расскажи|объясни|почему|зачем|как|что|когда|где|можешь|можно|tell|explain|why|how|what|when|where|can|could|would|said|says|сказал|сказала|цитата|quote|example|пример|переведи|translate|if|если)(?:$|[^\\p{L}]).*"))return "";
  s=s.replaceFirst("^(?:(?:пожалуйста|please)[, !]+)","");
  if(!s.matches("(?:убей|убейте|уничтожь|уничтожьте|kill|destroy|slay|eliminate) .+"))return "";
  return s;
 }
 static boolean allowed(String original){String s=command(original);return !s.isEmpty()&&(generic(s)||!species(s).isEmpty());}
 private static boolean generic(String s){return s.matches("(?s).*(?:^|[^\\p{L}])(?:моб\\p{L}*|монстр\\p{L}*|враг\\p{L}*|враждебн\\p{L}*|противник\\p{L}*|mobs?|monsters?|enemies|hostiles?)(?:$|[^\\p{L}]).*");}
 static Set<String> species(String original){
  String s=normalize(original);Set<String> ids=new HashSet<>();
  if(s.matches("(?s).*(?:паук\\p{L}*|spiders?).*")){ids.add("minecraft:spider");ids.add("minecraft:cave_spider");}
  if(s.matches("(?s).*(?:зомби|zombies?).*"))ids.add("minecraft:zombie");
  if(s.matches("(?s).*(?:скелет\\p{L}*|skeletons?).*"))ids.add("minecraft:skeleton");
  if(s.matches("(?s).*(?:крипер\\p{L}*|creepers?).*"))ids.add("minecraft:creeper");
  if(s.matches("(?s).*(?:волк\\p{L}*|wolves|wolf).*"))ids.add("minecraft:wolf");
  return Set.copyOf(ids);
 }
 static boolean matchesType(String original,String type){Set<String> ids=species(original);return ids.isEmpty()||ids.contains(type);}
 static int radius(String original,int requested){
  String s=normalize(original);Matcher match=Pattern.compile("(?:радиус(?:е|ом)?|radius|within|range)\\s*(?:of\\s*)?(\\d{1,4})").matcher(s);int authorized=DEFAULT_RADIUS;
  if(match.find())authorized=Math.max(1,Math.min(MAX_RADIUS,Integer.parseInt(match.group(1))));
  return Math.max(1,Math.min(authorized,requested));
 }
 static boolean eligible(boolean player,boolean owned,boolean villager,boolean hostileType,boolean recentAttacker){return !player&&!owned&&!villager&&(hostileType||recentAttacker);}
}
