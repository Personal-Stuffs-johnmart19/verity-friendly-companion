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
 static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(com.mojang.brigadier.suggestion.SuggestionsBuilder builder){
  if(System.nanoTime()-refreshed>TimeUnit.SECONDS.toNanos(5)&&refreshing.compareAndSet(false,true)){
   try{return CompletableFuture.supplyAsync(()->{try{models=inventory(post("models",new JsonObject()));refreshed=System.nanoTime();}catch(Exception ignored){}finally{refreshing.set(false);}return fill(builder);},WORK);}catch(RejectedExecutionException error){refreshing.set(false);}
  }
  return CompletableFuture.completedFuture(fill(builder));
 }
 private static com.mojang.brigadier.suggestion.Suggestions fill(com.mojang.brigadier.suggestion.SuggestionsBuilder b){for(String model:models)if(model.toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase()))b.suggest(model);return b.build();}
 private static List<String> inventory(JsonObject response){List<String> result=new ArrayList<>();for(JsonElement e:response.getAsJsonArray("models")){JsonObject m=e.getAsJsonObject();result.add(m.get("key").getAsString());if(m.has("variants")&&m.get("variants").isJsonArray())for(JsonElement v:m.getAsJsonArray("variants"))result.add(v.getAsString());}return List.copyOf(new LinkedHashSet<>(result));}
 static List<String> suggestions(){return models;}
 static String describe(ServerPlayer p,String action,JsonObject r){
  if(r.has("error"))return Relations.tr(p,"Настройка не применена: ","Setting not applied: ")+r.get("error").getAsString();
  if(r.has("addition"))return Relations.tr(p,"Временное дополнение к основным инструкциям (общее для сервера):\n","Temporary addition to core instructions (server-wide):\n")+r.get("addition").getAsString();
  if(r.has("job_id"))return Relations.tr(p,"Загружаю выбранную модель с новым контекстом; расход RAM/VRAM может вырасти. Предыдущие настройки сохраняются до успеха. /llm status. Задача: ","Loading selected model with new context; RAM/VRAM use may increase. Previous settings remain until success. /llm status. Job: ")+r.get("job_id").getAsString();
  if(action.equals("system_prompt"))return Relations.tr(p,"Дополнение добавлено к основным инструкциям Verity, общее для сервера до очистки или перезапуска моста.","Appended to Verity core instructions, server-wide until clear or bridge restart.");
  if(action.equals("prompt_clear"))return Relations.tr(p,"Временное дополнение очищено. Основные инструкции Verity сохранены.","Temporary addition cleared. Core Verity instructions remain active.");
  if(action.equals("caps"))return r.toString();
  return Relations.tr(p,"LLM (общие настройки): ","LLM (shared settings): ")+r.get("provider")+"; model="+r.get("model")+"; context="+r.get("context")+"; overrides="+r.get("overrides")+"; prompt chars="+r.get("system_prompt_chars")+"; loading="+r.get("loading")+"; job="+r.get("job");
 }
 static int setting(ServerPlayer p,String action,JsonElement value){if(!p.hasPermissions(2))return 0;try{WORK.execute(()->{try{JsonObject body=new JsonObject();body.addProperty("action",action);if(value!=null)body.add("value",value);JsonObject response=post("settings",body);tell(p,describe(p,action,response));}catch(Exception error){tell(p,"LLM: "+error.getMessage());}});return 1;}catch(RejectedExecutionException error){tell(p,"LLM queue busy");return 0;}}

 static JsonObject post(String action,JsonObject body)throws Exception{return JsonParser.parseString(new String(ServerBridge.post("/lmstudio/"+action,body.toString().getBytes(StandardCharsets.UTF_8),"application/json"),StandardCharsets.UTF_8)).getAsJsonObject();}
 static void tell(ServerPlayer p,String message){Runnable delivery=()->{if(!p.hasDisconnected())p.sendSystemMessage(Component.literal(message));};if(p.getServer().isSameThread())delivery.run();else p.getServer().execute(delivery);}
 static int request(ServerPlayer p,String model,boolean cloud){if(!p.hasPermissions(2)){tell(p,Relations.tr(p,"Выбор общей модели доступен оператору.","Selecting the shared model requires operator permissions."));return 0;}tell(p,Relations.tr(p,"Проверяю LM Studio…","Checking LM Studio…"));try{WORK.execute(()->{try{
  if(cloud){post("cloud",new JsonObject());tell(p,Relations.tr(p,"Ответы переключены на Google. Озвучка настраивается отдельно.","Replies switched to Google. TTS is configured separately."));return;}
  if(model==null){JsonObject response=post("models",new JsonObject());List<String> found=new ArrayList<>();StringBuilder text=new StringBuilder(Relations.tr(p,"Скачанные модели LM Studio (выбор общий для сервера):\n","Downloaded LM Studio models (selection is server-wide):\n"));for(JsonElement element:response.getAsJsonArray("models")){JsonObject m=element.getAsJsonObject();String id=m.get("key").getAsString();found.add(id);text.append(id).append(" — ").append(m.get("display_name").isJsonNull()?id:m.get("display_name").getAsString()).append("; variants=").append(m.get("variants")).append("; caps=").append(m.get("capabilities")).append('\n');}models=inventory(response);refreshed=System.nanoTime();tell(p,text+(found.isEmpty()?Relations.tr(p,"Нет скачанных текстовых моделей.","No downloaded text models."):"/llm local <model>; /llm cloud"));return;}
  JsonObject body=new JsonObject();body.addProperty("model",model);JsonObject started=post("select",body);String job=started.get("job_id").getAsString();tell(p,Relations.tr(p,"Загружаю ","Loading ")+model+"; context="+started.get("context").getAsInt());
  for(int attempt=0;attempt<240;attempt++){JsonObject query=new JsonObject();query.addProperty("job_id",job);JsonObject status=post("status",query).getAsJsonObject("job");String state=status.has("state")?status.get("state").getAsString():"unknown";if(state.equals("ready")){if(status.has("warning"))tell(p,Relations.tr(p,"Новая модель выбрана, но старая не выгрузилась. Проверь Loaded Models в LM Studio.","New model selected, but the previous instance did not unload. Check LM Studio Loaded Models."));tell(p,Relations.tr(p,"Локальная модель готова: ","Local model ready: ")+model+"; context="+status.get("context").getAsInt()+Relations.tr(p,". Ответы и инструменты — через LM Studio. Озвучка выбирается отдельно: /tts.",". Replies and tools use LM Studio. Speech is selected separately: /tts."));return;}if(state.equals("failed"))throw new IllegalStateException(status.get("message").getAsString());Thread.sleep(1000);}
  tell(p,Relations.tr(p,"Загрузка ещё не завершена. Проверь /llm status.","Loading has not finished. Check /llm status."));
 }catch(Exception error){tell(p,Relations.tr(p,"LM Studio: ","LM Studio: ")+error.getMessage());}});return 1;}catch(RejectedExecutionException error){tell(p,Relations.tr(p,"Уже выполняется несколько запросов моделей. Подожди.","Several model requests are already queued. Please wait."));return 0;}}
 static int status(ServerPlayer p){if(!p.hasPermissions(2))return 0;try{WORK.execute(()->{try{tell(p,describe(p,"status",post("status",new JsonObject())));}catch(Exception error){tell(p,"LM Studio: "+error.getMessage());}});return 1;}catch(RejectedExecutionException error){tell(p,"LM Studio queue busy");return 0;}}
 static int tts(ServerPlayer p,boolean enabled){return tts(p,enabled,true);}
 static int tts(ServerPlayer p,boolean enabled,boolean notify){synchronized(Relations.class){Relations.get(p).tts=enabled;Relations.save(p);}net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new VoiceSettings(enabled));if(notify)tell(p,Relations.tr(p,"Озвучка для тебя: ","Your TTS: ")+(enabled?"ON":"OFF"));return 1;}
}
