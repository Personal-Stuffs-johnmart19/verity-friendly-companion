package dev.johnmart19.veritycompanion;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Original atmosphere/assets through individual encounters, independent of world day or game rules. */
final class HorrorController {
 private static final String STALKER_OWNER="verity_horror_stalker_owner";
 private record Episode(Entity stalker,long ends){}
 private static final Map<UUID,Episode> EPISODES=new HashMap<>();
 private static final Map<UUID,Clock> CLOCKS=new LinkedHashMap<>();
 private static final class Clock {long nextCheck,lastEvent=Long.MIN_VALUE/4,lastBoss=Long.MIN_VALUE/4;}
 private static int ticks,cursor;
 private HorrorController(){}
 static void register(){NeoForge.EVENT_BUS.addListener(HorrorController::tick);}
 private static long now(){return System.nanoTime()/1_000_000L;}
 private static Clock clock(ServerPlayer p){
  if(!CLOCKS.containsKey(p.getUUID())&&CLOCKS.size()>=1024)CLOCKS.remove(CLOCKS.keySet().iterator().next());
  return CLOCKS.computeIfAbsent(p.getUUID(),id->new Clock());
 }
 static boolean moodEncounter(ServerPlayer p){return CompanionBrain.settings.relationships&&Relations.get(p).state.mood<=HorrorSettings.get().moodThreshold;}
 private static boolean eligible(ServerPlayer p,HorrorPolicy.Mode mode){
  if(p.hasDisconnected()||!p.isAlive()||p.isSpectator()||p.isCreative())return false;
  if(mode==HorrorPolicy.Mode.MOOD&&!CompanionBrain.settings.relationships)return false;
  return HorrorPolicy.eligible(mode,Relations.get(p).state.mood,HorrorSettings.get().moodThreshold);
 }
 static boolean bossAllowed(ServerPlayer p){
  var o=HorrorSettings.get();return eligible(p,HorrorPolicy.parseMode(o.mode))&&eligible(p,HorrorPolicy.parseMode(o.bossMode));
 }
 static void onMood(ServerPlayer p){if(bossAllowed(p)&&HorrorSettings.get().bossMode.equals("mood"))maybeBoss(p,now(),0);}
 private static boolean maybeBoss(ServerPlayer p,long now,double roll){
  if(!bossAllowed(p)||RivalController.ACTIVE.containsKey(p.getUUID()))return false;
  var o=HorrorSettings.get();Clock clock=clock(p);var mode=HorrorPolicy.parseMode(o.bossMode);
  if(!HorrorPolicy.shouldTrigger(mode,Relations.get(p).state.mood,o.moodThreshold,o.chancePercent,roll,now-clock.lastBoss,o.bossCooldownSeconds*1000L))return false;
  endEpisode(p);RivalController.spawn(p);
  if(!RivalController.ACTIVE.containsKey(p.getUUID()))return false;
  clock.lastBoss=now;clock.lastEvent=now;return true;
 }
 private static void tick(ServerTickEvent.Post event){
  if(++ticks%20!=0)return;CompanionBrain.init();var options=HorrorSettings.get();var players=event.getServer().getPlayerList().getPlayers();
  if(players.isEmpty()){cursor=0;return;}int count=Math.min(8,players.size());long now=now();
  for(int i=0;i<count;i++){
   ServerPlayer p=players.get(Math.floorMod(cursor++,players.size()));Episode episode=EPISODES.get(p.getUUID());
   boolean allowed=eligible(p,HorrorPolicy.parseMode(options.mode));
   if(episode!=null&&(!allowed||now>=episode.ends())){endEpisode(p);episode=null;}
   if(episode!=null&&options.darkness)p.addEffect(new MobEffectInstance(MobEffects.DARKNESS,60,0,false,false,false));
   if(!allowed){if(RivalController.ACTIVE.containsKey(p.getUUID()))RivalController.remove(p.getUUID());continue;}
   if(RivalController.ACTIVE.containsKey(p.getUUID()))continue;
   Clock clock=clock(p);if(now<clock.nextCheck)continue;clock.nextCheck=now+options.intervalSeconds*1000L;
   double roll=p.getRandom().nextDouble();if(maybeBoss(p,now,roll))continue;
   if(!HorrorPolicy.shouldTrigger(HorrorPolicy.parseMode(options.mode),Relations.get(p).state.mood,options.moodThreshold,options.chancePercent,roll,now-clock.lastEvent,options.cooldownSeconds*1000L))continue;
   if(!(options.darkness||options.fog||options.sounds||options.stalker))continue;
   clock.lastEvent=now;startEpisode(p,options,now);
  }
 }
 private static void startEpisode(ServerPlayer p,HorrorSettings.Options options,long now){
  endEpisode(p);Entity stalker=options.stalker?spawnStalker(p):null;
  EPISODES.put(p.getUUID(),new Episode(stalker,now+options.durationSeconds*1000L));
  if(options.darkness)p.addEffect(new MobEffectInstance(MobEffects.DARKNESS,60,0,false,false,false));
  PacketDistributor.sendToPlayer(p,new HorrorVisualState(stalker==null?-1:stalker.getId(),options.durationSeconds*20,options.fog,false));
  if(options.sounds){String[] sounds={"bone_snap","bone_break","jumpscare"};play(p,sounds[p.getRandom().nextInt(sounds.length)]);}
 }
 private static Entity spawnStalker(ServerPlayer p){
  var key=ResourceLocation.parse("verity:verity_demon");if(!BuiltInRegistries.ENTITY_TYPE.containsKey(key))return null;
  Entity entity=BuiltInRegistries.ENTITY_TYPE.get(key).create(p.serverLevel());if(!(entity instanceof Mob mob))return null;
  BlockPos at=p.blockPosition();BlockPos chosen=null;
  // Window encounter: bounded loaded-only glass/window search; no glass or doors are broken.
  outer:for(int dx=-6;dx<=6;dx++)for(int dz=-6;dz<=6;dz++)for(int dy=-1;dy<=2;dy++){
   BlockPos window=at.offset(dx,dy,dz);if(!p.serverLevel().hasChunkAt(window))continue;
   String id=BuiltInRegistries.BLOCK.getKey(p.serverLevel().getBlockState(window).getBlock()).getPath();
   if(!(id.contains("glass")||id.contains("window")))continue;
   BlockPos candidate=window.offset(Integer.signum(dx)*2,0,Integer.signum(dz)*2);
   if(position(p,mob,candidate)){chosen=candidate;break outer;}
  }
  if(chosen==null)outer:for(int r=4;r<=8;r++)for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++){
   if(Math.abs(dx)!=r&&Math.abs(dz)!=r)continue;BlockPos candidate=at.offset(dx,0,dz);if(position(p,mob,candidate)){chosen=candidate;break outer;}
  }
  if(chosen==null)return null;RivalController.data(mob).putUUID(STALKER_OWNER,p.getUUID());mob.setNoAi(true);mob.setTarget(null);
  pose(mob,0);return p.serverLevel().addFreshEntity(mob)?mob:null;
 }
 private static boolean position(ServerPlayer p,Mob mob,BlockPos pos){
  if(!p.serverLevel().hasChunkAt(pos)||!p.serverLevel().isInWorldBounds(pos)||!p.serverLevel().getWorldBorder().isWithinBounds(pos)||!p.serverLevel().getBlockState(pos.below()).isSolid()||!p.serverLevel().getFluidState(pos).isEmpty())return false;
  mob.moveTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5,p.getYRot(),0);return p.serverLevel().noCollision(mob);
 }
 static void pose(Entity e,int state){try{e.getClass().getMethod("setDemonState",int.class).invoke(e,state);}catch(ReflectiveOperationException ignored){/* Missing animation must not enable native combat. */}}
 static boolean isStalker(Entity e){return RivalController.data(e).hasUUID(STALKER_OWNER);}
 static void stalkerTick(Entity entity){
  UUID owner=RivalController.data(entity).getUUID(STALKER_OWNER);Episode episode=EPISODES.get(owner);
  ServerPlayer p=entity.level().getServer().getPlayerList().getPlayer(owner);
  if(episode==null||episode.stalker()!=entity||p==null||p.level()!=entity.level()||now()>=episode.ends()||!eligible(p,HorrorPolicy.parseMode(HorrorSettings.get().mode))){entity.discard();return;}
  if(entity instanceof Mob mob){mob.setTarget(null);mob.getLookControl().setLookAt(p,30,30);mob.getLookControl().tick();}
 }
 static void bossPresentation(ServerPlayer p,Entity boss){
  var o=HorrorSettings.get();if(o.darkness)p.addEffect(new MobEffectInstance(MobEffects.DARKNESS,60,0,false,false,false));
  PacketDistributor.sendToPlayer(p,new HorrorVisualState(boss.getId(),60,o.fog,o.sounds));
 }
 private static void play(ServerPlayer p,String name){var id=ResourceLocation.parse("verity:"+name);if(BuiltInRegistries.SOUND_EVENT.containsKey(id))p.playNotifySound(BuiltInRegistries.SOUND_EVENT.get(id),SoundSource.HOSTILE,.5f,1f);}
 private static void endEpisode(ServerPlayer p){Episode previous=EPISODES.remove(p.getUUID());if(previous!=null&&previous.stalker()!=null)previous.stalker().discard();if(!p.hasDisconnected())PacketDistributor.sendToPlayer(p,new HorrorVisualState(-1,0,false,false));}
 static void stop(ServerPlayer p){endEpisode(p);RivalController.remove(p.getUUID());Clock clock=clock(p);clock.lastEvent=now();clock.lastBoss=now();}
 static void logout(ServerPlayer p){endEpisode(p);RivalController.remove(p.getUUID());}
 static void configurationChanged(MinecraftServer server){for(ServerPlayer p:server.getPlayerList().getPlayers()){endEpisode(p);RivalController.remove(p.getUUID());}}
 static void clear(){for(Episode episode:EPISODES.values())if(episode.stalker()!=null)episode.stalker().discard();EPISODES.clear();CLOCKS.clear();ticks=cursor=0;HorrorSettings.resetCache();}
}
