package dev.johnmart19.veritycompanion;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

/** Operator-only model management, off the main server tick thread. */
public final class LocalModelCommands {
 private static final ThreadPoolExecutor WORK=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"Verity local models");t.setDaemon(true);return t;});
 private static volatile List<String> models=List.of();
 private static volatile long refreshed;
 private static final java.util.concurrent.atomic.AtomicBoolean refreshing=new java.util.concurrent.atomic.AtomicBoolean();
 static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(com.mojang.brigadier.suggestion.SuggestionsBuilder builder){return CompletableFuture.completedFuture(RuntimeCapabilities.suggestions(builder,RuntimeCapabilities.get().models()));}
 private static com.mojang.brigadier.suggestion.Suggestions fill(com.mojang.brigadier.suggestion.SuggestionsBuilder b){for(String model:models)if(model.toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase()))b.suggest(model);return b.build();}
 private static List<String> inventory(JsonObject response){List<String> result=new ArrayList<>();for(JsonElement e:response.getAsJsonArray("models")){JsonObject m=e.getAsJsonObject();result.add(m.get("key").getAsString());if(m.has("variants")&&m.get("variants").isJsonArray())for(JsonElement v:m.getAsJsonArray("variants"))result.add(v.getAsString());}return List.copyOf(new LinkedHashSet<>(result));}
 static List<String> suggestions(){return models;}
 static String describe(ServerPlayer p,String action,JsonObject r){
  if(r.has("error"))return ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.setting_not_applied")+r.get("error").getAsString();
  if(r.has("addition"))return ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.temporary_addition_to_core_instructions_server_wide")+r.get("addition").getAsString();
  if(r.has("job_id"))return ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.loading_selected_model_with_new_context_ram")+r.get("job_id").getAsString();
  if(action.equals("system_prompt"))return ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.appended_to_verity_core_instructions_server_wide");
  if(action.equals("prompt_clear"))return ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.temporary_addition_cleared_core_verity_instructions_remain");
  if(action.equals("caps"))return r.toString();
  return ModTranslations.text(Relations.get(p).language,"verity_companion.llm.shared_status",r.get("provider"),r.get("model"),r.get("context"),r.get("overrides"),r.get("system_prompt_chars"),r.get("loading"),r.get("job"));
 }
 static int setting(ServerPlayer p,String action,JsonElement value){if(!p.hasPermissions(2))return 0;try{WORK.execute(()->{try{JsonObject body=new JsonObject();body.addProperty("action",action);if(value!=null)body.add("value",value);JsonObject response=post("settings",body);tell(p,describe(p,action,response));RuntimeCapabilities.refresh(p.getServer());}catch(Exception error){tell(p,"LLM: "+error.getMessage());}});return 1;}catch(RejectedExecutionException error){tell(p,ModTranslations.text(Relations.get(p).language,"verity_companion.llm.queue_busy"));return 0;}}

 static JsonObject post(String action,JsonObject body)throws Exception{return JsonParser.parseString(new String(ServerBridge.post("/lmstudio/"+action,body.toString().getBytes(StandardCharsets.UTF_8),"application/json"),StandardCharsets.UTF_8)).getAsJsonObject();}
 static void tell(ServerPlayer p,String message){Runnable delivery=()->{if(!p.hasDisconnected())p.sendSystemMessage(Component.literal(message));};if(p.getServer().isSameThread())delivery.run();else p.getServer().execute(delivery);}
 static int request(ServerPlayer p,String model,boolean cloud){if(!p.hasPermissions(2)){tell(p,ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.selecting_the_shared_model_requires_operator_permissions"));return 0;}tell(p,ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.checking_lm_studio"));try{WORK.execute(()->{try{
  if(cloud){post("cloud",new JsonObject());RuntimeCapabilities.refresh(p.getServer());tell(p,ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.replies_switched_to_google_tts_is_configured"));return;}
  if(model==null){JsonObject response=post("models",new JsonObject());List<String> found=new ArrayList<>();StringBuilder text=new StringBuilder(ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.downloaded_lm_studio_models_selection_is_server"));for(JsonElement element:response.getAsJsonArray("models")){JsonObject m=element.getAsJsonObject();String id=m.get("key").getAsString();found.add(id);text.append(id).append(" — ").append(m.get("display_name").isJsonNull()?id:m.get("display_name").getAsString()).append("; variants=").append(m.get("variants")).append("; caps=").append(m.get("capabilities")).append('\n');}models=inventory(response);refreshed=System.nanoTime();tell(p,text+(found.isEmpty()?ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.no_downloaded_text_models"):"/llm local <model>; /llm cloud"));return;}
  JsonObject body=new JsonObject();body.addProperty("model",model);JsonObject started=post("select",body);String job=started.get("job_id").getAsString();tell(p,ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.loading")+model+"; context="+started.get("context").getAsInt());
  for(int attempt=0;attempt<240;attempt++){JsonObject query=new JsonObject();query.addProperty("job_id",job);JsonObject status=post("status",query).getAsJsonObject("job");String state=status.has("state")?status.get("state").getAsString():"unknown";if(state.equals("ready")){RuntimeCapabilities.refresh(p.getServer());if(status.has("warning"))tell(p,ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.new_model_selected_but_the_previous_instance"));tell(p,ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.local_model_ready")+model+"; context="+status.get("context").getAsInt()+ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.replies_and_tools_use_lm_studio_speech"));return;}if(state.equals("failed"))throw new IllegalStateException(status.get("message").getAsString());Thread.sleep(1000);}
  tell(p,ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.loading_has_not_finished_check_llm_status"));
 }catch(Exception error){tell(p,ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.lm_studio")+error.getMessage());}});return 1;}catch(RejectedExecutionException error){tell(p,ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.several_model_requests_are_already_queued_please"));return 0;}}
 static int status(ServerPlayer p){if(!p.hasPermissions(2))return 0;try{WORK.execute(()->{try{tell(p,describe(p,"status",post("status",new JsonObject())));}catch(Exception error){tell(p,"LM Studio: "+error.getMessage());}});return 1;}catch(RejectedExecutionException error){tell(p,ModTranslations.text(Relations.get(p).language,"verity_companion.llm.studio_queue_busy"));return 0;}}
 static int tts(ServerPlayer p,boolean enabled){return tts(p,enabled,true);}
 static int tts(ServerPlayer p,boolean enabled,boolean notify){synchronized(Relations.class){Relations.get(p).tts=enabled;Relations.save(p);}net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new VoiceSettings(enabled));if(notify)tell(p,ModTranslations.text(Relations.get(p).language,"verity_companion.local_model_commands.your_tts")+ModTranslations.text(Relations.get(p).language,enabled?"verity_companion.common.on":"verity_companion.common.off"));return 1;}
}
