package dev.johnmart19.veritycompanion;

import java.util.List;
import java.util.Locale;

/** Original parametric designs; dimensions charge the entire box, including empty rooms. */
public final class StructureCatalog {
 private StructureCatalog(){}
 public record Preset(String kind,String size,int width,int length,int height,int floors){
  public long volume(){return (long)width*length*height;}
 }
 public static final List<String> KINDS=List.of("house","cottage","workshop","create_workshop","factory","tower","watchtower","outpost","castle","skyscraper","apartment","inn","warehouse","library","barracks","greenhouse","stable","bridge","platform");
 public static final List<String> STYLES=List.of("default","medieval","modern","industrial","create","deepslate","desert","timber");
 public static String canonical(String kind){
  String k=kind==null?"house":kind.strip().toLowerCase(Locale.ROOT);
  return switch(k){case "home"->"house";case "building","residential","apartments"->"apartment";case "fort","fortress"->"castle";case "watch_tower"->"watchtower";case "highrise","high_rise"->"skyscraper";default->k;};
 }
 public static boolean supports(String kind){return KINDS.contains(canonical(kind));}
 /** A model-supplied replacement kind must be grounded in this human turn, including the newer catalogue. */
 public static boolean mentionsKind(String human,String kind){
  String k=canonical(kind),text=human==null?"":human.toLowerCase(Locale.ROOT).replace('ё','е');
  String aliases=switch(k){
   case "house","cottage"->"house|home|cottage|дом\\p{L}*|коттедж\\p{L}*";
   case "workshop","create_workshop"->"workshop|мастерск\\p{L}*|цех\\p{L}*";
   case "castle"->"castle|fort|fortress|замок|замка|замке|крепост\\p{L}*";
   case "tower","watchtower"->"tower|watchtower|watch_tower|башн\\p{L}*";
   case "outpost"->"outpost|аванпост\\p{L}*|форпост\\p{L}*|застав\\p{L}*";
   case "skyscraper"->"skyscraper|highrise|high_rise|небоскреб\\p{L}*";
   case "apartment"->"apartment|apartments|residential|многоэтаж\\p{L}*|многоквартир\\p{L}*";
   case "inn"->"inn|hotel|гостиниц\\p{L}*|трактир\\p{L}*";
   case "warehouse"->"warehouse|склад\\p{L}*";
   case "library"->"library|библиотек\\p{L}*";
   case "barracks"->"barracks|казарм\\p{L}*";
   case "greenhouse"->"greenhouse|теплиц\\p{L}*";
   case "stable"->"stable|конюшн\\p{L}*";
   case "factory"->"factory|фабрик\\p{L}*|завод\\p{L}*";
   case "bridge"->"bridge|мост\\p{L}*";
   case "platform"->"platform|платформ\\p{L}*";
   default->"(?!)";
  };
  return java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}_])(?:"+aliases+")(?![\\p{L}\\p{N}_])").matcher(text).find();
 }
 /** Keep journaled recipe selection identical to the generator's effective defaults. */
 public static String effectiveStyle(String kind,String style){
  String k=canonical(kind),s=style==null?"":style.strip().toLowerCase(Locale.ROOT);
  if(s.equals("vanilla"))s="";
  if(s.equals("create_workshop"))s="create";
  if(!s.isEmpty()&&!STYLES.contains(s))throw new IllegalArgumentException("Unknown structure style "+s+". Choose "+STYLES);
  if(k.equals("create_workshop")||k.equals("workshop")&&(s.isEmpty()||s.equals("default")))return "create";
  if(s.isEmpty()||s.equals("default"))return switch(k){case "skyscraper","apartment","greenhouse"->"modern";case "factory","warehouse"->"industrial";default->s;};
  return s;
 }
 /** Only for older recipes without an explicit saved floor count. Corner towers do not establish keep floors. */
 public static int inferredFloors(String kind,int width,int length,int height){
  String k=canonical(kind);
  if(k.equals("castle")&&(width<23||length<23)||k.equals("tower")&&(width<11||length<11))return 1;
  return List.of("tower","watchtower","skyscraper","apartment","inn","castle","outpost").contains(k)?Math.max(1,(height-1)/4):1;
 }
 public static Preset preset(String kind,String size,int requestedFloors){
  String k=canonical(kind),s=size==null||size.isBlank()?"medium":size.strip().toLowerCase(Locale.ROOT);
  if(!supports(k))throw new IllegalArgumentException("Unknown structure kind: "+k+". Choose "+KINDS);
  int tier=switch(s){case "small"->0;case "medium"->1;case "large"->2;default->throw new IllegalArgumentException("Size must be small, medium or large");};
  if(requestedFloors<0||requestedFloors>32)throw new IllegalArgumentException("Floors must be 1..32 (0 selects preset)");
  boolean compound=k.equals("castle")||k.equals("outpost");
  boolean tall=List.of("tower","watchtower","skyscraper","apartment","inn").contains(k);
  int floors=requestedFloors>0?requestedFloors:k.equals("skyscraper")?new int[]{5,9,12}[tier]:tall?new int[]{2,3,5}[tier]:compound?new int[]{1,2,3}[tier]:1;
  if((k.equals("bridge")||k.equals("platform"))&&requestedFloors>1)throw new IllegalArgumentException("Bridge/platform has one deck");
  int w=compound?new int[]{23,27,35}[tier]:new int[]{11,15,21}[tier];
  int l=compound?new int[]{25,31,39}[tier]:new int[]{13,19,27}[tier];
  if(List.of("house","cottage","workshop","create_workshop").contains(k)&&floors==1){w=new int[]{7,11,17}[tier];l=new int[]{9,15,23}[tier];return new Preset(k,s,w,l,new int[]{7,9,11}[tier],1);}
  if(k.equals("bridge"))return new Preset(k,s,new int[]{5,7,9}[tier],new int[]{13,25,41}[tier],4,1);
  if(k.equals("platform"))return new Preset(k,s,w,l,3,1);
  return new Preset(k,s,w,l,Math.max(compound?9:5,4*floors+1),floors);
 }
 public static String catalog(){return catalog("");}
 public static String catalog(String kind){return catalog(kind,"en");}
 public static String catalog(String kind,String language){
  String selected=kind==null?"":canonical(kind);
  StringBuilder out=new StringBuilder(ModTranslations.text(language,"verity_companion.structures.header",String.join(", ",STYLES)));
  for(String k:KINDS){if(!selected.isEmpty()&&!selected.equals(k))continue;out.append(k).append(": ");for(String size:List.of("small","medium","large")){Preset p=preset(k,size,0);out.append(size).append(' ').append(p.width).append('x').append(p.length).append('x').append(p.height).append(" / ").append(p.floors).append(ModTranslations.text(language,"verity_companion.structures.floor_budget")).append(p.volume()).append("; ");}out.append('\n');}
  return out.append(ModTranslations.text(language,"verity_companion.structures.footer")).toString();
 }
}
