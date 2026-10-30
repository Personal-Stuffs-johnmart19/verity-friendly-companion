package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.neoforge.common.CommonHooks;

/** Bounded operator-authorized combat; never raw model commands/selectors or console permission. */
public final class CombatTools {
 private CombatTools() {}
 public static String clear(ServerPlayer player,JsonObject args,String original){
  if(player.getServer()==null||!player.getServer().isSameThread())throw new IllegalStateException("Combat actions require the server thread");
  if(!player.createCommandSourceStack().hasPermission(2))throw new IllegalArgumentException("Requires the requesting player's actual permission level 2; no permission escalation");
  if(!Relations.helps(player))throw new IllegalStateException("Assistance paused for this player");
  String human=ToolIntent.unaddress(original,Relations.name(player));
  if(!CombatPolicy.allowed(human))throw new IllegalArgumentException("Explicitly ask to kill nearby mobs/hostiles in the original message; reports of attacks, questions, negation and quoted commands do not authorize killing");
  for(String key:args.keySet())if(!key.equals("radius"))throw new IllegalArgumentException("Only radius is accepted; entity IDs, selectors and other-player targets are not accepted");
  int requested=CombatPolicy.MAX_RADIUS;try{if(args.has("radius"))requested=args.get("radius").getAsInt();}catch(RuntimeException invalid){throw new IllegalArgumentException("radius must be an integer");}
  int radius=CombatPolicy.radius(human,requested);List<Mob> candidates=new ArrayList<>();
  player.serverLevel().getEntities(EntityTypeTest.forClass(Mob.class),player.getBoundingBox().inflate(radius),e->e.isAlive()&&e.distanceToSqr(player)<=radius*radius,candidates,257);
  boolean capped=candidates.size()>256;if(capped)candidates.removeLast();candidates.sort(Comparator.comparingDouble(e->e.distanceToSqr(player)));
  JsonArray killed=new JsonArray(),failed=new JsonArray(),skipped=new JsonArray();int attempts=0,skippedCount=0;
  for(Mob mob:candidates){
   String type=BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();boolean recent=ThreatEvents.recentlyDamaged(player,mob.getUUID());
   boolean owned=(mob instanceof OwnableEntity entity&&entity.getOwnerUUID()!=null)||(mob instanceof TamableAnimal tame&&tame.isTame());
   String reason=null;
   if(!CombatPolicy.eligible(false,owned,mob instanceof AbstractVillager,mob instanceof Enemy,recent))reason="protected_nonhostile_or_owned_or_villager";
   else if(!CombatPolicy.matchesType(human,type))reason="outside_human_requested_species";
   else if(attempts>=CombatPolicy.MAX_TARGETS)reason="per_call_attempt_cap";
   if(reason!=null){skippedCount++;if(skipped.size()<32){JsonObject row=row(mob,type);row.addProperty("reason",reason);skipped.add(row);}continue;}
   attempts++;JsonObject row=row(mob,type);row.addProperty("recent_actual_attacker",recent);row.addProperty("targeting_player_at_attempt",mob.getTarget()==player);
   if(!mob.isAlive()||mob.isRemoved()||mob.level()!=player.level()||mob.distanceToSqr(player)>radius*radius){row.addProperty("reason","target_changed_before_attempt");failed.add(row);continue;}
   try{
    if(!CommonHooks.onPlayerAttackTarget(player,mob)){row.addProperty("reason","player_attack_hook_denied");failed.add(row);continue;}
    boolean accepted=mob.hurt(player.damageSources().playerAttack(player),Float.MAX_VALUE);row.addProperty("damage_accepted",accepted);
   }catch(RuntimeException error){row.addProperty("adapter_error",error.getClass().getSimpleName());}
   row.addProperty("health_after",mob.getHealth());
   if(!mob.isAlive()||mob.isDeadOrDying()){row.addProperty("outcome","confirmed_dead");killed.add(row);}else{row.addProperty("reason","survived_or_damage_blocked");failed.add(row);}
  }
  JsonObject out=new JsonObject();out.addProperty("scope","loaded hostile mobs and actual recent attackers only; players, owned/tamed entities and villagers are always excluded");out.addProperty("dimension",player.serverLevel().dimension().location().toString());out.addProperty("actual_radius",radius);out.addProperty("snapshot_at_epoch_millis",System.currentTimeMillis());out.addProperty("candidate_cap",256);out.addProperty("candidate_cap_reached",capped);out.addProperty("attempt_cap",CombatPolicy.MAX_TARGETS);out.addProperty("attempts",attempts);out.addProperty("confirmed_killed",killed.size());out.add("killed",killed);out.add("failed",failed);out.add("skipped_sample",skipped);
  out.addProperty("skipped_count",skippedCount);out.addProperty("failed_count",failed.size());out.addProperty("candidate_count",candidates.size());
  out.addProperty("limits","Report only confirmed_killed as killed. Failed targets may be protected or invulnerable; do not promise safety or that every mob was removed. Nonhostile innocents were preserved. Only already loaded entities in the reported radius and current dimension were considered. Standard player attack and damage hooks apply; no global claim-protection guarantee is inferred.");return out.toString();
 }
 private static JsonObject row(Mob mob,String type){JsonObject row=new JsonObject();row.addProperty("entity_uuid",mob.getUUID().toString());row.addProperty("entity_id",mob.getId());row.addProperty("type",type);row.addProperty("name_untrusted_data",RecentWorldEventLog.safeText(mob.getName().getString(),100));row.addProperty("position",mob.blockPosition().toShortString());return row;}
}
