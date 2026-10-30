package dev.johnmart19.veritycompanion;

import java.util.*;
import java.util.regex.*;

/** Decisions depend on the original human message, never model explanations. */
final class EffectPolicy {
 private EffectPolicy() {}
 static String text(String s) { return HumanIntentLexicon.policyText(s); }
 static String nameRu(String id,String fallback){return switch(id){
  case "minecraft:night_vision" -> "Ночное зрение";case "minecraft:regeneration" -> "Регенерация";
  case "minecraft:saturation" -> "Насыщение";case "minecraft:hunger" -> "Голод";
  case "minecraft:absorption" -> "Поглощение";case "minecraft:speed" -> "Скорость";
  case "minecraft:slowness" -> "Замедление";case "minecraft:strength" -> "Сила";
  case "minecraft:weakness" -> "Слабость";case "minecraft:poison" -> "Отравление";
  case "minecraft:resistance" -> "Сопротивление";case "minecraft:fire_resistance" -> "Огнестойкость";
  case "minecraft:invisibility" -> "Невидимость";case "minecraft:haste" -> "Спешка";
  case "minecraft:jump_boost" -> "Прыгучесть";case "minecraft:water_breathing" -> "Подводное дыхание";
  default -> fallback;
 };}
 static boolean safe(String s) {
  s=text(s);
  return !s.isEmpty() && s.length()<=1024 && !s.matches("(?s).*[\"«»„“”`\\n\\r].*") && !s.startsWith("'") && !s.endsWith("'")
   && !s.matches("(?s).*(?:^|[^\\p{L}])(?:не|нет|без|not|no|never|don't|dont|cancel|отмена|объясни|расскажи|почему|зачем|переведи|сказал|сказала|explain|translate|why|how|said|told|quote)(?:$|[^\\p{L}]).*");
 }
 static boolean applyIntent(String s) { return safe(s)&&!GameplayLanguage.idiomaticStrength(s)
  && !text(s).matches("(?s).*(?:узнать|информац|совет|например|сказал|what|tell me|about|example).*" )
  && text(s).matches("(?s).*(?:дай|выдай|наложи|добавь|примени|хочу|give|grant|apply|add|i want).*" ); }
 static boolean removeIntent(String s) { return safe(s) && text(s).matches("(?s).*(?:убери|сними|удали|очисти|отмени эффект|remove|clear|undo).*" ); }
 static boolean all(String s) { return removeIntent(s) && text(s).matches("(?s).*(?:все(?: мои| активные)? эффекты|всех эффектов|all(?: my| active)? effects).*" ); }
 static void validateRemoval(String human,boolean clearAll) {
  if(!removeIntent(human))throw new IllegalArgumentException("No explicit human request to remove an effect");
  if(clearAll&&!all(human))throw new IllegalArgumentException("Clearing every effect requires the human to explicitly request all effects");
 }
 /** Explicitly named current effects can be removed even when applied before this journal existed. */
 static boolean authorizeSelectiveId(String human,String id,String display) {
  return id!=null&&!id.isEmpty()&&!id.equals("last")&&removeIntent(human)&&named(human,id,display);
 }
 static void validateTrackedRemoval(String human,String id,String display,boolean latest,Map<String,String> registeredNames){
  validateRemoval(human,false);
  if(named(human,id,display))return;
  for(var effect:registeredNames.entrySet())if(!effect.getKey().equals(id)&&named(human,effect.getKey(),effect.getValue()))
   throw new IllegalArgumentException("The named effect does not match the selected Verity effect; select the effect the human named");
  if(!latest)throw new IllegalArgumentException("Removing an older Verity effect requires the human to name that effect; otherwise undo the last one");
 }
 static boolean effectWords(String s){return text(s).matches("(?s).*(?:эффект\\p{L}*|баф\\p{L}*|\\beffects?\\b|\\bbuffs?\\b).*" );}
 static boolean foodWords(String s){return text(s).matches("(?s).*(?<![\\p{L}])(?:еда|еды|еду|питан\\p{L}*|food|feeding)(?![\\p{L}]).*" );}
 static boolean satiety(String s) { return text(s).matches("(?s).*(?:сыт|насыщ|утоли.*голод|satiat|saturation|well fed|full stomach|stop.*hungry).*" )||(effectWords(s)&&foodWords(s)); }
 static final Map<String,String> NAMES=Map.ofEntries(
  Map.entry("saturation","насыщ\\p{L}*|сыт\\p{L}*|satiat\\p{L}*|well fed|full stomach"), Map.entry("hunger","голод\\p{L}*|hunger"),
  Map.entry("absorption","поглощ\\p{L}*|absorption"), Map.entry("speed","скорост\\p{L}*|speed"),
  Map.entry("slowness","замедл\\p{L}*|slowness"), Map.entry("strength","сил[аыуе]|силой|strength"),
  Map.entry("weakness","слабост\\p{L}*|weakness"), Map.entry("poison","отрав\\p{L}*|яд|poison"),
  Map.entry("regeneration","реген(?:ерац\\p{L}*)?|regeneration|regen"), Map.entry("resistance","сопротивл\\p{L}*|resistance"),
  Map.entry("fire_resistance","огнестой\\p{L}*|fire resistance"), Map.entry("night_vision","ночн\\p{L}*\\s+(?:зрен\\p{L}*|видени\\p{L}*)|night[ _]+vision|nightvision"),
  Map.entry("invisibility","невидим\\p{L}*|invisibility"), Map.entry("haste","спешк\\p{L}*|haste"),
  Map.entry("jump_boost","прыгуч\\p{L}*|jump boost"), Map.entry("water_breathing","подводн\\p{L}*\\s+дыхан\\p{L}*|water breathing"));
 static String aliasPattern(String aliases){return "(?<![\\p{L}\\p{N}_])(?:"+aliases+")(?![\\p{L}\\p{N}_])";}
 static Set<String> namedIds(String human){
  Set<String> ids=new TreeSet<>();for(String path:NAMES.keySet())if(named(human,"minecraft:"+path,""))ids.add("minecraft:"+path);
  if(satiety(human)){ids.add("minecraft:saturation");ids.remove("minecraft:hunger");ids.remove("minecraft:absorption");}
  if(effectWords(human)){
   var custom=Pattern.compile("(?<![\\p{L}\\p{N}_])[a-z0-9_]+:[a-z0-9_/.-]+(?![\\p{L}\\p{N}_])").matcher(text(human));while(custom.find())ids.add(custom.group());
  }
  return ids;
 }
 static boolean named(String human,String id,String display) {
  String s=text(human), path=id.substring(id.indexOf(':')+1);
  // The word Resistance within Fire Resistance names one effect, not both.
  if(id.equals("minecraft:resistance"))s=s.replaceAll(aliasPattern("fire[ _]+resistance")," ");
  if(s.contains(id)||s.matches("(?s).*(?<![a-z_])"+Pattern.quote(path)+"(?![a-z_]).*"))return true;
  String name=text(display);
  if(name.length()>2 && s.contains(name))return true;
  String aliases=NAMES.get(path);
  return id.startsWith("minecraft:") && aliases!=null && Pattern.compile(aliasPattern(aliases)).matcher(s).find();
 }
 static void validate(String human,String id,String display,boolean beneficial) {
  if(!applyIntent(human))throw new IllegalArgumentException("No explicit human request to apply an effect");
  if(satiety(human)&&(id.equals("minecraft:hunger")||id.equals("minecraft:absorption")))
   throw new IllegalArgumentException("Satiety requires minecraft:saturation; hunger and absorption do not feed the player");
  if(!(satiety(human)&&id.equals("minecraft:saturation"))&&!named(human,id,display))
   throw new IllegalArgumentException("Effect meaning is unclear; ask the human which effect");
  if(!beneficial && !named(human,id,display))throw new IllegalArgumentException("A non-beneficial effect needs an explicit human request naming it");
 }
 static final String INFINITE_WORDS="бесконечн\\p{L}*|навсегда|на\\s+всегда|постоянн\\p{L}*|infinite|infinity|permanent(?:ly)?|forever";
 static boolean infiniteRequested(String human){
  return safe(human)&&Pattern.compile(aliasPattern(INFINITE_WORDS)).matcher(text(human)).find();
 }
 /** Only the requesting player's actual current set; never other players, future effects or new levels. */
 static boolean makeCurrentInfinite(String human){
  if(!infiniteRequested(human)||!effectWords(human))return false;
  String s=text(human);
  if(!Pattern.compile(aliasPattern("сделай(?:те)?|примени|продли|make|set|keep|apply")).matcher(s).find())return false;
  if(!s.matches("(?s).*(?:на мне|на меня|у меня|мои|моих|текущ\\p{L}*|активн\\p{L}*|\\bmy\\b|on me|i have|current|active).*"))return false;
  String rest=s.replaceAll(aliasPattern(INFINITE_WORDS)," ");
  rest=rest.replaceAll(aliasPattern("сделай(?:те)?|примени|продли|эффект\\p{L}*|баф\\p{L}*|те|эти|все|что|которые|сейчас|уже|активн\\p{L}*|текущ\\p{L}*|мои|моих|на|мне|меня|у|пожалуйста|make|set|keep|apply|effects?|buffs?|these|those|all|my|the|that|which|are|currently|active|current|on|me|i|have|please")," ");
  return rest.matches("[\\s,.;:!?()\\-]*");
 }
 /** Native Minecraft infinite duration is -1; large finite tick counts are never substitutes. */
 static int durationTicks(String human,int proposedSeconds,boolean proposedInfinite){
  if(infiniteRequested(human))return -1;
  if(proposedInfinite||proposedSeconds==-1)throw new IllegalArgumentException("Infinite duration requires an explicit human request for forever/permanent effects");
  return Math.multiplyExact(duration(human,proposedSeconds),20);
 }
 static int duration(String human,int proposed) {
  Matcher m=Pattern.compile("(?iu)(\\d{1,7})\\s*(секунд\\p{L}*|сек\\b|seconds?\\b|secs?\\b|s\\b|минут\\p{L}*|мин\\b|minutes?\\b|mins?\\b|m\\b|час\\p{L}*|hours?\\b|h\\b)").matcher(text(human));
  long limit=300; boolean explicit=false;
  if(m.find()){String unit=m.group(2);limit=Long.parseLong(m.group(1))*(unit.startsWith("м")||unit.startsWith("m")?60L:unit.startsWith("ч")||unit.startsWith("h")?3600L:1L);explicit=true;}
  if(proposed<=0)throw new IllegalArgumentException("Duration must be positive seconds");
  return (int)Math.min(Math.min(proposed,explicit?limit:300),Integer.MAX_VALUE/20);
 }
 static boolean maximumRequested(String human){return safe(human)&&text(human).matches("(?s).*(?:максимальн\\p{L}*|на максимум|maximum|max level|highest level).* ".strip());}
 static int amplifier(String human,int proposed) {
  if(maximumRequested(human))return 255;
  Matcher m=Pattern.compile("(?iu)(?:уров(?:ень|ня|нем)|level|lvl)\\s*(\\d{1,3})|\\b(\\d{1,3})\\s*(?:уров(?:ень|ня)|level)").matcher(text(human));
  int max=0;
  if(m.find())max=Math.max(0,Integer.parseInt(m.group(1)!=null?m.group(1):m.group(2))-1);
  else {Matcher roman=Pattern.compile("\\b(II|III|IV|V|VI|VII|VIII|IX|X)\\b").matcher(human==null?"":human);if(roman.find())max=List.of("I","II","III","IV","V","VI","VII","VIII","IX","X").indexOf(roman.group(1));}
  return Math.max(0,Math.min(proposed,Math.min(255,max)));
 }
}
