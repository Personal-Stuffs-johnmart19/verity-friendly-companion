package dev.johnmart19.veritycompanion;

import java.util.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Explicit, session-only owner following. Resolves loaded entities only; never teleports. */
public final class CompanionFollow {
 private record Following(UUID body,ResourceKey<Level> dimension){}
 private static final Map<UUID,Following> ACTIVE=new HashMap<>();
 private static final ArrayDeque<UUID> ORDER=new ArrayDeque<>();
 private static int ticks;
 private CompanionFollow(){}
 static void register(){
  NeoForge.EVENT_BUS.addListener(CompanionFollow::tick);
  NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e)->{if(e.getEntity() instanceof ServerPlayer p)stop(p);});
  NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e)->{if(e.getEntity() instanceof ServerPlayer p)stop(p);});
  NeoForge.EVENT_BUS.addListener((LivingDeathEvent e)->{if(e.getEntity() instanceof ServerPlayer p)stop(p);});
 }
 static String set(ServerPlayer player,boolean enabled){
  if(!enabled){stop(player);return Relations.tr(player,"Следование выключено.","Following disabled.");}
  Entity entity=CompanionPresence.ownedBody(player);
  if(!(entity instanceof PathfinderMob mob))return Relations.tr(player,"Твой Verity не найден рядом в загруженной области. /companion come или summon, затем follow.","Your owned Verity was not found nearby in loaded chunks. Use /companion come or summon, then follow.");
  if(!player.isAlive()||player.isSpectator())return Relations.tr(player,"Следование доступно живому игроку вне режима наблюдателя.","Following requires a living player outside spectator mode.");
  stop(player);mob.setNoAi(false);mob.setTarget(null);ACTIVE.put(player.getUUID(),new Following(mob.getUUID(),player.level().dimension()));ORDER.addLast(player.getUUID());
  return Relations.tr(player,"Иду за тобой. /companion stay или follow off — остановиться. Только эта сессия; до 24 блоков, по загруженному пути, без телепортации.","Following you. /companion stay or follow off stops it. This session only; up to 24 blocks along loaded paths, without teleporting.");
 }
 static void stop(ServerPlayer player){remove(player.getServer(),player.getUUID());ORDER.remove(player.getUUID());}
 private static void remove(MinecraftServer server,UUID owner){
  Following following=ACTIVE.remove(owner);if(following==null)return;ServerLevel level=server.getLevel(following.dimension());
  Entity entity=level==null?null:level.getEntity(following.body());
  if(entity instanceof PathfinderMob mob&&owner.equals(CompanionPresence.owner(mob))){mob.getNavigation().stop();mob.setNoAi(true);}
 }
 static void clear(){ACTIVE.clear();ORDER.clear();ticks=0;}
 private static void tick(ServerTickEvent.Post event){
  if(++ticks%10!=0||ORDER.isEmpty())return;
  // Round robin bounds work even when many owners enable follow simultaneously.
  int budget=Math.min(8,ORDER.size());MinecraftServer server=event.getServer();
  for(int i=0;i<budget;i++){
   UUID owner=ORDER.removeFirst();Following following=ACTIVE.get(owner);if(following==null)continue;
   ServerPlayer player=server.getPlayerList().getPlayer(owner);ServerLevel level=server.getLevel(following.dimension());Entity body=level==null?null:level.getEntity(following.body());
   if(player==null||!player.isAlive()||player.isSpectator()||player.level()!=level||!(body instanceof PathfinderMob mob)||!body.isAlive()||!owner.equals(CompanionPresence.owner(body))||!CompanionPresence.friendly(body)){remove(server,owner);continue;}
   ORDER.addLast(owner);double distance=mob.distanceToSqr(player);
   if(distance<=4||distance>24*24||mob.isNoAi()){mob.getNavigation().stop();continue;}
   if(distance<=9)continue;
   // Vanilla createPath(BlockPos,accuracy,maxDistance) builds a radius maxDistance+8
   // region and can request its chunks. Check that entire bounded region first.
   int x=mob.blockPosition().getX(),z=mob.blockPosition().getZ();boolean loaded=true;
   for(int cx=(x-32)>>4;cx<=(x+32)>>4&&loaded;cx++)for(int cz=(z-32)>>4;cz<=(z+32)>>4;cz++)if(level.getChunkSource().getChunkNow(cx,cz)==null){loaded=false;break;}
   if(!loaded){mob.getNavigation().stop();continue;}
   mob.setTarget(null);mob.getLookControl().setLookAt(player,30,30);
   var path=mob.getNavigation().createPath(player.blockPosition(),0,24);
   if(path==null)mob.getNavigation().stop();else mob.getNavigation().moveTo(path,1.0);
  }
 }
}
