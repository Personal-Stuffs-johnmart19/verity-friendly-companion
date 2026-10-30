package dev.johnmart19.veritycompanion;
import java.util.UUID;
/** Portable ownership and loss-free capacity rules, independent of game bootstrap. */
final class CompanionItemPolicy {
 private CompanionItemPolicy(){}
 static boolean mayPickUp(UUID owner,UUID player){return owner!=null&&owner.equals(player);}
 static boolean mayPlace(UUID owner,UUID player){return owner==null||owner.equals(player);}
 static boolean fits(int count,int[] capacities){long free=0;for(int space:capacities)free+=Math.max(0,space);return count>0&&free>=count;}
 // Original 3.4.1 VALID_VARIANTS, verified against bytecode and bundled textures.
 private static final java.util.Set<String> VARIANTS=java.util.Set.of("crazy_talking_0","default","default_sleep_0","default_talking_1","hurt_0","neutral_0","noface","serious_1","serious_2","serious_3","serious_angry","smile2","smile4","smiling_evil_0","smiling_evil_1","talking_0");
 static String variant(String value){return value!=null&&VARIANTS.contains(value)?value:"default";}
 static String alias(String value){return value==null?"":value.replaceAll("[\\p{Cntrl}]"," ").substring(0,Math.min(64,value.length()));}
}
