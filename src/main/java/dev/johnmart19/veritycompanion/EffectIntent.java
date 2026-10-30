package dev.johnmart19.veritycompanion;

import java.util.*;

/** Transient human-only effect clarification. Assistant replies and tool arguments never enter this store. */
final class EffectIntent {
 static final long WINDOW_MILLIS=180_000L;
 static final int MAX_PLAYERS=128;
 private record Pending(long opened,String current,String root,String details,String effective) {}
 private static final Map<UUID,Pending> REQUESTS=new LinkedHashMap<>();
 private static boolean live(Pending p,long now){return p!=null&&now>=p.opened()&&now-p.opened()<WINDOW_MILLIS;}
 private static void sweep(long now){REQUESTS.entrySet().removeIf(e->!live(e.getValue(),now));}
 private static String raw(String text){return text==null?"":text;}
 private static boolean affirmation(String text){return HumanIntentLexicon.affirmation(text)||EffectPolicy.text(text).matches("(?:да|ага|угу|давай|конечно|да пожалуйста|да, пожалуйста|yes|yeah|yep|sure|yes please|yes, please|please do)[.! ]*");}
 private static boolean direct(String human){return EffectPolicy.applyIntent(human)
  &&(EffectPolicy.effectWords(human)||!EffectPolicy.namedIds(human).isEmpty());}
 /** Deliberately limited noun list: questions, other chat and assistant suggestions cannot expand consent. */
 private static boolean detail(String human){
  if(!EffectPolicy.safe(human)||GameplayLanguage.idiomaticStrength(human))return false;
  String rest=EffectPolicy.text(human);
  boolean named=!EffectPolicy.namedIds(human).isEmpty()||EffectPolicy.foodWords(human);
  if(!named)return false;
  // Long aliases first so "fire resistance" is not reduced to a stray "fire".
  var aliases=new ArrayList<>(EffectPolicy.NAMES.values());aliases.sort(Comparator.comparingInt(String::length).reversed());
  for(String alias:aliases)rest=rest.replaceAll(EffectPolicy.aliasPattern(alias)," ");
  rest=rest.replaceAll(EffectPolicy.aliasPattern(EffectPolicy.INFINITE_WORDS)," ");
  rest=rest.replaceAll("(?<![\\p{L}\\p{N}_])[a-z0-9_]+:[a-z0-9_/.-]+(?![\\p{L}\\p{N}_])"," ");
  rest=rest.replaceAll("(?iu)(?<![\\p{L}])(?:еда|еды|еду|питание|food|feeding|эффект\\p{L}*|баф\\p{L}*|effects?|buffs?|и|а|еще|мне|пожалуйста|тогда|and|also|me|please|the|of|на|for|уровень|уровня|level|lvl|секунд\\p{L}*|сек|seconds?|secs?|минут\\p{L}*|мин|minutes?|mins?|час\\p{L}*|hours?|[smh]|i{1,3}|iv|vi{0,3}|ix|x)(?![\\p{L}])"," ");
  return rest.matches("[\\s,.;:!?()0-9+\\-]*");
 }
 private static boolean infiniteContinuation(String human){
  if(!EffectPolicy.infiniteRequested(human)||GameplayLanguage.idiomaticStrength(human))return false;
  String rest=EffectPolicy.text(human).replaceAll(EffectPolicy.aliasPattern(EffectPolicy.INFINITE_WORDS)," ");
  rest=rest.replaceAll(EffectPolicy.aliasPattern("сделай(?:те)?|сделать|примени|применить|наложи|продли|поставь|на|их|его|ее|это|эти|этот|эффект\\p{L}*|баф\\p{L}*|make|apply|set|keep|it|them|those|these|effects?|buffs?|please|пожалуйста")," ");
  return rest.matches("[\\s,.;:!?()\\-]*");
 }
 /** Call only once at the incoming human-message boundary, before prompt/model/tool processing. */
 static synchronized boolean begin(UUID player,String human,long now){
  sweep(now);if(player==null)return false;
  String current=raw(human);Pending prior=REQUESTS.get(player),next=null;
  if(live(prior,now)&&!EffectPolicy.namedIds(prior.effective()).isEmpty()&&infiniteContinuation(human)){
   String effective=prior.effective()+"; "+current;
   next=new Pending(now,current,effective,"",effective);
  }else if(direct(human))next=new Pending(now,current,current,"",current);
  else if(live(prior,now)&&detail(human)){
   String effective=prior.root()+"; "+current;
   next=new Pending(now,current,prior.root(),current,effective);
  }else if(live(prior,now)&&affirmation(human))
   next=new Pending(prior.opened(),current,prior.root(),prior.details(),prior.effective());
  // Any unrelated, negated, informational or idiomatic human turn closes this request.
  if(next==null){REQUESTS.remove(player);return false;}
  if(!REQUESTS.containsKey(player)&&REQUESTS.size()>=MAX_PLAYERS)REQUESTS.remove(REQUESTS.keySet().iterator().next());
  REQUESTS.put(player,next);return true;
 }
 /** Does not create permission. A different queued or invented request cannot borrow the pending scope. */
 static synchronized String effective(UUID player,String current,long now){
  sweep(now);Pending p=REQUESTS.get(player);
  return live(p,now)&&p.current().equals(raw(current))?p.effective():raw(current);
 }
 /** Canonical choices for deterministic execution; every choice must still pass EffectPolicy.validate. */
 static synchronized List<String> requestedEffects(UUID player,String current,long now){
  sweep(now);Pending p=REQUESTS.get(player);
  if(!live(p,now)||!p.current().equals(raw(current))||!EffectPolicy.applyIntent(p.effective()))return List.of();
  return List.copyOf(EffectPolicy.namedIds(p.effective()));
 }
 static synchronized String context(UUID player,String current,long now){
  return context(player,current,now,"en");
 }
 static synchronized String context(UUID player,String current,long now,String language){
  sweep(now);Pending p=REQUESTS.get(player);
  if(!live(p,now)||!p.current().equals(raw(current)))return "";
  Set<String> ids=EffectPolicy.namedIds(p.effective());

  return "Server human-only effect context: "+(ids.isEmpty()?"The human asked for effects but has not named any yet. Ask which effects; an affirmation cannot select assistant-suggested effects.":
   "The human explicitly requested these effects: "+String.join(", ",ids)+". This message continues that human request. Apply each requested effect through apply_effect; do not ask for repeated consent or require an explicit duration or level. "+
   (EffectPolicy.infiniteRequested(p.effective())?"The human explicitly requested permanent effects: use infinite=true (native duration -1), never a large finite duration. ":"Defaults are 60 seconds, level I. ")+"Only human-specified duration/level can increase the limits.");
 }
 static synchronized void clear(UUID player){REQUESTS.remove(player);}
 static synchronized void clear(){REQUESTS.clear();}
 static synchronized void clearAll(){clear();}
}
