package dev.johnmart19.veritycompanion;

import java.util.List;
import java.util.Locale;

/** Fixed, attributed web references: no networking, file downloads or schematic execution. */
public final class BuildExamples {
 private BuildExamples(){}
 private record Example(String kinds,String title,String author,String create,String dimensions,String mods,String slug,String counterpart,String note){}
 private static final List<Example> EXAMPLES=List.of(
  new Example("house workshop","Starter House/Workshop by Keej_Inc","Repodraghon; original credited to Skeej_Inc/Keej_Inc","6.0.9","35 x 14 x 17","Create","starter-house-workshop-by-keej_inc","house / create_workshop","verity_companion.blueprints.house_note"),
  new Example("workshop factory","Workshop + Factory Building","Icyknightgaming","6.0.10","70 x 52 x 80","Create, Connected, Aeronautics (Simulated), Electro Energetics, Macaw's Roofs, Macaw's Fences and Walls, Colored Chain Conveyor, Encased, Design n' Decor, Bits 'n' Bobs, Nomansland, Aeronautics Utility Objects, Deco, Quark, Macaw's Lights and Lamps, Chipped, Macaw's Paths and Pavings, Copycats+, Steam 'n' Rails","workshop-factory-building","create_workshop","verity_companion.blueprints.workshop_note"),
  new Example("tower","Deepslate tower","Accmist","6.0.10","75 x 167 x 72","verity_companion.blueprints.tower_mods","deepslate-tower","tower","verity_companion.blueprints.tower_note"),
  new Example("bridge","lifting train bridge","Birdchan2","6.0.10","18 x 46 x 96","Create, Chipped, FramedBlocks, Create Deco","lifting-train-bridge","verity_companion.blueprints.bridge_counterpart","verity_companion.blueprints.bridge_note"),
  new Example("factory","Create 6.0 Brass Age Factory (NeoForge 1.21)","Kindlebubbledump","6.0.0","47 x 11 x 38","Create","create-6-0-brass-age-factory-neoforge-1-21","verity_companion.blueprints.factory_counterpart","verity_companion.blueprints.factory_note")
 );

 /** Empty query lists all. Recognized RU/EN building kinds select relevant references. */
 public static String catalog(String query){return catalog(query,"en");}
 public static String catalog(String query,String language){
  String q=query==null?"":query.strip().toLowerCase(Locale.ROOT);
  if(q.length()>256)return ModTranslations.text(language,"verity_companion.blueprints.query_too_long");
  boolean all=q.isEmpty()||q.equals("all")||q.equals("все")||q.equals("всё");
  boolean house=has(q,"house","cottage","дом","коттедж");
  boolean workshop=has(q,"workshop","мастерск","цех");
  boolean tower=has(q,"tower","башн");
  boolean bridge=has(q,"bridge","мост");
  boolean factory=has(q,"factory","фабрик","завод");
  String local=localKind(q);
  StringBuilder out=new StringBuilder(ModTranslations.text(language,"verity_companion.blueprints.header"));
  if(all||!local.isEmpty())out.append('\n').append(StructureCatalog.catalog(all?"":local,language));
  int count=0;
  for(Example e:EXAMPLES){
   if(!all&&!(house&&e.kinds.contains("house")||workshop&&e.kinds.contains("workshop")||tower&&e.kinds.contains("tower")||bridge&&e.kinds.contains("bridge")||factory&&e.kinds.contains("factory")))continue;
   count++;
   out.append('\n').append(e.title).append(" — ").append(e.author)
    .append("; Create ").append(e.create).append(ModTranslations.text(language,"verity_companion.blueprints.dimensions")).append(e.dimensions)
    .append(ModTranslations.text(language,"verity_companion.blueprints.required_mods")).append(display(e.mods,language))
    .append("\nhttps://createmod.com/schematics/").append(e.slug)
    .append(ModTranslations.text(language,"verity_companion.blueprints.local_counterpart")).append(display(e.counterpart,language)).append(". ").append(display(e.note,language)).append('\n');
  }
  if(count==0&&local.isEmpty()&&!all)out.append(ModTranslations.text(language,"verity_companion.blueprints.no_category",String.join(", ",StructureCatalog.KINDS)));
  return out.toString();
 }
 private static String display(String value,String language){return value.startsWith("verity_companion.")?ModTranslations.text(language,value):value;}
 private static String localKind(String q){
  if(has(q,"небоскреб","skyscraper","highrise"))return "skyscraper";
  if(has(q,"аванпост","форпост","outpost"))return "outpost";
  if(has(q,"замок","крепост","castle","fortress"))return "castle";
  if(has(q,"многоэтаж","этажн","apartment"))return "apartment";
  if(has(q,"библиотек","library"))return "library";
  if(has(q,"казарм","barracks"))return "barracks";
  if(has(q,"теплиц","greenhouse"))return "greenhouse";
  if(has(q,"конюшн","stable"))return "stable";
  if(has(q,"склад","warehouse"))return "warehouse";
  if(has(q,"гостиниц","трактир","inn"))return "inn";
  if(has(q,"стороже","watchtower"))return "watchtower";
  if(has(q,"house","cottage","дом","коттедж"))return "house";
  if(has(q,"workshop","мастерск","цех"))return "workshop";
  if(has(q,"tower","башн"))return "tower";
  if(has(q,"bridge","мост"))return "bridge";
  if(has(q,"factory","фабрик","завод"))return "factory";
  if(has(q,"platform","платформ"))return "platform";
  return "";
 }
 private static boolean has(String query,String... words){for(String word:words)if(query.contains(word))return true;return false;}
}
