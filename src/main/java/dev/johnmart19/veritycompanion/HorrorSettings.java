package dev.johnmart19.veritycompanion;

import java.nio.file.*;
import java.util.function.Consumer;

/** Independent server-owned event configuration. Installing an update never enables horror. */
final class HorrorSettings {
 static final Path PATH=Path.of("config/verity-companion-horror.json");
 static final class Options {
  String mode="off",bossMode="mood";
  int moodThreshold=0,intervalSeconds=30,cooldownSeconds=180,bossCooldownSeconds=600,durationSeconds=15,chancePercent=20;
  boolean darkness=true,fog=true,sounds=true,stalker=true;
 }
 private static volatile Options current;
 static synchronized Options get(){
  if(current==null)try{current=SafeJsonStore.load(PATH,CompanionBrain.JSON,Options.class,Options::new,HorrorSettings::validate);if(!Files.exists(PATH))SafeJsonStore.save(PATH,CompanionBrain.JSON,Options.class,current,HorrorSettings::validate);}catch(java.io.IOException e){throw new IllegalStateException("Cannot read horror configuration",e);}
  return current;
 }
 static void validate(Options value){
  if(value==null)throw new IllegalArgumentException("Missing horror settings");
  value.mode=HorrorPolicy.parseMode(value.mode).name().toLowerCase(java.util.Locale.ROOT);value.bossMode=HorrorPolicy.parseMode(value.bossMode).name().toLowerCase(java.util.Locale.ROOT);
  value.moodThreshold=Math.clamp(value.moodThreshold,0,100);value.chancePercent=Math.clamp(value.chancePercent,0,100);
  value.intervalSeconds=Math.clamp(value.intervalSeconds,5,600);value.cooldownSeconds=Math.clamp(value.cooldownSeconds,10,3600);
  value.bossCooldownSeconds=Math.clamp(value.bossCooldownSeconds,10,3600);value.durationSeconds=Math.clamp(value.durationSeconds,3,60);
 }
 static synchronized void update(Consumer<Options> edit){
  Options next=CompanionBrain.JSON.fromJson(CompanionBrain.JSON.toJson(get()),Options.class);edit.accept(next);validate(next);
  try{SafeJsonStore.save(PATH,CompanionBrain.JSON,Options.class,next,HorrorSettings::validate);}catch(java.io.IOException e){throw new IllegalStateException("Cannot save horror configuration",e);}
  current=next;
 }
 static boolean enabled(HorrorPolicy.Event event){Options o=get();return switch(event){case DARKNESS->o.darkness;case FOG->o.fog;case SOUNDS->o.sounds;case STALKER->o.stalker;case BOSS->!o.bossMode.equals("off");};}
 static void resetCache(){current=null;}
}
