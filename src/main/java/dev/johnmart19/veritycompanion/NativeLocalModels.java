package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Live LM Studio inventory, transactional selection and temporary shared overrides. */
final class NativeLocalModels {
 private final Path root;
 private final ProviderHttp.Transport transport;
 private final Object lock=new Object();
 private final Semaphore inference=new Semaphore(1,true);
 private JsonObject state,overrides=new JsonObject(),job=new JsonObject();
 private String jobId="";private boolean loading;private long revision;
 private volatile boolean closed;
 void close(){synchronized(lock){closed=true;revision++;}}
 record Snapshot(long revision,JsonObject state,JsonObject request,JsonObject overrides) {}
 NativeLocalModels(Path root,ProviderHttp.Transport transport){this.root=root;this.transport=transport;}
 private void load()throws Exception {
  if(state!=null)return;
  Path file=root.resolve("chat-routing.json");
  if(Files.exists(file)){
   if(Files.size(file)>32768)throw new IOException("Invalid local routing state");
   try{JsonObject saved=ProviderHttp.object(Files.readString(file));if(!Set.of("google","local").contains(ProviderHttp.text(saved,"provider","")))throw new IOException("Invalid chat routing; explicitly select a provider");state=saved;}
   catch(RuntimeException invalid){throw new IOException("Invalid chat routing; explicitly select a provider");}
  }else{state=new JsonObject();state.addProperty("provider","google");}
 }
 private void persist(JsonObject next)throws Exception {
  Files.createDirectories(root);Path temporary=root.resolve("chat-routing.json.tmp");Files.writeString(temporary,next.toString());Files.move(temporary,root.resolve("chat-routing.json"),StandardCopyOption.REPLACE_EXISTING);
 }
 private JsonObject http(String path,JsonObject body,JsonObject config,long timeout)throws Exception {
  if(closed)throw new IOException("Local model session ended");
  URI base=ProviderHttp.loopback(ProviderHttp.text(config,"lmstudio_endpoint","http://127.0.0.1:1234"));
  String token="";Path tokenFile=root.resolve(".secrets/lmstudio-api-key");if(!Files.exists(tokenFile))tokenFile=root.resolve(".secrets/lmstudio-api-token");if(Files.exists(tokenFile)){if(Files.size(tokenFile)>4096)throw new IOException("Invalid LM Studio token");token=Files.readString(tokenFile).replace("\ufeff","").strip();if(!token.matches("[\\x21-\\x7e]{1,1024}"))throw new IOException("Invalid LM Studio token");}
  var response=transport.send(URI.create(base+path),body,"Authorization",token.isEmpty()?"":"Bearer "+token,timeout);
  if(response.status()!=200)throw new IOException("LM Studio request unavailable (HTTP "+response.status()+")");return response.body();
 }
 JsonArray models(JsonObject config)throws Exception {
  JsonObject response=http("/api/v1/models",null,config,5000);if(!response.has("models")||!response.get("models").isJsonArray())throw new IOException("Invalid LM Studio inventory");
  JsonArray result=new JsonArray();for(JsonElement entry:response.getAsJsonArray("models")){
   JsonObject model=entry.getAsJsonObject();if(!"llm".equals(ProviderHttp.text(model,"type","")))continue;
   String key=ProviderHttp.text(model,"key","");if(key.isBlank()||key.length()>512||key.codePoints().anyMatch(Character::isISOControl))throw new IOException("Invalid local model identifier");
   JsonObject clean=model.deepCopy();if(!clean.has("display_name"))clean.addProperty("display_name",key);if(!clean.has("variants"))clean.add("variants",new JsonArray());if(!clean.has("capabilities"))clean.add("capabilities",new JsonObject());result.add(clean);
  }return result;
 }
 private static JsonObject find(JsonArray models,String key)throws IOException {
  List<JsonObject> found=new ArrayList<>();for(JsonElement entry:models){JsonObject model=entry.getAsJsonObject();if(ProviderHttp.text(model,"key","").equals(key)||ProviderHttp.array(model,"variants").asList().stream().anyMatch(v->v.isJsonPrimitive()&&v.getAsString().equals(key)))found.add(model);}
  if(found.size()!=1)throw new IOException("Model is absent or ambiguous in the live LM Studio inventory");return found.getFirst();
 }
 JsonObject status()throws Exception {
  synchronized(lock){load();JsonObject result=state.deepCopy();if(!result.has("model"))result.add("model",JsonNull.INSTANCE);if(!result.has("context"))result.addProperty("context",16384);JsonObject visible=overrides.deepCopy();visible.remove("system_prompt");result.add("overrides",visible);result.addProperty("system_prompt_chars",ProviderHttp.text(overrides,"system_prompt","").length());result.addProperty("loading",loading);result.add("job",job.deepCopy());return result;}
 }
 private JsonObject select(String key,Integer requested,JsonObject config,boolean preserve,long expected)throws Exception {
  JsonArray inventory=models(config);JsonObject model=find(inventory,key);int maximum=ProviderHttp.integer(model,"max_context_length",16384);int context=requested==null?Math.min(16384,maximum):requested;
  if(context<4096||context>Math.min(1048576,maximum))throw new IOException("Requested context is outside this model's supported range");
  final JsonObject previous;final String id=UUID.randomUUID().toString();
  synchronized(lock){load();if(loading||inference.availablePermits()==0||expected>=0&&revision!=expected)throw new IOException("Model/settings busy or changed; retry");previous=state.deepCopy();loading=true;jobId=id;job=new JsonObject();job.addProperty("state","loading");}
  Thread worker=new Thread(()->{
   boolean committed=false;int actual=context;
   try{
    JsonObject body=new JsonObject();body.addProperty("model",key);body.addProperty("context_length",context);body.addProperty("echo_load_config",true);
    JsonObject loaded=http("/api/v1/models/load",body,config,180000);String instance=ProviderHttp.text(loaded,"instance_id","");actual=loaded.has("load_config")?ProviderHttp.integer(loaded.getAsJsonObject("load_config"),"context_length",0):0;
    if(!"loaded".equals(ProviderHttp.text(loaded,"status",""))||instance.isBlank()||actual<context)throw new IOException("LM Studio did not confirm the requested loaded context");
    JsonObject next=new JsonObject();next.addProperty("provider","local");next.addProperty("model",key);next.addProperty("instance",instance);next.addProperty("context",context);
    synchronized(lock){if(closed)throw new IOException("Local model session ended");persist(next);state=next;if(!preserve){overrides.remove("reasoning");overrides.remove("context");}else overrides.addProperty("context",context);revision++;committed=true;}
    String old=ProviderHttp.text(previous,"instance","");boolean owned=false;
    if("local".equals(ProviderHttp.text(previous,"provider",""))&&!old.isBlank()&&!old.equals(instance))try{JsonObject oldModel=find(inventory,ProviderHttp.text(previous,"model",""));for(JsonElement e:ProviderHttp.array(oldModel,"loaded_instances"))if(old.equals(ProviderHttp.text(e.getAsJsonObject(),"id","")))owned=true;}catch(IOException absent){}
    if(owned){JsonObject unload=new JsonObject();unload.addProperty("instance_id",old);JsonObject result=http("/api/v1/models/unload",unload,config,15000);if(!old.equals(ProviderHttp.text(result,"instance_id","")))throw new IOException("Previous instance unload unconfirmed");}
    synchronized(lock){job.addProperty("state","ready");job.addProperty("context",context);}
   }catch(Exception failure){synchronized(lock){job.addProperty("state",committed?"ready":"failed");job.addProperty("context",context);if(committed)job.addProperty("warning","Previous companion instance cleanup failed");else job.addProperty("message","LM Studio could not confirm model loading; previous routing preserved");}}
   finally{synchronized(lock){loading=false;}}
  },"Verity native model loading");worker.setDaemon(true);worker.start();JsonObject result=new JsonObject();result.addProperty("job_id",id);result.addProperty("context",context);return result;
 }
 JsonObject command(String action,JsonObject body,JsonObject config)throws Exception {
  switch(action){
   case "models"->{JsonObject result=new JsonObject();result.add("models",models(config));return result;}
   case "status"->{synchronized(lock){if(body.has("job_id")&&!jobId.equals(body.get("job_id").getAsString()))throw new IOException("Unknown local model job");}return status();}
   case "select"->{return select(body.get("model").getAsString(),null,config,false,-1);}
   case "cloud"->{synchronized(lock){load();if(loading)throw new IOException("Wait for model loading to finish");JsonObject next=new JsonObject();next.addProperty("provider","google");persist(next);state=next;overrides.remove("reasoning");overrides.remove("context");revision++;}return status();}
   case "settings"->{return configure(body.get("action").getAsString(),body.get("value"),config);}
   default->throw new IOException("Unknown local model operation");
  }
 }
 private JsonObject configure(String action,JsonElement value,JsonObject config)throws Exception {
  if(Set.of("context","reasoning","caps").contains(action)){
   JsonObject selected;long expected;synchronized(lock){load();selected=state.deepCopy();expected=revision;if(loading)throw new IOException("Model loading is in progress");}
   if(!"local".equals(ProviderHttp.text(selected,"provider","")))throw new IOException("This control requires a selected local model; Google manages its own context/reasoning");
   JsonObject model=find(models(config),ProviderHttp.text(selected,"model",""));if(action.equals("caps")){JsonObject result=new JsonObject();result.add("model",model);return result;}
   if(action.equals("context")){int context=value.getAsInt();if(context<4096||context>Math.min(1048576,ProviderHttp.integer(model,"max_context_length",16384)))throw new IOException("Context exceeds model capacity");if(context>ProviderHttp.integer(selected,"context",4096))return select(selected.get("model").getAsString(),context,config,true,expected);}
   if(action.equals("reasoning"))reasoning(model,value.getAsString());
   synchronized(lock){if(expected!=revision||loading)throw new IOException("Selected model/settings changed; retry");overrides.add(action,value.deepCopy());revision++;}return status();
  }
  synchronized(lock){load();switch(action){
   case "prompt_show"->{JsonObject result=new JsonObject();result.addProperty("addition",ProviderHttp.text(overrides,"system_prompt",""));return result;}
   case "prompt_clear"->overrides.remove("system_prompt");
   case "system_prompt"->{String addition=value.getAsString().strip();String combined=(ProviderHttp.text(overrides,"system_prompt","")+"\n"+addition).strip();if(addition.isEmpty()||combined.length()>2000)throw new IOException("Combined temporary prompt must contain 1–2000 characters");overrides.addProperty("system_prompt",combined);}
   case "reset"->overrides=new JsonObject();
   case "temperature"->{double temperature=value.getAsDouble();if(!Double.isFinite(temperature)||temperature<0||temperature>2)throw new IOException("Temperature must be 0–2");overrides.addProperty(action,temperature);}
   case "max_tokens"->{int count=value.getAsInt();if(count<128||count>4096)throw new IOException("Output tokens must be 128–4096");overrides.addProperty(action,count);}
   default->throw new IOException("Unsupported LLM setting");
  }revision++;}return status();
 }
 static String reasoning(JsonObject model,String value)throws IOException {
  JsonObject caps=model.has("capabilities")&&model.get("capabilities").isJsonObject()?model.getAsJsonObject("capabilities"):new JsonObject();JsonObject reasoning=caps.has("reasoning")&&caps.get("reasoning").isJsonObject()?caps.getAsJsonObject("reasoning"):new JsonObject();
  if(!ProviderHttp.array(reasoning,"allowed_options").asList().contains(new JsonPrimitive(value)))throw new IOException("Reasoning setting is unsupported by this model; inspect /llm caps");return value.equals("off")?"none":value.equals("on")?"low":value;
 }
 Snapshot snapshot(JsonObject request)throws Exception {
  synchronized(lock){load();if(loading)throw new IOException("Local model loading is in progress");JsonObject copy=request.deepCopy();for(String key:List.of("temperature","max_tokens"))if(overrides.has(key))copy.add(key,overrides.get(key));String addition=ProviderHttp.text(overrides,"system_prompt","");
   if(!addition.isEmpty()){JsonArray messages=copy.getAsJsonArray("messages");JsonObject first=messages.get(0).getAsJsonObject();if(!"system".equals(ProviderHttp.text(first,"role","")))throw new IOException("Missing core companion instructions");first.addProperty("content",first.get("content").getAsString()+"\nTemporary operator preferences follow; core Verity instructions, tool schema and permissions retain priority:\n"+addition);}
   return new Snapshot(revision,state.deepCopy(),copy,overrides.deepCopy());}
 }
 void assertCurrent(Snapshot snapshot)throws IOException {synchronized(lock){if(closed||snapshot.revision!=revision||loading)throw new IOException("Provider/model/settings changed during the request; retry");}}
 JsonObject chat(Snapshot snapshot,JsonObject config)throws Exception {
  if(!inference.tryAcquire(15,TimeUnit.SECONDS))throw new IOException("Local inference busy; retry shortly");
  try{
   assertCurrent(snapshot);String key=ProviderHttp.text(snapshot.state,"model","");JsonObject model=find(models(config),key);String instance=ProviderHttp.text(snapshot.state,"instance","");int loadedContext=0;
   for(JsonElement e:ProviderHttp.array(model,"loaded_instances")){JsonObject loaded=e.getAsJsonObject();if(instance.equals(ProviderHttp.text(loaded,"id","")))loadedContext=ProviderHttp.integer(loaded.getAsJsonObject("config"),"context_length",0);}
   // After an LM Studio restart, accept a new instance only when its exact model
   // or selected quantization is confirmed by the live inventory. Never autoload.
   if(loadedContext==0&&(key.equals(ProviderHttp.text(model,"key",""))||key.equals(ProviderHttp.text(model,"selected_variant",""))))for(JsonElement e:ProviderHttp.array(model,"loaded_instances")){JsonObject loaded=e.getAsJsonObject();int actual=ProviderHttp.integer(loaded.getAsJsonObject("config"),"context_length",0);if(actual>loadedContext){loadedContext=actual;instance=ProviderHttp.text(loaded,"id","");}}
   if(loadedContext<4096)throw new IOException("Selected local instance is unloaded; select it again with /llm local");
   int context=Math.min(loadedContext,ProviderHttp.integer(snapshot.overrides,"context",ProviderHttp.integer(snapshot.state,"context",4096)));
   JsonObject body=snapshot.request.deepCopy();body.remove("language");body.addProperty("model",instance);int requested=ProviderHttp.integer(body,"max_tokens",2048);if(!snapshot.overrides.has("max_tokens")&&!ProviderHttp.text(snapshot.overrides,"reasoning","").equals("off")&&context>=8192&&(key.toLowerCase(Locale.ROOT).contains("qwen")||key.toLowerCase(Locale.ROOT).contains("gpt-oss")))requested=Math.max(requested,4096);int output=Math.min(requested,Math.max(128,context/3));JsonArray fitted;try{fitted=fit(body.getAsJsonArray("messages"),context,output);}catch(IOException tooLarge){int ordinary=Math.min(ProviderHttp.integer(snapshot.request,"max_tokens",2048),Math.max(128,context/3));if(ordinary>=output)throw tooLarge;output=ordinary;fitted=fit(body.getAsJsonArray("messages"),context,output);}body.addProperty("max_tokens",output);body.add("messages",fitted);
   if(snapshot.overrides.has("reasoning"))body.addProperty("reasoning_effort",reasoning(model,snapshot.overrides.get("reasoning").getAsString()));
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(110);
   for(int attempt=0;attempt<2;attempt++){
    assertCurrent(snapshot);long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(remaining<1)throw new IOException("Local response deadline exceeded");
    JsonObject response=http("/v1/chat/completions",body,config,Math.min(60000,remaining));assertCurrent(snapshot);
    JsonObject choice=response.getAsJsonArray("choices").get(0).getAsJsonObject();String finish=ProviderHttp.text(choice,"finish_reason","");
    if(!Set.of("stop","length").contains(finish))throw new IOException("Local response not completed; no actions executed");
    try{
     if(!finish.equals("stop"))throw new IllegalArgumentException();
     JsonObject message=choice.getAsJsonObject("message");String raw=message.get("content").getAsString().strip();if(raw.startsWith("```")&&raw.endsWith("```")){int newline=raw.indexOf('\n');if(newline>0)raw=raw.substring(newline+1,raw.length()-3).strip();}
     JsonObject parsed=LegacyModelReply.normalize(ProviderHttp.object(raw));for(JsonElement e:ProviderHttp.array(parsed,"actions")){JsonObject action=e.getAsJsonObject();if(action.has("args")&&action.get("args").isJsonObject())action.addProperty("args",action.get("args").toString());}
     message.addProperty("content",ModelReply.parse(parsed.toString()).toString());response.addProperty("provider","local");response.addProperty("model",key);return response;
    }catch(RuntimeException invalid){if(attempt==1)throw new IOException("Local model returned incomplete/invalid action JSON; no actions executed");JsonObject system=body.getAsJsonArray("messages").get(0).getAsJsonObject();system.addProperty("content",system.get("content").getAsString()+"\nFormatting repair only: return one concise completed JSON object matching the existing schema, no Markdown or reasoning text. Preserve all Verity instructions and tool rules.");body.add("messages",fit(body.getAsJsonArray("messages"),context,output));}
   }
   throw new IOException("Local model could not complete action JSON");
  }finally{inference.release();}
 }
 static JsonArray fit(JsonArray messages,int context,int output)throws IOException {
  int size=messages.size(),budget=context-output-512,total=0;int[] costs=new int[size];Set<Integer> protectedIndices=new HashSet<>(),observations=new HashSet<>(),feedback=new HashSet<>();List<Integer> users=new ArrayList<>();List<Set<Integer>> pairs=new ArrayList<>();
  for(int i=0;i<size;i++){JsonObject message=messages.get(i).getAsJsonObject();String role=ProviderHttp.text(message,"role","");String content=ProviderHttp.text(message,"content","");costs[i]=(content.getBytes(StandardCharsets.UTF_8).length+1)/2+16;total+=costs[i];if(role.equals("system"))protectedIndices.add(i);if(content.startsWith("SERVER OBSERVATIONS")||content.startsWith("НАБЛЮДЕНИЯ СЕРВЕРА ("))observations.add(i);else if(content.startsWith("TOOL RESULTS (")||content.startsWith("РЕЗУЛЬТАТЫ ИНСТРУМЕНТОВ ("))feedback.add(i);else if(role.equals("user"))users.add(i);}
  if(!users.isEmpty()){protectedIndices.add(users.getLast());for(int n=0;n<users.size()-1;n++){int last=-1;for(int i=users.get(n)+1;i<users.get(n+1);i++)if("assistant".equals(ProviderHttp.text(messages.get(i).getAsJsonObject(),"role","")))last=i;if(last>=0)pairs.add(Set.of(users.get(n),last));}}
  for(int i=Math.max(0,pairs.size()-2);i<pairs.size();i++)protectedIndices.addAll(pairs.get(i));int current=users.isEmpty()?size:users.getLast();for(int i:feedback)if(i>current){protectedIndices.add(i);if(i>0&&"assistant".equals(ProviderHttp.text(messages.get(i-1).getAsJsonObject(),"role","")))protectedIndices.add(i-1);}if(size>0&&!observations.contains(size-1))protectedIndices.add(size-1);
  List<Set<Integer>> candidates=new ArrayList<>();observations.stream().sorted().forEach(i->candidates.add(Set.of(i)));candidates.addAll(pairs);for(int i=0;i<size;i++)candidates.add(Set.of(i));Set<Integer> removed=new HashSet<>();
  for(Set<Integer> group:candidates){if(total<=budget)break;if(!Collections.disjoint(group,protectedIndices))continue;for(int i:group)if(removed.add(i))total-=costs[i];}
  if(total>budget)throw new IOException("Core instructions, recent dialogue and tool results exceed local context; select a larger context/model");JsonArray result=new JsonArray();for(int i=0;i<size;i++)if(!removed.contains(i))result.add(messages.get(i).deepCopy());return result;
 }
}
