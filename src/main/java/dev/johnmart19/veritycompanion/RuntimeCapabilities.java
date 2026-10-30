package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Server-owned metadata cache. Command predicates and suggestions never perform I/O. */
final class RuntimeCapabilities {
 record Snapshot(boolean checked,boolean google,boolean lmReachable,boolean localSelected,List<String> models,Set<String> reasoning,Set<String> ttsProviders,Set<String> sttBackends,String provider,String engine,boolean audioBusy){
  Snapshot{models=List.copyOf(models);reasoning=Set.copyOf(reasoning);ttsProviders=Set.copyOf(ttsProviders);sttBackends=Set.copyOf(sttBackends);}
  static Snapshot empty(){return new Snapshot(false,false,false,false,List.of(),Set.of(),Set.of("disabled"),Set.of("disabled"),"disabled","disabled",false);}
 }
 private static final ThreadPoolExecutor WORK=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"Verity capability metadata");t.setDaemon(true);return t;});
 private static final AtomicBoolean REFRESHING=new AtomicBoolean();
 private static volatile Snapshot snapshot=Snapshot.empty();
 private static volatile MinecraftServer server;
 private static volatile long generation,nextRefresh;
 private RuntimeCapabilities(){}
 static Snapshot get(){return snapshot;}
 static void start(MinecraftServer active){server=active;generation++;snapshot=Snapshot.empty();nextRefresh=0;refresh(active);}
 static void stop(){server=null;generation++;snapshot=Snapshot.empty();nextRefresh=0;REFRESHING.set(false);WORK.getQueue().clear();}
 static void tick(MinecraftServer active){if(server==active&&System.nanoTime()>=nextRefresh){nextRefresh=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);refresh(active);}}
 static void refresh(MinecraftServer active){refresh(active,null);}
 private static void refresh(MinecraftServer active,CommandSourceStack report){
  if(server!=active||!REFRESHING.compareAndSet(false,true))return;
  long expected=generation;NativeRuntime runtime=NativeRuntime.get();
  try{WORK.execute(()->{try{
   if(server!=active||expected!=generation)return;Snapshot next=inspect(runtime);
   active.execute(()->{if(server!=active||expected!=generation)return;boolean changed=!next.equals(snapshot);snapshot=next;if(changed)for(var player:active.getPlayerList().getPlayers())active.getCommands().sendCommands(player);if(report!=null)report.sendSuccess(()->Component.literal(status(language(report))),false);});
  }catch(Exception unavailable){active.execute(()->{if(server!=active||expected!=generation)return;snapshot=Snapshot.empty();for(var player:active.getPlayerList().getPlayers())active.getCommands().sendCommands(player);if(report!=null)report.sendFailure(Component.literal(ModTranslations.text(language(report),"verity_companion.runtime.refresh_failed")));});}
  finally{if(expected==generation)REFRESHING.set(false);}});}catch(RejectedExecutionException busy){REFRESHING.set(false);}
 }
 static int requestRefresh(CommandSourceStack source){source.sendSuccess(()->Component.literal(ModTranslations.text(language(source),"verity_companion.runtime.refreshing")),false);refresh(source.getServer(),source);return 1;}
 static String language(CommandSourceStack source){return source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p?Relations.get(p).language:"en";}
 static String status(String language){Snapshot s=get();return ModTranslations.text(language,"verity_companion.runtime.status",s.checked(),s.google(),s.lmReachable(),String.join(", ",s.ttsProviders().stream().sorted().toList()),String.join(", ",s.sttBackends().stream().sorted().toList()));}
 static int status(CommandSourceStack source){source.sendSuccess(()->Component.literal(status(language(source))),false);return 1;}
 static boolean google(){return get().google();}
 static boolean local(){return get().lmReachable();}
 static boolean selectedLocal(){return get().localSelected();}
 static boolean ttsReady(){Snapshot s=get();return !s.provider().equals("disabled")&&s.ttsProviders().contains(s.provider());}
 static boolean googleVoice(){return get().provider().equals("google")&&google();}
 static com.mojang.brigadier.suggestion.Suggestions suggestions(com.mojang.brigadier.suggestion.SuggestionsBuilder builder,Collection<String> values){for(String value:values)if(value.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase()))builder.suggest(value);return builder.build();}

 /** Metadata only: bounded loopback inventory/status, private-file checks, no generation or process startup. */
 static Snapshot inspect(NativeRuntime runtime)throws Exception {
  JsonObject config=runtime.config();boolean google=false;
  if(ProviderHttp.text(config,"allow_external_requests","true").equals("true"))try{NativeGoogle.key(runtime.root(),config);google=true;}catch(Exception unavailable){}
  JsonArray inventory=new JsonArray();boolean reachable=false;try{inventory=runtime.local.models(config);reachable=true;}catch(Exception unavailable){}
  List<String> models=new ArrayList<>();Set<String> reasoning=new HashSet<>();boolean selected=false;int matched=0;JsonObject routing=runtime.local.status();String chosen=ProviderHttp.text(routing,"model","");
  for(JsonElement element:inventory){JsonObject model=element.getAsJsonObject();String key=ProviderHttp.text(model,"key","");models.add(key);boolean matches=key.equals(chosen);
   for(JsonElement variant:ProviderHttp.array(model,"variants")){String id=variant.getAsString();if(id.length()<=512&&!id.codePoints().anyMatch(Character::isISOControl)){models.add(id);matches|=id.equals(chosen);}}
   if(matches&&ProviderHttp.text(routing,"provider","").equals("local")){matched++;selected=true;JsonObject caps=model.has("capabilities")&&model.get("capabilities").isJsonObject()?model.getAsJsonObject("capabilities"):new JsonObject();JsonObject reasoningCaps=caps.has("reasoning")&&caps.get("reasoning").isJsonObject()?caps.getAsJsonObject("reasoning"):new JsonObject();for(JsonElement value:ProviderHttp.array(reasoningCaps,"allowed_options")){String option=value.getAsString();if(Set.of("off","on","low","medium","high").contains(option))reasoning.add(option);}}
  }
  if(matched!=1){selected=false;reasoning.clear();}
  Set<String> tts=new HashSet<>(Set.of("disabled")),stt=new HashSet<>(Set.of("disabled"));if(google){tts.add("google");if(config.has("google_audio_transcription_confirmed")&&config.get("google_audio_transcription_confirmed").getAsBoolean())stt.add("google");}
  JsonObject nativeConfig=config.deepCopy();nativeConfig.addProperty("provider","disabled");nativeConfig.addProperty("stt_language","auto");
  String backend=ProviderHttp.text(config,"stt_native_primary_backend",ProviderHttp.text(config,"stt_native_backend",""));String engine=switch(backend){case "vulkan"->"vulkan";case "cuda"->"native-cuda";case "cpu"->"native-cpu";default->"";};
  if(!engine.isEmpty()){nativeConfig.addProperty("stt_engine",engine);nativeConfig.addProperty("stt_native_backend",backend);if(runtime.audio.readiness(nativeConfig).isEmpty())stt.add(engine);}
  // A GPU executable's existence does not establish a separately prepared CPU build.
  if(config.has("stt_native_cpu_executable")){nativeConfig.addProperty("stt_engine","native-cpu");nativeConfig.addProperty("stt_native_backend","cpu");if(runtime.audio.readiness(nativeConfig).isEmpty())stt.add("native-cpu");}
  JsonObject drama=config.deepCopy();drama.addProperty("stt_engine","disabled");drama.addProperty("provider","drama3");drama.addProperty("tts_language","auto");if(runtime.audio.readiness(drama).isEmpty())tts.add("drama3");
  boolean busy=false;
  try{JsonObject bridge=runtime.audioStatus();busy=bridge.has("busy")&&bridge.get("busy").getAsBoolean();for(JsonElement value:ProviderHttp.array(bridge,"tts_providers"))if(value.getAsString().equals("silero"))tts.add("silero");for(JsonElement value:ProviderHttp.array(bridge,"stt_backends"))if(Set.of("faster-whisper","cuda","rocm").contains(value.getAsString()))stt.add(value.getAsString());}catch(Exception unavailable){}
  return new Snapshot(true,google,reachable,selected,new ArrayList<>(new LinkedHashSet<>(models)),reasoning,tts,stt,ProviderHttp.text(config,"provider","disabled"),ProviderHttp.text(config,"stt_engine","disabled"),busy);
 }
 static int select(CommandSourceStack source,boolean speech,String value){
  if(!source.hasPermission(2))return 0;Snapshot cached=get();Set<String> choices=speech?cached.ttsProviders():cached.sttBackends();if(!choices.contains(value)){source.sendFailure(Component.literal(ModTranslations.text(language(source),"verity_companion.runtime.unavailable")));return 0;}
  MinecraftServer active=source.getServer();long expected=generation;
  try{WORK.execute(()->{try{
   if(server!=active||generation!=expected)return;NativeRuntime runtime=NativeRuntime.get();Snapshot current=inspect(runtime);Set<String> actual=speech?current.ttsProviders():current.sttBackends();if(!actual.contains(value)||current.audioBusy())throw new IllegalStateException("Unavailable or busy");
   runtime.selectAudio(speech,value);active.execute(()->{if(server==active&&generation==expected){source.sendSuccess(()->Component.literal(ModTranslations.text(language(source),"verity_companion.runtime.selected",value)),false);refresh(active);}});
  }catch(Exception failed){active.execute(()->{if(server==active&&generation==expected)source.sendFailure(Component.literal(ModTranslations.text(language(source),"verity_companion.runtime.selection_failed")));});}});return 1;}catch(RejectedExecutionException full){source.sendFailure(Component.literal(ModTranslations.text(language(source),"verity_companion.runtime.refresh_failed")));return 0;}
 }
}
