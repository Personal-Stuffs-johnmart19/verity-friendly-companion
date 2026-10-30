package dev.johnmart19.veritycompanion;

import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;

/** Original demon appearance, completely separate behavior. No original goals, attacks or tick logic. */
public final class RivalController {
 static net.minecraft.nbt.CompoundTag data(Entity e){return ((net.neoforged.neoforge.common.extensions.IEntityExtension)(Object)e).getPersistentData();}
 static final String OWNER="verity_companion_owner";
 static final Map<UUID,LivingEntity> ACTIVE=new HashMap<>();
 static final Map<UUID,ServerBossEvent> BARS=new HashMap<>();
 static final Map<UUID,Long> LAST_HIT=new HashMap<>();
 public static boolean owned(Entity entity){return data(entity).hasUUID(OWNER);}
 static ServerPlayer owner(Entity e){return e.level() instanceof ServerLevel l&&owned(e)?l.getServer().getPlayerList().getPlayer(data(e).getUUID(OWNER)):null;}
 public static void spawn(ServerPlayer p){
  if(!HorrorController.bossAllowed(p)||ACTIVE.containsKey(p.getUUID())||p.hasDisconnected()||p.isCreative()||p.isSpectator()||!p.isAlive())return;
  var type=BuiltInRegistries.ENTITY_TYPE.entrySet().stream().filter(e->e.getKey().location().getNamespace().equals("verity")&&e.getKey().location().getPath().contains("demon")).map(Map.Entry::getValue).findFirst().orElseThrow();
  Entity raw=type.create(p.serverLevel());if(!(raw instanceof PathfinderMob mob))return;
  boolean space=false;for(int r=4;r<=8&&!space;r++)for(int dx=-r;dx<=r&&!space;dx++)for(int dz=-r;dz<=r&&!space;dz++){if(Math.abs(dx)!=r&&Math.abs(dz)!=r)continue;var pos=p.blockPosition().offset(dx,0,dz);if(!p.serverLevel().hasChunkAt(pos))continue;mob.moveTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5,p.getYRot(),0);space=p.serverLevel().noCollision(mob)&&p.serverLevel().getBlockState(pos.below()).isSolid();}if(!space)return;
  data(mob).putUUID(OWNER,p.getUUID());mob.setPersistenceRequired();mob.setCustomName(Component.literal(Relations.name(p)+Relations.tr(p," — обиженный соперник"," — offended rival")));mob.setCustomNameVisible(true);
  if(mob.getAttribute(Attributes.MAX_HEALTH)!=null)mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);mob.setHealth(mob.getMaxHealth());mob.setTarget(null);
  HorrorController.pose(mob,1);if(!p.serverLevel().addFreshEntity(mob))return;ACTIVE.put(p.getUUID(),mob);
  ServerBossEvent bar=new ServerBossEvent(mob.getName(),BossEvent.BossBarColor.RED,BossEvent.BossBarOverlay.NOTCHED_10);bar.addPlayer(p);BARS.put(p.getUUID(),bar);
  p.sendSystemMessage(Component.literal(HorrorController.moodEncounter(p)?Relations.tr(p,"Настроение упало до порога боя. Этот соперник сражается только с тобой. После боя поговорим.","My mood reached the encounter threshold. This rival fights only you. We can talk after the fight."):Relations.tr(p,"Начался случайный бой с Verity. Этот соперник сражается только с тобой.","A random Verity encounter started. This rival fights only you.")));
 }
 /** Invoked after vanilla PathfinderMob.tick; original subclass tick never executes. */
 public static void tick(Entity entity){
  if(entity.level().isClientSide)return;if(HorrorController.isStalker(entity)){HorrorController.stalkerTick(entity);return;}if(!owned(entity)){entity.discard();return;}CompanionBrain.init();
  ServerPlayer p=owner(entity);if(!(entity instanceof PathfinderMob mob)||p==null||p.hasDisconnected()||!p.isAlive()||p.isCreative()||p.isSpectator()||p.level()!=entity.level()||!HorrorController.bossAllowed(p)){if(owned(entity))remove(data(entity).getUUID(OWNER));entity.discard();return;}
  UUID id=p.getUUID();if(ACTIVE.get(id)!=mob){entity.discard();return;}
  mob.setTarget(null);mob.getLookControl().setLookAt(p,30,30);mob.getNavigation().moveTo(p,1.15);
  // Vanilla Darkness is applied to the owner alone; no time, gamma or global fog changes.
  if(entity.tickCount%40==0)HorrorController.bossPresentation(p,entity);
  if(entity.tickCount%30==0&&mob.distanceToSqr(p)<9&&mob.hasLineOfSight(p)&&!p.isInvulnerable()){
   // Direct health subtraction is deliberate: armor, absorption and mod damage scaling do not change the percentage.
   float amount=p.getMaxHealth()*.25f;p.setHealth(Math.max(0,p.getHealth()-amount));p.hurtMarked=true;if(p.getHealth()<=0)p.die(p.damageSources().mobAttack(mob));
   p.connection.send(new net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket(p.getId(),0f));
   try{mob.getClass().getMethod("triggerAttack").invoke(mob);}catch(ReflectiveOperationException e){System.err.println("[Verity Companion] rival attack animation unavailable");}
  }
  ServerBossEvent bar=BARS.get(id);if(bar!=null)bar.setProgress(mob.getHealth()/mob.getMaxHealth());
  if(!HorrorController.bossAllowed(p))remove(id);
 }
 /** Replacement for the original demon hurt method. Other players/environment cannot hurt it. */
 public static boolean hurt(Entity entity,DamageSource source,float ignored){
  if(entity.level().isClientSide)return false;ServerPlayer p=owner(entity);if(!(entity instanceof LivingEntity mob)||p==null||source.getEntity()!=p||Float.isNaN(ignored)||ignored<=0)return false;
  if(ACTIVE.get(p.getUUID())!=entity)return false;long now=entity.level().getGameTime();long last=LAST_HIT.getOrDefault(p.getUUID(),Long.MIN_VALUE/2);if(now-last<10)return false;LAST_HIT.put(p.getUUID(),now);
  mob.setHealth(Math.max(0,mob.getHealth()-mob.getMaxHealth()*.125f));mob.hurtTime=10;mob.hurtMarked=true;
  if(mob.getHealth()<=0){boolean offended=Relations.get(p).state.reconciliation;if(offended)Relations.defeated(p);remove(p.getUUID());p.sendSystemMessage(Component.literal(offended?Relations.tr(p,"Ты победил. Я немного успокоился, но помощь вернётся после примирения и извинений.","You won. I have calmed down a little, but we need a conversation and an apology before I help again."):Relations.tr(p,"Ты победил в случайном бою. Хорошая схватка!","You won the random encounter. Good fight!")));}return true;
 }
 static void remove(UUID id){LivingEntity entity=ACTIVE.remove(id);if(entity!=null)entity.discard();ServerBossEvent bar=BARS.remove(id);if(bar!=null)bar.removeAllPlayers();LAST_HIT.remove(id);}
 static void clear(){for(UUID id:new ArrayList<>(ACTIVE.keySet()))remove(id);}
}
