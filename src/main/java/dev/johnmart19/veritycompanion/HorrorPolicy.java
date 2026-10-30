package dev.johnmart19.veritycompanion;

import java.util.Locale;

/** Pure per-player gates. Callers own master/event switches and separate event clocks. */
public final class HorrorPolicy {
 public enum Mode { OFF, MOOD, RANDOM }
 public enum Event { DARKNESS, FOG, SOUNDS, STALKER, BOSS }

 private HorrorPolicy() {}

 /** Reject unknown commands/config values instead of silently enabling a mode. */
 public static Mode parseMode(String value) {
  if(value==null)throw new IllegalArgumentException("Horror mode is required");
  try{return Mode.valueOf(value.strip().toUpperCase(Locale.ROOT));}
  catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Horror mode must be off, mood or random");}
 }

 /** Invalid persisted state fails closed; threshold zero means fully sad only. */
 public static boolean eligible(Mode mode,int mood,int threshold) {
  if(mode==null||mood<0||mood>100||threshold<0||threshold>100)return false;
  return mode==Mode.RANDOM||(mode==Mode.MOOD&&mood<=threshold);
 }

 /**
  * Millisecond cooldown applies to every mode. Chance applies only to RANDOM;
  * MOOD triggers deterministically when eligible. Supply a finite roll in [0,1).
  * All malformed numeric inputs fail closed, including negative elapsed time.
  */
 public static boolean shouldTrigger(Mode mode,int mood,int threshold,int chancePercent,
                                     double roll,long elapsedMs,long cooldownMs) {
  if(!eligible(mode,mood,threshold)||chancePercent<0||chancePercent>100
      ||!Double.isFinite(roll)||roll<0||roll>=1||elapsedMs<0||cooldownMs<0
      ||elapsedMs<cooldownMs)return false;
  return mode==Mode.MOOD||roll<chancePercent/100.0;
 }
}
