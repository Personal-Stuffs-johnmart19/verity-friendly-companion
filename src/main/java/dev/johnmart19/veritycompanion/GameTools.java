package dev.johnmart19.veritycompanion;
import com.google.gson.*;import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/** Small authenticated-player observations. No client entity ID or chunk generation required. */
final class GameTools {
 static String clean(String text){String clean=text.replaceAll("[\\p{Cntrl}]"," ");return clean.substring(0,Math.min(160,clean.length()));}
 static String location(ServerPlayer p){JsonObject out=new JsonObject();out.addProperty("dimension",p.serverLevel().dimension().location().toString());out.addProperty("biome",p.serverLevel().getBiome(p.blockPosition()).unwrapKey().map(k->k.location().toString()).orElse("unknown"));out.addProperty("x",p.getX());out.addProperty("y",p.getY());out.addProperty("z",p.getZ());
  BlockPos feet=BlockPos.containing(p.getX(),p.getBoundingBox().minY-.01,p.getZ());out.addProperty("block_directly_under_feet",BuiltInRegistries.BLOCK.getKey(p.serverLevel().getBlockState(feet).getBlock()).toString());out.addProperty("under_feet_position",feet.toShortString());out.addProperty("on_ground",p.onGround());
  Vec3 start=p.getEyePosition(),end=start.add(p.getViewVector(1).scale(6));boolean loaded=true;int minX=Math.min((int)Math.floor(start.x),(int)Math.floor(end.x)),maxX=Math.max((int)Math.floor(start.x),(int)Math.floor(end.x));int minZ=Math.min((int)Math.floor(start.z),(int)Math.floor(end.z)),maxZ=Math.max((int)Math.floor(start.z),(int)Math.floor(end.z));
  for(int x=minX>>4;x<=maxX>>4;x++)for(int z=minZ>>4;z<=maxZ>>4;z++)if(!p.serverLevel().hasChunk(x,z))loaded=false;
  if(loaded){var hit=p.serverLevel().clip(new ClipContext(start,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,p));if(hit.getType()==HitResult.Type.BLOCK){out.addProperty("looked_at_block",BuiltInRegistries.BLOCK.getKey(p.serverLevel().getBlockState(hit.getBlockPos()).getBlock()).toString());out.addProperty("looked_at_position",hit.getBlockPos().toShortString());}else out.addProperty("looked_at_block","no loaded block in 6-block reach");}
  try{out.add("settlement_evidence",JsonParser.parseString(SettlementTools.inspect(p)));}catch(RuntimeException|LinkageError unavailable){out.addProperty("settlement_evidence","unavailable; do not infer village absence");}
  return out.toString();
 }
 static String nearby(ServerPlayer p,JsonObject args){int radius=Math.clamp(CompanionBrain.number(args,"radius",48),1,96),offset=Math.clamp(CompanionBrain.number(args,"offset",0),0,256),limit=Math.clamp(CompanionBrain.number(args,"limit",12),1,24);String query=CompanionBrain.string(args,"query","").toLowerCase(Locale.ROOT);
  List<Mob> mobs=new ArrayList<>();
  p.serverLevel().getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(Mob.class),p.getBoundingBox().inflate(radius),e->e.isAlive()&&e.distanceToSqr(p)<=radius*radius,mobs,257);
  boolean capped=mobs.size()>256;if(capped)mobs.removeLast();
  var matching=mobs.stream().filter(e->(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())+" "+e.getName().getString()).toLowerCase(Locale.ROOT).contains(query)).sorted(Comparator.comparingDouble(e->e.distanceToSqr(p))).toList();JsonArray rows=new JsonArray();
  for(int i=offset;i<Math.min(matching.size(),offset+limit);i++){Mob e=matching.get(i);JsonObject row=new JsonObject();row.addProperty("entity_id",e.getId());row.addProperty("type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());row.addProperty("name_untrusted_data",clean(e.getName().getString()));row.addProperty("distance",Math.round(Math.sqrt(e.distanceToSqr(p))*10)/10.0);row.addProperty("position",e.blockPosition().toShortString());row.addProperty("health",e.getHealth());row.addProperty("max_health",e.getMaxHealth());row.addProperty("targeting_requesting_player",e.getTarget()==p);row.addProperty("potential_hostile_type",e instanceof net.minecraft.world.entity.monster.Enemy);row.addProperty("recently_damaged_requesting_player",ThreatEvents.recentlyDamaged(p,e.getUUID()));row.addProperty("boss_evidence",WorldEvents.evidence(e));rows.add(row);}
  JsonObject out=new JsonObject();out.addProperty("snapshot_at_epoch_millis",System.currentTimeMillis());out.addProperty("dimension",p.serverLevel().dimension().location().toString());out.addProperty("radius",radius);out.addProperty("loaded_only",true);out.addProperty("candidate_cap",256);out.addProperty("capped",capped);out.addProperty("matches_in_sample",matching.size());out.addProperty("next_offset",offset+limit<matching.size()?offset+limit:-1);out.addProperty("target_interpretation","targeting_requesting_player is only a transient AI target snapshot. False does not prove no attack, no danger or player invulnerability. potential_hostile_type identifies the Enemy interface, not a confirmed current attack. Check recent actual damage and respect the player's report; snapshot may already have changed.");out.add("mobs",rows);return out.toString();
 }
 static String companion(ServerPlayer p){var e=CompanionPresence.select(p);JsonObject out=new JsonObject();if(e==null){out.addProperty("status","not_found_in_loaded_entities");out.addProperty("note","Unloaded chunks were not searched. Companion may be elsewhere; do not invent a location.");return out.toString();}out.addProperty("type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());out.addProperty("entity_id",e.getId());out.addProperty("dimension",e.level().dimension().location().toString());out.addProperty("position",e.blockPosition().toShortString());out.addProperty("name_untrusted_data",clean(e.getName().getString()));if(e.level()==p.level())out.addProperty("distance",Math.round(Math.sqrt(e.distanceToSqr(p))*10)/10.0);else out.addProperty("distance","different dimension");return out.toString();}
 static JsonObject args(String key,String value){JsonObject a=new JsonObject();a.addProperty(key,value);return a;}
 static void observe(StringBuilder out,String label,java.util.function.Supplier<String> read){out.append('\n').append(label).append(": ");try{out.append(read.get());}catch(RuntimeException|LinkageError unavailable){out.append("unavailable; do not infer missing facts");System.err.println("[Verity Companion] snapshot adapter "+label+": "+unavailable.getClass().getSimpleName());}}
 static void observe(SnapshotSections out,String label,java.util.function.Supplier<String> read){
  int priority=switch(label){case "Actual held item and enchantments"->110;case "Actual full player inventory"->105;case "Own live player status"->90;case "Actual blocks/position"->85;case "Loaded nearby mobs"->40;default->100;};
  int cap=label.contains("inventory")||label.contains("held item")?1400:label.equals("Loaded nearby mobs")?900:1100;
  String result;try{result=read.get();}catch(RuntimeException|LinkageError unavailable){result="unavailable; do not infer missing facts";System.err.println("[Verity Companion] snapshot adapter "+label+": "+unavailable.getClass().getSimpleName());}
  out.add(label,result,priority,cap);
 }
 static String compactLocation(ServerPlayer p){JsonObject result=JsonParser.parseString(location(p)).getAsJsonObject();JsonElement evidence=result.get("settlement_evidence");if(evidence!=null&&evidence.isJsonObject()){JsonObject compact=new JsonObject();for(String key:List.of("inside_known_village","inside_generated_village_bounds","inside_village_structure_piece","current_structure_check_complete","active_vanilla_village_poi_at_player","loaded_villagers_within_32_blocks_capped","nearby_building_signals"))if(evidence.getAsJsonObject().has(key))compact.add(key,evidence.getAsJsonObject().get(key));compact.addProperty("interpretation","Current-position containment is independent of distance to a village center. Use settlement for details; unknown does not mean absent.");result.add("settlement_evidence",compact);}return result.toString();}
 static String forQuestion(ServerPlayer p,String text){String s=text.toLowerCase(Locale.ROOT);SnapshotSections out=new SnapshotSections("Actual requesting player permission2="+p.createCommandSourceStack().hasPermission(2)+"; rejected intent/args are not permission denial.\n",5000);
  if(DeathLog.forQuestion(text))observe(out,"Recent confirmed player death",()->DeathEvents.recent(p));
  boolean combat=s.matches("(?s).*(атак|напал|напада|бьют|бьёт|бьет|удар|урон|паук|зомби|скелет|монстр|враг|защит|спас|сраж|бой|attack|hit|hurt|damage|fight|combat|spider|zombie|skeleton|defend|protect|threat).*");
  if(combat){JsonObject a=new JsonObject();a.addProperty("limit",2);observe(out,"Recent actual damage evidence",()->ThreatEvents.recent(p,a));}
  if(s.matches("(?s).*(эффект|баф|дебаф|effect|buff).*"))observe(out,"Own effects",()->EffectTools.status(p,new JsonObject()));
  if(combat||s.matches("(?s).*(моб|вокруг|creature|nearby|enemy|enemies).*")){JsonObject a=new JsonObject();a.addProperty("limit",combat?4:8);if(combat)a.addProperty("radius",24);observe(out,"Loaded nearby mobs",()->nearby(p,a));}
  observe(out,"Own live player status",()->PackTools.status(p));observe(out,"Actual blocks/position",()->compactLocation(p));
  if(s.matches("(?s).*(инвентар|вещи|предметы|экипиров|inventory|equipment|items|кто я|who am i).*"))observe(out,"Actual full player inventory",()->InventoryTools.snapshot(p));
  if(s.matches("(?s).*(зачаров|чары|кирк|предмет|инструмент|enchant|pickaxe|held|tool).*"))observe(out,"Actual held item and enchantments",()->PackTools.item(p,new JsonObject()));
  var itemNames=ServerItemCatalog.mentioned(text,3);if(!itemNames.isEmpty())observe(out,"Registered items actually named in the human request",()->itemNames.toString()+". Full modified names outrank generic nouns; search exact localized names before substituting.");
  if(s.matches("(?s).*(где ты|ты наход|ты далеко|далеко от меня|where are you|how far.*you).*"))observe(out,"Companion",()->companion(p));
  if(s.matches("(?s).*(пробуд|проснул|босс|boss|awaken).*")){JsonObject a=new JsonObject();a.addProperty("limit",5);observe(out,"Recent boss observations",()->WorldEvents.recent(p,a));}
  if(s.matches("(?s).*(ачив|достиж|achievement|advancement).*")){JsonObject a=args("status",s.matches("(?s).*(только что|последн|just|latest|recent).*")?"recent":"completed");a.addProperty("limit",6);observe(out,"Real advancements",()->AdvancementTools.view(p,a));}
  if(s.matches("(?s).*(квест|quest|задани|чем.*занят|прохожд).*")){JsonObject a=new JsonObject();a.addProperty("limit",5);observe(out,"Currently available FTB quests",()->QuestTools.next(p,a));}
  return out.render();
 }
}
