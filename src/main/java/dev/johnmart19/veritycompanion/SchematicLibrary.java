package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;

/** Server-approved local native structures, decoded by Minecraft's actual StructureTemplate loader. */
final class SchematicLibrary {
 static final Path ROOT=Path.of("config/verity-companion-blueprints");
 private record Cached(long size,long modified,NativeSchematicAdapter.Plan plan){}
 private static final Map<String,Cached> CACHE=new HashMap<>();
 static synchronized void clear(){CACHE.clear();}
 static NativeSchematicAdapter.Plan load(ServerPlayer p,String file){try{return file!=null&&file.startsWith("resource:")?NativeStructureIndex.read(p,file):read(p,file);}catch(Exception failure){throw new IllegalArgumentException("Schematic unavailable: "+failure.getMessage());}}
 static String preferred(){for(String name:List.of("plains_medium_house_1.nbt","plains_small_house_1.nbt"))if(Files.isRegularFile(ROOT.resolve(name)))return name;return "";}
 static synchronized NativeSchematicAdapter.Plan read(ServerPlayer p,String file)throws Exception{
  Path path=NativeSchematicAdapter.resolveFile(ROOT,file);long size=Files.size(path),modified=Files.getLastModifiedTime(path).toMillis();Cached old=CACHE.get(file);
  if(old!=null&&old.size==size&&old.modified==modified)return old.plan;
  var plan=NativeSchematicAdapter.read(ROOT,file,p.serverLevel().holderLookup(Registries.BLOCK),CompanionBrain.settings.maxBlocksPerBuild,NativeSchematicAdapter.Mode.DECORATIVE);
  if(CACHE.size()>=32)CACHE.clear();CACHE.put(file,new Cached(size,modified,plan));return plan;
 }
 static String summary(ServerPlayer p){
  JsonObject result=JsonParser.parseString(list(p)).getAsJsonObject();String language=Relations.get(p).language;
  JsonArray rows=result.getAsJsonArray("schematics");StringBuilder out=new StringBuilder(ModTranslations.text(language,"verity_companion.schematics.heading",rows.size(),result.get("matched"),result.get("next_offset")));
  for(JsonElement element:rows)out.append('\n').append(element.getAsJsonObject().get("file").getAsString());
  if(rows.isEmpty())out.append('\n').append(ModTranslations.text(language,"verity_companion.schematics.empty"));
  return out.append('\n').append(ModTranslations.text(language,"verity_companion.schematics.note")).toString();
 }
 static String list(ServerPlayer p){return list(p,new JsonObject());}
 static String list(ServerPlayer p,JsonObject args){JsonObject out=new JsonObject();List<String> names=new ArrayList<>();String query=CompanionBrain.string(args,"query","").toLowerCase(Locale.ROOT),source=CompanionBrain.string(args,"source","all");
  if(!Set.of("all","local","native").contains(source))throw new IllegalArgumentException("source: all, local or native");
  if(!source.equals("native")&&Files.isDirectory(ROOT))try(var stream=Files.list(ROOT)){for(Path path:stream.filter(f->f.getFileName().toString().endsWith(".nbt")).sorted().toList()){String name=path.getFileName().toString();if(name.toLowerCase(Locale.ROOT).contains(query))names.add(name);}}catch(java.io.IOException unavailable){out.addProperty("local_status","unavailable");}
  if(!source.equals("local"))for(String name:NativeStructureIndex.matching(query))names.add("resource:"+name);
  int offset=Math.clamp(CompanionBrain.number(args,"offset",0),0,names.size()),limit=Math.clamp(CompanionBrain.number(args,"limit",8),1,8);JsonArray rows=new JsonArray();
  for(String name:names.subList(offset,Math.min(names.size(),offset+limit))){JsonObject row=new JsonObject();row.addProperty("file",name);row.addProperty("source",name.startsWith("resource:")?"installed native server resource":"approved local file");
   // Exact selection requests inspect geometry; broad discovery never decodes thousands of structures.
   if(query.equals(name.toLowerCase(Locale.ROOT))||query.equals(name.replaceFirst("^resource:","").toLowerCase(Locale.ROOT)))try{var plan=load(p,name);row.addProperty("width",plan.width());row.addProperty("length",plan.length());row.addProperty("height",plan.height());row.addProperty("blocks",plan.cells().size());row.addProperty("metadata_stripped",plan.strippedBlockEntities());row.addProperty("jigsaws_resolved",plan.replacedJigsaws());}catch(Exception invalid){row.addProperty("available",false);row.addProperty("reason",invalid.getMessage());}
   rows.add(row);
  }
  out.add("schematics",rows);out.addProperty("matched",names.size());out.addProperty("offset",offset);out.addProperty("next_offset",offset+limit<names.size()?offset+limit:-1);out.addProperty("native_index",NativeStructureIndex.status());out.addProperty("native_total",NativeStructureIndex.size());
  out.addProperty("note","Select a listed file or resource: ID. Exact query inspects dimensions. Native assets can be individual structure pieces, not whole jigsaw assemblies. Imported entities, commands, inventories and machine NBT are not executed; unsupported blocks are rejected before placement. build_schematic uses actual bounds, permitted loaded cells and owner undo journal.");return out.toString();
 }
 static String styled(String state,String style){if(!style.equals("create"))return state;String id=state.split("\\[",2)[0],replacement="";
  if(id.equals("minecraft:oak_planks")||id.equals("minecraft:spruce_planks"))replacement="create:andesite_casing";
  else if(id.endsWith("_log")||id.endsWith("_wood"))replacement="create:metal_girder";
  else if(id.equals("minecraft:glass")||id.equals("minecraft:glass_pane"))replacement="create:industrial_iron_window";
  return !replacement.isEmpty()&&net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(net.minecraft.resources.ResourceLocation.parse(replacement))?replacement:state;
 }
}
