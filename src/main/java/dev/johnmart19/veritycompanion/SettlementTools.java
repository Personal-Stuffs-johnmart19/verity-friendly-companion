package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.*;

/** Current-point settlement evidence. Never locate/generate/load a world chunk. Server thread only. */
public final class SettlementTools {
 private SettlementTools() {}
 private static final int CHUNK_RADIUS=4, MAX_STARTS=64, BLOCK_RADIUS=8;
 record Bounds(int minX,int minY,int minZ,int maxX,int maxY,int maxZ){
  boolean contains(int x,int y,int z){return x>=minX&&x<=maxX&&y>=minY&&y<=maxY&&z>=minZ&&z<=maxZ;}
  double centerDistanceSquared(int x,int z){double dx=(minX+(long)maxX)/2.0-x,dz=(minZ+(long)maxZ)/2.0-z;return dx*dx+dz*dz;}
 }
 static boolean villageName(String id,Collection<String> tags){
  // Tags are authoritative classification; identifier tokens are reported separately as naming evidence.
  if(tags.stream().anyMatch(t->t.equals("minecraft:village")||t.matches("[^:]+:(?:.*/)?villages?(?:/.*)?")))return true;
  String path=id.substring(id.indexOf(':')+1);
  return path.matches("(?:.*[/_-])?(?:village|villages|hamlet|settlement)(?:[/_-].*)?");
 }
 static Boolean insideKnownVillage(boolean bounds,boolean activePoi,boolean complete){return bounds||activePoi?Boolean.TRUE:complete?Boolean.FALSE:null;}
 private static Bounds bounds(BoundingBox b){return new Bounds(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ());}
 private static JsonObject boundsJson(Bounds b){JsonObject out=new JsonObject();out.addProperty("min_x",b.minX());out.addProperty("min_y",b.minY());out.addProperty("min_z",b.minZ());out.addProperty("max_x",b.maxX());out.addProperty("max_y",b.maxY());out.addProperty("max_z",b.maxZ());return out;}
 private record VillageStart(String id,String classification,StructureStart start){}
 private static VillageStart classified(ServerLevel level,StructureStart start){
  if(start==null||!start.isValid())return null;
  var registry=level.registryAccess().registryOrThrow(Registries.STRUCTURE);
  var key=registry.getKey(start.getStructure());if(key==null)return null;
  var holder=registry.getHolder(key);List<String> tags=holder.stream().flatMap(h->h.tags()).map(t->t.location().toString()).toList();
  String id=key.toString();if(!villageName(id,tags))return null;
  boolean tagged=villageName("unknown:unclassified",tags);
  return new VillageStart(id,tagged?"structure_village_tag":"structure_identifier_village_token",start);
 }
 private static void addStart(ServerLevel level,StructureStart start,Map<String,VillageStart> starts){
  if(starts.size()>=MAX_STARTS)return;VillageStart village=classified(level,start);if(village!=null)starts.putIfAbsent(village.id()+"@"+start.getChunkPos().toLong(),village);
 }
 public static String inspect(ServerPlayer player){
  ServerLevel level=player.serverLevel();BlockPos pos=player.blockPosition();int cx=pos.getX()>>4,cz=pos.getZ()>>4;
  JsonObject out=new JsonObject();out.addProperty("source","loaded_server_structure_metadata_and_cached_village_poi");out.addProperty("dimension",level.dimension().location().toString());out.addProperty("position",pos.toShortString());
  var current=level.getChunkSource().getChunkNow(cx,cz);boolean complete=current!=null;int unloadedReferences=0;
  Map<String,VillageStart> starts=new LinkedHashMap<>();
  Set<String> directBounds=new HashSet<>(),directPieces=new HashSet<>();
  if(current!=null){
   for(var start:current.getAllStarts().values())addStart(level,start,starts);
   var registry=level.registryAccess().registryOrThrow(Registries.STRUCTURE);
   int checked=0,referenceBudget=256;
   for(var ref:current.getAllReferences().entrySet()){
    var id=registry.getKey(ref.getKey());if(id==null)continue;
    var holder=registry.getHolder(id);var tags=holder.stream().flatMap(h->h.tags()).map(t->t.location().toString()).toList();
    if(!villageName(id.toString(),tags))continue;
    if(++checked>MAX_STARTS){complete=false;break;}
    boolean allLoaded=true;
    for(long packed:ref.getValue()){
     if(--referenceBudget<0){allLoaded=false;complete=false;break;}
     if(starts.size()>=MAX_STARTS){allLoaded=false;complete=false;break;}
     ChunkPos startPos=new ChunkPos(packed);var chunk=level.getChunkSource().getChunkNow(startPos.x,startPos.z);
     if(chunk==null){allLoaded=false;complete=false;unloadedReferences++;continue;}
     addStart(level,chunk.getStartForStructure(ref.getKey()),starts);
    }
    // These APIs otherwise load referenced start chunks. Same-thread getChunkNow guards every reference.
    if(allLoaded){
     var piece=level.structureManager().getStructureWithPieceAt(pos,ref.getKey());
     if(piece.isValid())directPieces.add(id.toString()+"@"+piece.getChunkPos().toLong());
     var area=level.structureManager().getStructureAt(pos,ref.getKey());
     if(area.isValid())directBounds.add(id.toString()+"@"+area.getChunkPos().toLong());
    }
   }
  }
  // Nearby centers are metadata hints only; they are never used to decide current-point containment.
  int loadedChunks=0,unloadedChunks=0;
  for(int x=cx-CHUNK_RADIUS;x<=cx+CHUNK_RADIUS;x++)for(int z=cz-CHUNK_RADIUS;z<=cz+CHUNK_RADIUS;z++){
   var chunk=level.getChunkSource().getChunkNow(x,z);if(chunk==null){unloadedChunks++;continue;}loadedChunks++;
   if(starts.size()<MAX_STARTS)for(var start:chunk.getAllStarts().values())addStart(level,start,starts);
  }
  JsonArray containing=new JsonArray();int containingCount=0;VillageStart nearest=null;double nearestDistance=Double.POSITIVE_INFINITY;boolean inBounds=false,inPiece=false;
  for(var entry:starts.entrySet()){
   VillageStart village=entry.getValue();Bounds b=bounds(village.start().getBoundingBox());
   boolean atBounds=directBounds.contains(entry.getKey())||b.contains(pos.getX(),pos.getY(),pos.getZ());
   boolean atPiece=directPieces.contains(entry.getKey())||level.structureManager().structureHasPieceAt(pos,village.start());
   if(atBounds||atPiece){containingCount++;if(containing.size()<8){JsonObject row=new JsonObject();row.addProperty("structure_id",village.id());row.addProperty("classification_evidence",village.classification());row.addProperty("inside_structure_bounds",atBounds);row.addProperty("inside_structure_piece",atPiece);row.add("bounds",boundsJson(b));containing.add(row);}inBounds|=atBounds;inPiece|=atPiece;}
   double distance=b.centerDistanceSquared(pos.getX(),pos.getZ());if(distance<nearestDistance){nearestDistance=distance;nearest=village;}
  }
  boolean activePoi=current!=null&&level.isVillage(pos);int sectionDistance=current==null?-1:level.sectionsToVillage(net.minecraft.core.SectionPos.of(pos));
  Boolean inside=insideKnownVillage(inBounds,activePoi,complete);
  if(inside==null)out.add("inside_known_village",JsonNull.INSTANCE);else out.addProperty("inside_known_village",inside);
  if(inBounds||complete)out.addProperty("inside_generated_village_bounds",inBounds);else out.add("inside_generated_village_bounds",JsonNull.INSTANCE);
  if(inPiece||complete)out.addProperty("inside_village_structure_piece",inPiece);else out.add("inside_village_structure_piece",JsonNull.INSTANCE);
  out.addProperty("current_structure_check_complete",complete);out.addProperty("unloaded_start_references",unloadedReferences);out.add("containing_village_structures",containing);out.addProperty("containing_village_structures_total",containingCount);
  out.addProperty("active_vanilla_village_poi_at_player",activePoi);out.addProperty("poi_section_distance",sectionDistance);out.addProperty("poi_rule","ServerLevel.isVillage: section distance <= 1 from an occupied village POI section; not distance from worldgen center");
  if(nearest!=null){Bounds b=bounds(nearest.start().getBoundingBox());JsonObject row=new JsonObject();row.addProperty("structure_id",nearest.id());row.addProperty("classification_evidence",nearest.classification());row.addProperty("bounds_center_horizontal_distance",Math.round(Math.sqrt(nearestDistance)*10)/10.0);row.add("bounds",boundsJson(b));row.addProperty("center_distance_does_not_determine_containment",true);out.add("nearby_loaded_village_structure",row);}
  List<AbstractVillager> villagers=new ArrayList<>();level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(AbstractVillager.class),player.getBoundingBox().inflate(32),v->v.isAlive()&&v.distanceToSqr(player)<=32*32,villagers,32);
  out.addProperty("loaded_villagers_within_32_blocks_capped",villagers.size());out.addProperty("villager_cap_reached",villagers.size()>=32);
  int beds=0,doors=0,bells=0,skipped=0;
  for(int x=-BLOCK_RADIUS;x<=BLOCK_RADIUS;x++)for(int z=-BLOCK_RADIUS;z<=BLOCK_RADIUS;z++)for(int y=-4;y<=4;y++){
   BlockPos p=pos.offset(x,y,z);if(level.isOutsideBuildHeight(p))continue;if(level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)==null){skipped++;continue;}
   var state=level.getBlockState(p);if(state.is(BlockTags.BEDS))beds++;if(state.is(BlockTags.DOORS))doors++;if(state.is(Blocks.BELL))bells++;
  }
  JsonObject buildings=new JsonObject();buildings.addProperty("horizontal_radius",BLOCK_RADIUS);buildings.addProperty("vertical_radius",4);buildings.addProperty("bed_blocks",beds);buildings.addProperty("door_blocks",doors);buildings.addProperty("bell_blocks",bells);buildings.addProperty("unloaded_block_positions_skipped",skipped);buildings.addProperty("note","Local built-block signals only; bed/door halves count separately and do not prove a village or identify every building");out.add("nearby_building_signals",buildings);
  out.addProperty("nearby_chunk_scan_radius",CHUNK_RADIUS);out.addProperty("nearby_loaded_chunks_checked",loadedChunks);out.addProperty("nearby_unloaded_chunks_skipped",unloadedChunks);out.addProperty("structure_start_cap_reached",starts.size()>=MAX_STARTS);
  out.addProperty("limits","Containment uses the player's current XYZ inside actual recorded structure bounds/pieces, separately from cached occupied village POIs. A center 49 blocks away can belong to the village currently containing the player. False means no matching known evidence, not that a player-built or untagged settlement cannot exist. Unloaded starts make negative structure evidence incomplete; no chunks are loaded/generated and no global locate is run. Ruins and abandoned generated villages retain structure bounds even without villagers/active POIs.");
  return out.toString();
 }
}
