package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.network.chat.Component;

/** Server-owned, world-specific, per-player relationships. The model cannot change scores. */
public final class Relations {
 static final Map<UUID,Entry> DATA=new ConcurrentHashMap<>();
 static final Map<UUID,ServerBossEvent> BARS=new HashMap<>();
 public static class Entry {public MoodPolicy.State state=new MoodPolicy.State();public String alias="Verity",language="ru",languageMode="auto";public int display;public volatile boolean tts=true;public volatile String ttsVoice="Achird";public String gender="male",role="orb";}
 static Path path(ServerPlayer p){return p.getServer().getWorldPath(LevelResource.ROOT).resolve("verity-companion/relationships/"+p.getUUID()+".json");}
 static void validate(Entry e){if(e==null||e.state==null||e.alias==null||e.alias.isBlank())throw new IllegalArgumentException("Invalid relationship record");try{e.gender=PersonaPolicy.normalize(e.gender);}catch(Exception invalid){e.gender="male";}try{e.role=PersonaPolicy.role(e.role);}catch(Exception invalid){e.role="orb";}e.state.mood=Math.clamp(e.state.mood,0,100);if(!"en".equals(e.language))e.language="ru";try{e.languageMode=PlayerLanguage.mode(e.languageMode);}catch(IllegalArgumentException invalid){e.languageMode="auto";}if(e.display<0||e.display>2)e.display=0;try{e.ttsVoice=TtsCommands.normalize(e.ttsVoice);}catch(Exception invalid){e.ttsVoice="Achird";}}
 static synchronized Entry get(ServerPlayer p){return DATA.computeIfAbsent(p.getUUID(),id->{try{return SafeJsonStore.load(path(p),CompanionBrain.JSON,Entry.class,()->{Entry e=new Entry();e.language=p.clientInformation().language().startsWith("ru")?"ru":"en";return e;},Relations::validate);}catch(java.io.IOException error){throw new IllegalStateException("Cannot read relationship storage",error);}});}
 static synchronized void save(ServerPlayer p){try{SafeJsonStore.save(path(p),CompanionBrain.JSON,Entry.class,get(p),Relations::validate);}catch(java.io.IOException error){throw new IllegalStateException("Cannot save relationship",error);}}
 static String tr(ServerPlayer p,String ru,String en){return get(p).language.equals("en")?en:ru;}
 static synchronized String name(ServerPlayer p){return get(p).alias;}
 static synchronized String receive(ServerPlayer p,String text){
  CompanionBrain.init();Entry e=get(p);String previous=e.language;e.language=PlayerLanguage.choose(e.languageMode,e.language,text);if(!previous.equals(e.language))save(p);
  if(!CompanionBrain.settings.relationships)return "";
  MoodPolicy.Result r=MoodPolicy.apply(e.state,text,e.alias,System.currentTimeMillis());save(p);
  p.getServer().execute(()->{refresh(p);HorrorController.onMood(p);});
  if(r.insult()&&e.state.strikes<=2)return tr(p,"Мне неприятны прямые оскорбления. Давай общаться нормально.","Direct insults are unpleasant. Let's speak respectfully.");
  return "";
 }
 static synchronized boolean helps(ServerPlayer p){return !CompanionBrain.settings.relationships||MoodPolicy.helps(get(p).state);}
 static synchronized String context(ServerPlayer p){
  Entry e=get(p);JsonObject facts=new JsonObject();facts.addProperty("name",e.alias);facts.addProperty("language",e.language);facts.addProperty("language_mode",e.languageMode);facts.addProperty("gender",e.gender);facts.addProperty("role",e.role);facts.addProperty("mood",CompanionBrain.settings.relationships?e.state.mood:100);facts.addProperty("horror",HorrorSettings.get().mode);facts.addProperty("boss_mode",HorrorSettings.get().bossMode);facts.addProperty("boss_mood_threshold",HorrorSettings.get().moodThreshold);
  if(e.language.equals("ru"))return "Состояние твоего персонажа для этого игрока: "+facts+". Отвечай по-русски, даже если в запросе английские названия модов. "+(e.gender.equals("female")?"Ты девушка: о себе поняла, готова. ":e.gender.equals("male")?"Ты парень: о себе понял, готов. ":"Избегай родовых окончаний о себе: готово, сделано. ")+(e.role.equals("protector")?"Образ спокойного защитника деревни. ":e.role.equals("anime")?"Образ живого выразительного аниме-компаньона, без навязанных присказок. ":"Образ любопытного дружелюбного шарика-смайлика. ")+"Отношение индивидуально, не наказывай других игроков. "+(helps(p)?"Помогай обычно; нейтральный разговор и ошибки в имени безвредны.":"После повторных прямых оскорблений помощь в игре приостановлена. Можно отвечать сдержанно и сердито, без угроз; принимай извинения, не используй игровые инструменты помощи до примирения.");
  return "Your per-player character state: "+facts+". Reply in English even when the request names Russian items. "+PersonaPolicy.context(e.gender)+" "+PersonaPolicy.roleContext(e.role)+" Relationships are individual; never punish others. "+(helps(p)?"Help normally; neutral chat and wrong names are harmless.":"Gameplay assistance is paused after repeated direct abuse. Be curt without threats; accept apologies. Do not use gameplay assistance tools before reconciliation.");
 }
 static synchronized void defeated(ServerPlayer p){MoodPolicy.defeated(get(p).state);save(p);refresh(p);}
 static synchronized void refresh(ServerPlayer p){Entry e=get(p);boolean show=CompanionBrain.settings.relationships&&e.display!=2&&(e.display==1||e.state.mood<100);ServerBossEvent bar=BARS.get(p.getUUID());if(!show){if(bar!=null){bar.removeAllPlayers();BARS.remove(p.getUUID());}return;}if(bar==null){bar=new ServerBossEvent(Component.empty(),BossEvent.BossBarColor.GREEN,BossEvent.BossBarOverlay.PROGRESS);BARS.put(p.getUUID(),bar);bar.addPlayer(p);}bar.setName(Component.literal(e.alias+tr(p," — настроение: "," — mood: ")+e.state.mood+"/100"));bar.setProgress(e.state.mood/100f);bar.setColor(e.state.mood>=60?BossEvent.BossBarColor.GREEN:e.state.mood>=40?BossEvent.BossBarColor.YELLOW:BossEvent.BossBarColor.RED);}
 static synchronized void logout(UUID id){ServerBossEvent bar=BARS.remove(id);if(bar!=null)bar.removeAllPlayers();DATA.remove(id);}
 static synchronized void clear(){BARS.values().forEach(ServerBossEvent::removeAllPlayers);BARS.clear();DATA.clear();}
}
