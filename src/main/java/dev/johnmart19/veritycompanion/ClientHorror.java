package dev.johnmart19.veritycompanion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Temporary local presentation; the original unconditional fog/gamma handlers stay disabled. */
public final class ClientHorror {
 private static final ResourceLocation CHASE=ResourceLocation.fromNamespaceAndPath("verity","chase");
 private static ClientLevel level;
 private static ClientPacketListener connection;
 private static int remaining,entityId=-1;
 private static boolean fog,chase,registered;
 private static Entity soundEntity;
 private static SoundInstance chaseSound;

 private ClientHorror() {}

 public static void register(){
  if(registered)return;registered=true;
  NeoForge.EVENT_BUS.addListener(ClientHorror::tick);
  NeoForge.EVENT_BUS.addListener(ClientHorror::color);
  NeoForge.EVENT_BUS.addListener(ClientHorror::fog);
  NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event)->clearNow());
 }

 public static void accept(HorrorVisualState state){
  Minecraft mc=Minecraft.getInstance();
  ClientLevel receivedLevel=mc.level;ClientPacketListener receivedConnection=mc.getConnection();
  mc.execute(()->{
   if(mc.level!=receivedLevel||mc.getConnection()!=receivedConnection)return;
   if(state==null||state.durationTicks()<=0||mc.level==null||mc.player==null||mc.getConnection()==null){clearNow();return;}
   if(level!=mc.level||connection!=mc.getConnection()||entityId!=state.entityId()||!state.chase())stopChase();
   level=mc.level;connection=mc.getConnection();remaining=Math.min(1200,state.durationTicks());
   entityId=state.entityId();fog=state.fog();chase=state.chase()&&entityId>=0;
  });
 }

 private static boolean active(){
  Minecraft mc=Minecraft.getInstance();
  return remaining>0&&mc.player!=null&&mc.level==level&&mc.getConnection()==connection&&connection!=null;
 }

 private static void tick(ClientTickEvent.Post event){
  if(!active()){clearNow();return;}
  if(--remaining==0){clearNow();return;}
  if(!chase)return;
  Entity entity=level.getEntity(entityId);
  // The server identifies its owned demon; reject unrelated local entity IDs.
  boolean demon=entity!=null&&net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals("verity:verity_demon");
  if(!demon||entity.isRemoved()){stopChase();return;}
  if(soundEntity!=null&&soundEntity!=entity)stopChase();
  if(chaseSound==null){
   var manager=Minecraft.getInstance().getSoundManager();
   if(manager.getSoundEvent(CHASE)==null)return;
   chaseSound=new SimpleSoundInstance(CHASE,SoundSource.HOSTILE,.45f,1f,SoundInstance.createUnseededRandom(),true,0,SoundInstance.Attenuation.NONE,0,0,0,true);
   soundEntity=entity;manager.play(chaseSound);
  }
 }

 private static void color(ViewportEvent.ComputeFogColor event){
  if(!active()||!fog)return;
  // Original night-blue formula, scoped to the receiving player's active window.
  float brightness=Mth.clamp(Mth.cos(level.getTimeOfDay((float)event.getPartialTick())*Mth.TWO_PI)*2f+.5f,0f,1f);
  event.setRed(.55f*brightness);event.setGreen(.75f*brightness);event.setBlue(brightness);
 }

 private static void fog(ViewportEvent.RenderFog event){
  if(!active()||!fog||event.getType()!=FogType.NONE)return;
  float far=event.getFarPlaneDistance();
  event.setNearPlaneDistance(far*.1f);event.setFarPlaneDistance(far*.75f);event.setCanceled(true);
 }

 private static void stopChase(){
  if(chaseSound!=null)Minecraft.getInstance().getSoundManager().stop(chaseSound);
  chaseSound=null;soundEntity=null;
 }

 private static void clearNow(){
  stopChase();remaining=0;entityId=-1;fog=false;chase=false;level=null;connection=null;
 }
}
