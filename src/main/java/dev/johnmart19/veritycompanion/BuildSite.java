package dev.johnmart19.veritycompanion;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;

/** Read-only loaded-terrain survey. Surface samples are suggestions, never placement approval. */
final class BuildSite {
 private BuildSite(){}
 static String survey(ServerPlayer player,JsonObject args){
  ServerLevel level=player.serverLevel();BlockPos playerPos=player.blockPosition();
  int width=clamp(CompanionBrain.number(args,"width",17),9,25),length=clamp(CompanionBrain.number(args,"length",17),9,25);
  int height=clamp(CompanionBrain.number(args,"height",9),6,24),radius=clamp(CompanionBrain.number(args,"radius",40),8,48);
  boolean preferWater=args.has("prefer_water")&&args.get("prefer_water").getAsBoolean();
  Candidate best=null;int sampled=0;
  // At most 7x7 candidates, nine surface samples each: 441 heightmap reads.
  for(int gx=-3;gx<=3;gx++)for(int gz=-3;gz<=3;gz++){
   int x=playerPos.getX()+gx*radius/3-width/2,z=playerPos.getZ()+gz*radius/3-length/2;
   if(Math.abs(x-playerPos.getX())>radius||Math.abs(x+width-1-playerPos.getX())>radius||Math.abs(z-playerPos.getZ())>radius||Math.abs(z+length-1-playerPos.getZ())>radius)continue;
   boolean loaded=true;
   for(int cx=x>>4;cx<=(x+width-1)>>4;cx++)for(int cz=z>>4;cz<=(z+length-1)>>4;cz++)
    if(!level.hasChunk(cx,cz))loaded=false;
   if(!loaded)continue;
   int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE,water=0;boolean valid=true;
   for(int ix=0;ix<3;ix++)for(int iz=0;iz<3;iz++){
    int sx=x+ix*(width-1)/2,sz=z+iz*(length-1)/2;
    int surface=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,sx,sz);sampled++;
    if(surface<=level.getMinBuildHeight()||surface+height>level.getMaxBuildHeight())valid=false;
    min=Math.min(min,surface);max=Math.max(max,surface);
    if(surface>level.getMinBuildHeight()&&level.getFluidState(new BlockPos(sx,surface-1,sz)).is(FluidTags.WATER))water++;
   }
   if(!valid||max-min>3||(preferWater?water<5:water>4))continue;
   BlockPos low=new BlockPos(x,max,z),high=new BlockPos(x+width-1,max+height-1,z+length-1);
   if(!level.getWorldBorder().isWithinBounds(low)||!level.getWorldBorder().isWithinBounds(high))continue;
   double distance=Math.hypot(x+width/2-playerPos.getX(),z+length/2-playerPos.getZ());
   double score=distance+8*(max-min)+(preferWater?(water>0?-80:0):water*8);
   if(best==null||score<best.score)best=new Candidate(x,max,z,water,max-min,score);
  }
  if(best==null)return "No suitable loaded sampled footprint found within radius "+radius+"; surface_samples="+sampled+". No chunks loaded or world changes made. Choose another nearby area or a smaller footprint.";
  return "Candidate build origin relative to player: x="+(best.x-playerPos.getX())+", y="+(best.y-playerPos.getY())+", z="+(best.z-playerPos.getZ())+"; width="+width+", length="+length+", height="+height+", baseY="+best.y+"; water_surface_samples="+best.water+"/9, surface_height_spread="+best.spread+", total_surface_samples="+sampled+". Entire footprint chunks are loaded and bounds checked. Only nine surface points sampled; full volume/ground support/claims/occupancy not checked. Water samples suggest water in the footprint, not a verified lake. Placement preserves occupied/protected cells; no world changes made.";
 }
 private static int clamp(int value,int min,int max){return Math.max(min,Math.min(max,value));}
 private record Candidate(int x,int y,int z,int water,int spread,double score){}
}
