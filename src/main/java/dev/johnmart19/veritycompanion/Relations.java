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
 public static class Entry {public MoodPolicy.State state=new MoodPolicy.State();public String alias="Verity",language="en",languageMode="auto",llmLanguage="inherit",ttsLanguage="inherit",sttLanguage="inherit";public int display;public volatile boolean tts=true;public volatile String ttsVoice="Achird";public String gender="male",role="orb";}
 static Path path(ServerPlayer p){return p.getServer().getWorldPath(LevelResource.ROOT).resolve("verity-companion/relationships/"+p.getUUID()+".json");}
 static void validate(Entry e){if(e==null||e.state==null||e.alias==null||e.alias.isBlank())throw new IllegalArgumentException("Invalid relationship record");try{e.gender=PersonaPolicy.normalize(e.gender);}catch(Exception invalid){e.gender="male";}try{e.role=PersonaPolicy.role(e.role);}catch(Exception invalid){e.role="orb";}e.state.mood=Math.clamp(e.state.mood,0,100);try{e.language=Languages.normalize(e.language);}catch(IllegalArgumentException invalid){e.language="en";}try{e.languageMode=PlayerLanguage.mode(e.languageMode);}catch(IllegalArgumentException invalid){e.languageMode="auto";}e.llmLanguage=preference(e.llmLanguage);e.ttsLanguage=preference(e.ttsLanguage);e.sttLanguage=preference(e.sttLanguage);if(e.display<0||e.display>2)e.display=0;try{e.ttsVoice=TtsCommands.normalize(e.ttsVoice);}catch(Exception invalid){e.ttsVoice="Achird";}}
 private static String preference(String value){try{return Languages.mode(value,true);}catch(IllegalArgumentException invalid){return "inherit";}}
 static synchronized Entry get(ServerPlayer p){return DATA.computeIfAbsent(p.getUUID(),id->{try{return SafeJsonStore.load(path(p),CompanionBrain.JSON,Entry.class,()->{Entry e=new Entry();e.language=Languages.fromClientLocale(p.clientInformation().language());return e;},Relations::validate);}catch(java.io.IOException error){throw new IllegalStateException("Cannot read relationship storage",error);}});}
 static synchronized void save(ServerPlayer p){try{SafeJsonStore.save(path(p),CompanionBrain.JSON,Entry.class,get(p),Relations::validate);}catch(java.io.IOException error){throw new IllegalStateException("Cannot save relationship",error);}}
 static synchronized String replyLanguage(ServerPlayer p){Entry e=get(p);return e.llmLanguage.equals("inherit")?e.language:PlayerLanguage.choose(e.llmLanguage,e.language,"",p.clientInformation().language());}
 static synchronized String ttsLanguage(ServerPlayer p){Entry e=get(p);return e.ttsLanguage.equals("inherit")?replyLanguage(p):e.ttsLanguage;}
 static synchronized String sttLanguage(ServerPlayer p){String mode=get(p).sttLanguage;return mode.equals("inherit")?null:mode;}
 static synchronized String name(ServerPlayer p){return get(p).alias;}
 static synchronized String receive(ServerPlayer p,String text){
  CompanionBrain.init();Entry e=get(p);String previous=e.language;e.language=PlayerLanguage.choose(e.languageMode,e.language,text,p.clientInformation().language());if(!previous.equals(e.language))try{save(p);}catch(IllegalStateException failed){e.language=previous;throw failed;}
  if(!CompanionBrain.settings.relationships)return "";
  MoodPolicy.Result r=MoodPolicy.apply(e.state,text,e.alias,System.currentTimeMillis());save(p);
  p.getServer().execute(()->{refresh(p);HorrorController.onMood(p);});
  if(r.insult()&&e.state.strikes<=2)return ModTranslations.text(e.language,"verity_companion.relationship.respect");
  return "";
 }
 static synchronized boolean helps(ServerPlayer p){return !CompanionBrain.settings.relationships||MoodPolicy.helps(get(p).state);}
 static synchronized String context(ServerPlayer p){
  Entry e=get(p);String replyLanguage=replyLanguage(p);JsonObject facts=new JsonObject();facts.addProperty("name",e.alias);facts.addProperty("language",replyLanguage);facts.addProperty("interface_language",e.language);facts.addProperty("language_mode",e.languageMode);facts.addProperty("gender",e.gender);facts.addProperty("role",e.role);facts.addProperty("mood",CompanionBrain.settings.relationships?e.state.mood:100);facts.addProperty("horror",HorrorSettings.get().mode);facts.addProperty("boss_mode",HorrorSettings.get().bossMode);facts.addProperty("boss_mood_threshold",HorrorSettings.get().moodThreshold);
  return "Your per-player character state: "+facts+". Reply in "+Languages.name(replyLanguage)+"; preserve registered item/tool identifiers verbatim. "+PersonaPolicy.context(e.gender)+" "+PersonaPolicy.roleContext(e.role)+" Relationships are individual; never punish others. "+(helps(p)?"Help normally; neutral chat and wrong names are harmless.":"Gameplay assistance is paused after repeated direct abuse. Be curt without threats; accept apologies. Do not use gameplay assistance tools before reconciliation.");
 }
 static synchronized void defeated(ServerPlayer p){MoodPolicy.defeated(get(p).state);save(p);refresh(p);}
 static synchronized void refresh(ServerPlayer p){Entry e=get(p);boolean show=CompanionBrain.settings.relationships&&e.display!=2&&(e.display==1||e.state.mood<100);ServerBossEvent bar=BARS.get(p.getUUID());if(!show){if(bar!=null){bar.removeAllPlayers();BARS.remove(p.getUUID());}return;}if(bar==null){bar=new ServerBossEvent(Component.empty(),BossEvent.BossBarColor.GREEN,BossEvent.BossBarOverlay.PROGRESS);BARS.put(p.getUUID(),bar);bar.addPlayer(p);}bar.setName(Component.literal(ModTranslations.text(e.language,"verity_companion.relationship.mood",e.alias,e.state.mood)));bar.setProgress(e.state.mood/100f);bar.setColor(e.state.mood>=60?BossEvent.BossBarColor.GREEN:e.state.mood>=40?BossEvent.BossBarColor.YELLOW:BossEvent.BossBarColor.RED);}
 static synchronized void logout(UUID id){ServerBossEvent bar=BARS.remove(id);if(bar!=null)bar.removeAllPlayers();DATA.remove(id);}
 static synchronized void clear(){BARS.values().forEach(ServerBossEvent::removeAllPlayers);BARS.clear();DATA.clear();}
}
