package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.util.*;
import java.util.regex.*;
import net.minecraft.server.level.ServerPlayer;

/** Narrow human-request paths: reliable ordinary actions do not need a model to restate consent. */
final class DirectRequests {
 record Answer(String reply,String emotion){}
 static Answer execute(ServerPlayer p,String original)throws Exception{return execute(p,original,"");}
 static Answer execute(ServerPlayer p,String original,String previousAssistant)throws Exception{
  var server=p.getServer();String human=ToolIntent.unaddress(original,Relations.name(p));
  var personal=CompanionChatActions.execute(p,original);if(personal!=null)return personal;
  if(BuildLocationQuestion.matches(human,previousAssistant)){
   var facts=BuildJournalSummary.read(CompanionBrain.journal(p.serverLevel(),p.getUUID()));
   if(facts.verified()&&facts.appliedBlocks()>0)return new Answer(ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.the_last_build_journal_records_coordinates")+facts.bounds()+" ("+facts.dimension()+"). "+ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.recorded_block_changes")+facts.appliedBlocks()+".","neutral");
  }
  var face=EmotionPolicy.requestedFace(human);
  if(face.isPresent())return new Answer(CompanionBrain.playerMain(server,p,()->ExpressionCommands.set(p,face.get())),"happy");
  if(BuildLanguage.isUndo(human)){
   RequestProgress.tool(p,"undo_build");var job=CompanionBrain.playerMain(server,p,()->CompanionBrain.undo(p));String outcome=CompanionBrain.awaitJob(job);
   if(!outcome.startsWith("Undo completed"))return new Answer(ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.undo_did_not_complete")+outcome,"serious");
   return new Answer(ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.undid_recorded_building_changes")+job.placed+ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.blocks_changed_or_protected_cells_preserved")+job.skipped+".","neutral");
  }
  String deathQuestion=ToolIntent.normalized(human).replaceAll("[.!?]+$","").trim();
  if(Set.of("что произошло","что случилось","почему я умер","почему я умерла","what happened","why did i die").contains(deathQuestion)){
   JsonObject death=JsonParser.parseString(CompanionBrain.playerMain(server,p,()->DeathEvents.recent(p))).getAsJsonObject();
   if(death.has("status")&&death.get("status").getAsString().equals("confirmed_recent_death")){
    String cause=death.get("damage_type").getAsString();String explanation=cause.equals("minecraft:fall")?ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.you_died_from_fall_damage"):ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.recorded_fatal_damage")+cause+".";
    String position=String.format(Locale.ROOT,"%.1f, %.1f, %.1f",death.get("death_x").getAsDouble(),death.get("death_y").getAsDouble(),death.get("death_z").getAsDouble());
    return new Answer(explanation+ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.recorded_death_location")+position+" ("+death.get("death_dimension").getAsString()+"), "+death.get("age_seconds").getAsLong()+ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.s_ago"),"serious");
   }
  }
  if(EffectPolicy.removeIntent(human)&&!EffectPolicy.all(human)){
   var removeIds=EffectPolicy.namedIds(human);
   if(!removeIds.isEmpty()){
    List<String> outcomes=new ArrayList<>();RequestProgress.tool(p,"remove_effect");
    for(String id:removeIds){JsonObject args=new JsonObject();args.addProperty("id",id);
     JsonObject result=JsonParser.parseString(CompanionBrain.playerMain(server,p,()->EffectTools.remove(p,args,human))).getAsJsonObject();
     String label=effectName(p,id);boolean removed=result.has("removed")&&result.get("removed").getAsBoolean();
     if(result.has("actual")){JsonObject active=result.getAsJsonObject("actual");String duration=active.get("infinite").getAsBoolean()?ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.permanent"):Math.max(0,(active.get("duration_ticks").getAsInt()+19)/20)+ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.s");
      boolean restored=result.has("prior_effect_restored")&&result.get("prior_effect_restored").getAsBoolean();
      outcomes.add(label+(removed&&restored?ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.verity_s_version_removed_currently"):ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.currently"))+active.get("level").getAsInt()+" — "+duration);
     }else outcomes.add(label+(removed?ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.removed"):ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.unchanged")));
    }
    return new Answer(String.join("; ",outcomes)+".","neutral");
   }
  }
  if(!Relations.helps(p))return null;
  if(EffectPolicy.makeCurrentInfinite(human)){
   RequestProgress.tool(p,"apply_effect");JsonObject result=JsonParser.parseString(CompanionBrain.playerMain(server,p,()->EffectTools.makeCurrentInfinite(p,human))).getAsJsonObject();
   List<String> actual=new ArrayList<>();for(JsonElement element:result.getAsJsonArray("effects")){
    JsonObject entry=element.getAsJsonObject();if(!entry.has("actual"))continue;JsonObject active=entry.getAsJsonObject("actual");
    String duration=active.get("infinite").getAsBoolean()?ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.permanent"):Math.max(0,(active.get("duration_ticks").getAsInt()+19)/20)+ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.s");
    String label=Relations.replyLanguage(p).equals("ru")?active.get("name_ru").getAsString():active.get("name").getAsString();
    actual.add(label+" "+active.get("level").getAsInt()+" — "+duration);
   }
   return new Answer(actual.isEmpty()?ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.you_have_no_active_effects"):ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.your_current_effects")+String.join("; ",actual)+".","happy");
  }
  if(EnchantmentIntent.clearAll(human))return new Answer(CompanionBrain.playerMain(server,p,()->EnchantmentTools.clear(p,human)),"neutral");
  var unbreakable=HeldItemPolicy.requestedUnbreakable(human);
  if(unbreakable.isPresent()){JsonObject args=new JsonObject();args.addProperty("enabled",unbreakable.get());return new Answer(CompanionBrain.playerMain(server,p,()->HeldItemTools.setUnbreakable(p,args,human)),"happy");}
  if(CombatPolicy.allowed(human)){
   RequestProgress.tool(p,"clear_nearby_mobs");JsonObject result=JsonParser.parseString(CompanionBrain.playerMain(server,p,()->CombatTools.clear(p,new JsonObject(),human))).getAsJsonObject();
   return new Answer(ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.dangerous_mobs_killed")+result.get("confirmed_killed").getAsInt()+ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.within")+result.get("actual_radius").getAsInt()+ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.blocks_villagers_pets_and_players_were_preserved")+(result.get("failed_count").getAsInt()>0?ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.some_targets_survived_or_were_protected"):""),"serious");
  }
  long now=System.currentTimeMillis();var effects=EffectIntent.requestedEffects(p.getUUID(),human,now);
  String effective=EffectIntent.effective(p.getUUID(),human,now);
  // Explicit numeric duration/strength needs the complete tool argument parser.
  if(!effects.isEmpty()&&!effective.matches("(?s).*\\d.*")&&(EffectPolicy.amplifier(effective,255)==0||EffectPolicy.maximumRequested(effective))){
   List<String> actual=new ArrayList<>();boolean queuedPermanent=false;
   for(String id:effects){JsonObject args=new JsonObject();args.addProperty("id",id);if(EffectPolicy.maximumRequested(effective))args.addProperty("amplifier",255);
    JsonObject result=JsonParser.parseString(CompanionBrain.playerMain(server,p,()->EffectTools.apply(p,args,human))).getAsJsonObject();
    if(result.has("actual")){JsonObject active=result.getAsJsonObject("actual");boolean infinite=active.get("infinite").getAsBoolean();String duration=infinite?ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.permanent"):Math.max(0,(active.get("duration_ticks").getAsInt()+19)/20)+ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.s");actual.add(effectName(p,id)+" "+active.get("level").getAsInt()+" — "+duration);
     if(EffectPolicy.infiniteRequested(effective)&&!infinite)for(JsonElement hidden:active.getAsJsonArray("hidden_effects"))if(hidden.getAsJsonObject().get("infinite").getAsBoolean())queuedPermanent=true;
    }
   }
   EffectIntent.clear(p.getUUID());return new Answer(ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.your_current_effects")+String.join("; ",actual)+"."+(queuedPermanent?ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.the_permanent_version_is_queued_under_a"):""),"happy");
  }

  var noun=ItemRequestPolicy.plainNoun(human);
  var item=noun.flatMap(ServerItemCatalog::resolveUnique);
  if(item.isPresent()){
   var match=item.get();JsonObject args=new JsonObject();args.addProperty("id",match.id());args.addProperty("count",1);
   CompanionBrain.playerMain(server,p,()->PackTools.give(p,args,human));
   String label=Relations.replyLanguage(p).equals("ru")&&!match.russian().isBlank()?match.russian():match.english();
   return new Answer(ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.here_you_go")+label+" ×1.","happy");
  }
  return simpleBuild(p,human);
 }
 private static String effectName(ServerPlayer p,String id){if(Relations.replyLanguage(p).equals("ru"))return switch(id){case "minecraft:saturation"->"насыщение";case "minecraft:regeneration"->"регенерация";case "minecraft:night_vision"->"ночное зрение";case "minecraft:strength"->"сила";case "minecraft:speed"->"скорость";default->id;};return id.substring(id.indexOf(':')+1).replace('_',' ');}
 private static Answer simpleBuild(ServerPlayer p,String human)throws Exception{
  String s=BuildLanguage.normalized(human);boolean editing=BuildLanguage.isModification(s);
  if(editing||!QuickBuildPolicy.simple(s))return null;
  if(!BuildLanguage.isBuild(s)&&!editing)return null;
  if(!BuildConversation.allowsBuild(p.getUUID(),human,System.currentTimeMillis()))return null;
  if(editing&&s.matches("(?:добавь|измени|замени|add|change|replace) .+"))return null;
  if(s.length()>180||s.matches("(?s).*\\d.*")||s.matches("(?s).*(?:огром|гигант|большой|большую|размер|huge|giant|large|size|schematic|схем|http|(?<!\\p{L})(?:и|and|then|затем|потом)(?!\\p{L})).*"))return null;
  boolean create=s.contains("create");if(!create&&s.matches("(?s).*(?:из |of |from ).*"))return null;
  String kind=create?"create_workshop":"";
  if(kind.isEmpty()&&(s.contains("дом")||s.contains("house")||s.contains("home")||s.contains("cottage")||editing))kind="house";
  if(kind.isEmpty())return null;
  JsonObject args=new JsonObject();args.addProperty("kind",kind);String ready=SchematicLibrary.preferred();String buildTool="build_structure";
  if(!editing&&!ready.isEmpty()){var nativePlan=CompanionBrain.playerMain(p.getServer(),p,()->SchematicLibrary.load(p,ready));args.addProperty("file",ready);args.addProperty("width",nativePlan.width());args.addProperty("length",nativePlan.length());args.addProperty("height",nativePlan.height());if(create)args.addProperty("style","create");buildTool="build_schematic";}
if(!editing&&!args.has("file")){args.addProperty("width",create?9:7);args.addProperty("length",create?11:9);args.addProperty("height",7);}
  if(!editing){String survey=CompanionBrain.playerMain(p.getServer(),p,()->BuildSite.survey(p,args));Matcher site=Pattern.compile("^Candidate build origin relative to player: x=(-?\\d+), y=(-?\\d+), z=(-?\\d+);").matcher(survey);
   if(!site.find())return new Answer(ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.no_suitable_nearby_loaded_site_was_found"),"neutral");
   args.addProperty("x",Integer.parseInt(site.group(1)));args.addProperty("y",Integer.parseInt(site.group(2)));args.addProperty("z",Integer.parseInt(site.group(3)));
  }
  args.addProperty("coordinate_mode","relative");RequestProgress.tool(p,"build_structure");String result=CompanionBrain.tool(p,buildTool,args,human);
  return new Answer(BuildOutcome.parse(result).map(outcome->outcome.reply(Relations.replyLanguage(p).equals("ru"))).orElse(ModTranslations.text(Relations.replyLanguage(p),"verity_companion.direct_requests.the_server_did_not_confirm_construction")),"neutral");
 }
}
