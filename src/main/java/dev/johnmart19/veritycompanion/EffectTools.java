package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.*;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Requesting player only. All entrypoints run on the logical server thread. */
public final class EffectTools {
 private EffectTools() {}
 private static final EffectJournal<CompoundTag> JOURNAL=new EffectJournal<>();
 public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event){JOURNAL.clear(event.getEntity().getUUID());}
 public static void clear(){JOURNAL.clear();}
 private static String string(JsonObject args,String key,String fallback){return args.has(key)?args.get(key).getAsString():fallback;}
 private static int number(JsonObject args,String key,int fallback){return args.has(key)?args.get(key).getAsInt():fallback;}
 private static CompoundTag snapshot(MobEffectInstance effect){return effect==null?null:((CompoundTag)effect.save()).copy();}
 private static JsonObject activeRow(MobEffectInstance effect){
  var out=new JsonObject();out.addProperty("id",BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString());
  out.addProperty("name",effect.getEffect().value().getDisplayName().getString());out.addProperty("category",effect.getEffect().value().getCategory().name().toLowerCase(Locale.ROOT));
  out.addProperty("name_ru",EffectPolicy.nameRu(out.get("id").getAsString(),out.get("name").getAsString()));
  out.addProperty("amplifier",effect.getAmplifier());out.addProperty("level",effect.getAmplifier()+1);out.addProperty("duration_ticks",effect.getDuration());out.addProperty("infinite",effect.isInfiniteDuration());return out;
 }
 private static JsonObject row(MobEffectInstance effect){
  var out=activeRow(effect);var hidden=new JsonArray();CompoundTag cursor=snapshot(effect);
  for(int i=0;i<32&&cursor.contains("hidden_effect",10);i++){
   cursor=cursor.getCompound("hidden_effect");var instance=MobEffectInstance.load(cursor);if(instance==null)break;
   hidden.add(activeRow(instance));
  }
  out.add("hidden_effects",hidden);out.addProperty("hidden_truncated",cursor.contains("hidden_effect",10));return out;
 }
 public static String status(ServerPlayer player,JsonObject args){
  var out=new JsonObject();out.addProperty("source","live_requesting_player");var effects=new JsonArray();
  String id=string(args,"id","");for(var effect:player.getActiveEffects())if(id.isEmpty()||BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString().equals(id))effects.add(row(effect));
  out.add("effects",effects);return out.toString();
 }
 public static String apply(ServerPlayer player,JsonObject args,String original){
  original=EffectIntent.effective(player.getUUID(),ToolIntent.unaddress(original,Relations.name(player)),System.currentTimeMillis());
  String id=string(args,"id","");var location=ResourceLocation.parse(id);
  var holder=BuiltInRegistries.MOB_EFFECT.getHolder(location).orElseThrow(()->new IllegalArgumentException("Unknown registered effect: "+id));
  EffectPolicy.validate(original,location.toString(),holder.value().getDisplayName().getString(),holder.value().getCategory()==MobEffectCategory.BENEFICIAL);
  int ticks=EffectPolicy.durationTicks(original,number(args,"duration_seconds",60),args.has("infinite")&&args.get("infinite").getAsBoolean());
  int amplifier=EffectPolicy.amplifier(original,number(args,"amplifier",0));
  CompoundTag before=snapshot(player.getEffect(holder));
  boolean activeUpdated=player.addEffect(new MobEffectInstance(holder,ticks,amplifier),player);
  MobEffectInstance actual=player.getEffect(holder);CompoundTag after=snapshot(actual);
  boolean changed=!Objects.equals(before,after),record=EffectJournal.shouldRecord(before,after);
  var out=new JsonObject();out.addProperty("applied",changed);out.addProperty("changed",changed);out.addProperty("active_updated",activeUpdated);out.addProperty("id",location.toString());
  out.addProperty("requested_duration_seconds",ticks==-1?-1:ticks/20);out.addProperty("requested_infinite",ticks==-1);out.addProperty("requested_amplifier",amplifier);
  // Instant effects may already be consumed; never invent an active effect or undo entry.
  if(actual!=null)out.add("actual",row(actual));
  if(record)JOURNAL.record(player.getUUID(),new EffectJournal.Entry<>(location.toString(),before,after,actual,player.serverLevel().getGameTime()));
  out.addProperty("undo_recorded",record);return out.toString();
 }
 /** Changes only duration on copies of the current active stacks; amplifier and hidden chains stay intact. */
 public static String makeCurrentInfinite(ServerPlayer player,String original){
  original=ToolIntent.unaddress(original,Relations.name(player));
  if(!EffectPolicy.makeCurrentInfinite(original))throw new IllegalArgumentException("Explicitly request making your current active effects permanent");
  var results=new JsonArray();int changedCount=0;
  for(var existing:new ArrayList<>(player.getActiveEffects())){
   var holder=existing.getEffect();String id=BuiltInRegistries.MOB_EFFECT.getKey(holder.value()).toString();
   CompoundTag before=snapshot(existing),permanent=before.copy();permanent.putInt("duration",-1);
   var replacement=MobEffectInstance.load(permanent);var result=new JsonObject();result.addProperty("id",id);
   if(replacement==null)throw new IllegalStateException("Could not clone current registered effect: "+id);
   // Preserve the owned instance on a no-op, so repeating this request cannot invalidate prior undo.
   if(!existing.isInfiniteDuration())player.forceAddEffect(replacement,player);
   var actual=player.getEffect(holder);CompoundTag after=snapshot(actual);boolean changed=!Objects.equals(before,after);
   boolean record=EffectJournal.shouldRecord(before,after);if(record)JOURNAL.record(player.getUUID(),new EffectJournal.Entry<>(id,before,after,actual,player.serverLevel().getGameTime()));
   if(changed)changedCount++;result.addProperty("changed",changed);result.addProperty("applied",changed);result.addProperty("undo_recorded",record);
   if(actual!=null)result.add("actual",row(actual));results.add(result);
  }
  var out=new JsonObject();out.addProperty("changed_count",changedCount);out.addProperty("source","live_requesting_player_current_effects");out.add("effects",results);return out.toString();
 }
 /** Age saved duration fields, including hidden effects, without touching the live world. */
 private static CompoundTag aged(CompoundTag saved,long elapsed){
  if(saved==null)return null;var copy=saved.copy();
  if(copy.contains("duration")){int duration=copy.getInt("duration");if(duration!=-1)copy.putInt("duration",(int)Math.max(0,duration-elapsed));}
  if(copy.contains("hidden_effect",10))copy.put("hidden_effect",aged(copy.getCompound("hidden_effect"),elapsed));return copy;
 }
 private static MobEffectInstance survivingPrior(CompoundTag saved){
  if(saved==null)return null;
  var prior=MobEffectInstance.load(saved);
  if(prior!=null&&(prior.isInfiniteDuration()||prior.getDuration()>0))return prior;
  return saved.contains("hidden_effect",10)?survivingPrior(saved.getCompound("hidden_effect")):null;
 }
 public static String remove(ServerPlayer player,JsonObject args,String original){
  boolean clearAll=args.has("clear_all")&&args.get("clear_all").getAsBoolean();
  EffectPolicy.validateRemoval(original,clearAll);
  if(clearAll){
   int before=player.getActiveEffects().size();boolean changed=player.removeAllEffects();JOURNAL.clear(player.getUUID());
   var out=new JsonObject();out.addProperty("cleared_all",changed);out.addProperty("previous_count",before);out.addProperty("remaining_count",player.getActiveEffects().size());return out.toString();
  }
  String id=string(args,"id","last");
  if(!id.isEmpty()&&!id.equals("last")){
   var selective=BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(id)).orElseThrow(()->new IllegalArgumentException("Unknown registered effect: "+id));
   if(EffectPolicy.authorizeSelectiveId(original,id,selective.value().getDisplayName().getString())){
    boolean removed=player.removeEffect(selective);
    if(removed){EffectJournal.Entry<CompoundTag> consumed;while((consumed=JOURNAL.find(player.getUUID(),id))!=null)JOURNAL.consume(player.getUUID(),consumed);}
    var out=new JsonObject();out.addProperty("removed",removed);out.addProperty("id",id);out.addProperty("mode","explicit_named_current_effect");
    out.addProperty("prior_effect_restored",false);if(player.getEffect(selective)!=null)out.add("actual",row(player.getEffect(selective)));return out.toString();
   }
  }
  var entry=JOURNAL.find(player.getUUID(),id);
  if(entry==null){
   if(!id.isEmpty()&&!id.equals("last"))throw new IllegalArgumentException("Removing an untracked current effect requires the human to explicitly name that effect");
   return "{\"removed\":false,\"reason\":\"no_matching_verity_effect_in_journal\"}";
  }
  var holder=BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(entry.id())).orElseThrow();var current=player.getEffect(holder);
  Map<String,String> registeredNames=new HashMap<>();
  for(var effect:BuiltInRegistries.MOB_EFFECT)registeredNames.put(BuiltInRegistries.MOB_EFFECT.getKey(effect).toString(),effect.getDisplayName().getString());
  EffectPolicy.validateTrackedRemoval(original,entry.id(),holder.value().getDisplayName().getString(),entry==JOURNAL.find(player.getUUID(),"last"),registeredNames);
  long elapsed=Math.max(0,player.serverLevel().getGameTime()-entry.tick());
  CompoundTag expected=aged(entry.after(),elapsed);
  if(!EffectJournal.owns(entry.identity(),current,expected,snapshot(current))){
   JOURNAL.consume(player.getUUID(),entry);return "{\"removed\":false,\"reason\":\"effect_expired_or_changed_since_verity_applied_it\"}";
  }
  boolean removed=player.removeEffect(holder);boolean restored=false;
  if(removed && entry.before()!=null){
   var prior=survivingPrior(aged(entry.before(),elapsed));
   if(prior!=null)restored=player.addEffect(prior,player);
  }
  JOURNAL.consume(player.getUUID(),entry);var out=new JsonObject();out.addProperty("removed",removed);out.addProperty("id",entry.id());out.addProperty("prior_effect_restored",restored);
  if(player.getEffect(holder)!=null)out.add("actual",row(player.getEffect(holder)));return out.toString();
 }
}
