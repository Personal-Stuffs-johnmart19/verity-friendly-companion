package dev.johnmart19.veritycompanion;
import com.google.gson.*;
import net.minecraft.core.*;import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;import net.minecraft.tags.TagKey;
import net.minecraft.server.level.ServerPlayer;
import java.util.Locale;
/** Uses the actual vanilla structure locator, never a model's guessed village coordinates. */
final class WorldLocate {
 static String locate(ServerPlayer p,JsonObject args){String query=CompanionBrain.string(args,"query","village").strip().toLowerCase(Locale.ROOT);int radius=Math.clamp(CompanionBrain.number(args,"radius",8),1,16);
  if(query.equals("village")||query.equals("деревня")||query.equals("деревню"))query="#minecraft:village";
  var registry=p.registryAccess().registryOrThrow(Registries.STRUCTURE);HolderSet<net.minecraft.world.level.levelgen.structure.Structure> holders;
  if(query.startsWith("#")){var tag=TagKey.create(Registries.STRUCTURE,ResourceLocation.parse(query.substring(1)));holders=registry.getTag(tag).orElseThrow(()->new IllegalArgumentException("Unknown structure tag; use registry kind=structures"));}
  else holders=HolderSet.direct(registry.getHolder(ResourceLocation.parse(query)).orElseThrow(()->new IllegalArgumentException("Unknown structure ID; use registry kind=structures")));
  var found=p.serverLevel().getChunkSource().getGenerator().findNearestMapStructure(p.serverLevel(),holders,p.blockPosition(),radius,false);JsonObject out=new JsonObject();out.addProperty("source","vanilla_server_structure_locator");out.addProperty("dimension",p.serverLevel().dimension().location().toString());out.addProperty("query",query);out.addProperty("search_radius_parameter",radius);out.addProperty("note","Bounded vanilla world-generation metadata search; not limited to already loaded mobs/chunks. No teleport or building performed.");
  if(found==null){out.addProperty("found",false);out.addProperty("reason","No match in the bounded search, not proof the dimension contains none");}else{out.addProperty("found",true);var pos=found.getFirst();out.addProperty("x",pos.getX());out.addProperty("y",pos.getY());out.addProperty("z",pos.getZ());out.addProperty("structure",found.getSecond().unwrapKey().map(k->k.location().toString()).orElse("unregistered"));out.addProperty("horizontal_distance",Math.round(Math.hypot(p.getX()-pos.getX(),p.getZ()-pos.getZ())));}
  return out.toString();
 }
}
