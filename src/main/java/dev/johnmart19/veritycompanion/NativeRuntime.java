package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Server-only providers. No listening port, Python, microphone access or client credentials. */
final class NativeRuntime {
 private static NativeRuntime current;
 private final Path root;
 private final ProviderHttp.Transport transport;
 final NativeGoogle google;
 final NativeLocalModels local;
 final NativeAudioSpeech audio;
 private final NativeSpeechCache speechCache;
 private volatile boolean closed;
 private int activeAudio;
 NativeRuntime(Path root,ProviderHttp.Transport transport){this(root,transport,new NativeAudioSpeech(root));}
 NativeRuntime(Path root,ProviderHttp.Transport transport,NativeAudioSpeech audio){this.root=root;this.transport=transport;google=new NativeGoogle(root,transport);local=new NativeLocalModels(root,transport);this.audio=audio;speechCache=new NativeSpeechCache(root);}
 Path root(){return root;}
 JsonObject audioStatus()throws Exception {
  if(!Files.isRegularFile(root.resolve(".secrets/bridge-token")))throw new IOException("Optional audio service is not prepared");
  var config=ServerBridge.config();var origin=ProviderHttp.loopback(config.endpoint);var result=transport.send(java.net.URI.create(origin+"/audio/status"),new JsonObject(),"Authorization","Bearer "+ServerBridge.token(),3000);
  if(result.status()!=200)throw new IOException("Optional audio status unavailable");return result.body();
 }
 synchronized void selectAudio(boolean speech,String value)throws Exception {
  if(closed)throw new IOException("Server provider session ended");
  if(activeAudio!=0)throw new IOException("Audio request in progress; retry after it finishes");
  if(!(speech?java.util.Set.of("disabled","google","drama3","silero"):java.util.Set.of("disabled","google","faster-whisper","cuda","rocm","vulkan","native-cuda","native-cpu")).contains(value))throw new IOException("Unsupported audio selection");
  config();Path file=root.resolve("voice-config.json");if(Files.size(file)>65536)throw new IOException("Voice configuration is too large");byte[] previous=Files.readAllBytes(file);JsonObject selected=ProviderHttp.object(new String(previous,StandardCharsets.UTF_8).replace("\ufeff",""));
  if(value.equals("google")){requireExternal();NativeGoogle.key(root,selected);if(!speech&&(!selected.has("google_audio_transcription_confirmed")||!selected.get("google_audio_transcription_confirmed").getAsBoolean()))throw new ServerBridge.SetupException("google_audio_confirmation");}
  selected.addProperty(speech?"provider":"stt_engine",value);
  if(!speech&&NativeAudioSpeech.nativeRecognition(value)){
   if(!selected.has("stt_native_primary_backend"))selected.addProperty("stt_native_primary_backend",ProviderHttp.text(selected,"stt_native_backend",""));
   selected.addProperty("stt_native_backend",value.equals("native-cuda")?"cuda":value.equals("native-cpu")?"cpu":"vulkan");
  }
  Path temporary=Files.createTempFile(root,".voice-selection-",".tmp");
  try{Files.writeString(temporary,new GsonBuilder().setPrettyPrinting().create().toJson(selected));if(Files.size(file)>65536||!java.util.Arrays.equals(previous,Files.readAllBytes(file)))throw new IOException("Voice settings changed; refresh and retry");Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
 }
 static synchronized NativeRuntime get(){if(current==null)current=new NativeRuntime(Path.of("verity-service"),ProviderHttp.NETWORK);return current;}
 static synchronized void clear(){if(current!=null){current.closed=true;current.google.close();current.local.close();current.audio.close();}current=null;}
 synchronized JsonObject config()throws Exception {
  NativeBootstrap.prepare(root);
  Path file=root.resolve("voice-config.json");
  if(!Files.exists(file)){
   Files.createDirectories(root);JsonObject defaults=ProviderHttp.object("{\"provider\":\"google\",\"stt_engine\":\"disabled\",\"stt_google_model\":\"gemini-3.1-flash-lite\",\"chat_model\":\"gemini-3.1-flash-lite\",\"google_voice\":\"Achird\",\"google_audio_transcription_confirmed\":false,\"google_free_project_confirmed\":false,\"allow_external_requests\":true,\"lmstudio_endpoint\":\"http://127.0.0.1:1234\",\"playback_mode\":\"complete\"}");
   defaults.add("google_tts_models",new Gson().toJsonTree(NativeGoogle.TTS_MODELS));Files.writeString(file,new GsonBuilder().setPrettyPrinting().create().toJson(defaults),StandardOpenOption.CREATE_NEW);
  }
  if(Files.size(file)>65536)throw new IOException("Voice configuration is too large");
  try{
   JsonObject config=ProviderHttp.object(Files.readString(file).replace("\ufeff",""));
   if(!config.has("stt_engine")){
    Path old=root.toAbsolutePath().resolveSibling("config").resolve("verity-companion-server.json");
    boolean legacy=Files.isRegularFile(old)&&Files.size(old)<65536&&"bridge".equals(ProviderHttp.text(ProviderHttp.object(Files.readString(old)),"runtime","bridge"));
    config.addProperty("stt_engine",legacy?"faster-whisper":"disabled");
   }
   if("cpu".equals(ProviderHttp.text(config,"stt_engine","")))config.addProperty("stt_engine","faster-whisper");
   return config;
  }catch(RuntimeException invalid){throw new IOException("Invalid voice configuration; existing settings preserved");}
 }
 boolean externalAllowed()throws Exception {JsonObject config=config();return !config.has("allow_external_requests")||config.get("allow_external_requests").getAsBoolean();}
 private void requireExternal()throws Exception {if(!externalAllowed())throw new ServerBridge.SetupException("external_disabled");}
 byte[] post(String path,byte[] bytes)throws Exception {
  if(closed)throw new IOException("Server provider session ended");
  JsonObject body=ProviderHttp.object(new String(bytes,StandardCharsets.UTF_8));JsonObject config=config(),result;
  if(path.startsWith("/lmstudio/"))result=local.command(path.substring("/lmstudio/".length()),body,config);
  else if(path.equals("/v1/chat/completions")){
   var snapshot=local.snapshot(body);if("local".equals(ProviderHttp.text(snapshot.state(),"provider","")))result=local.chat(snapshot,config);
   else {requireExternal();result=google.chat(snapshot.request(),config,()->local.assertCurrent(snapshot));local.assertCurrent(snapshot);}
  }else if(path.equals("/google-search")){
   // Gemini 3 Flash-Lite grounding is not a free-tier API feature. Public search
   // returns URLs to the existing tool loop without enabling a paid Google tool.
   requireExternal();result=new JsonObject();result.addProperty("provider","bing");result.addProperty("results",WebTools.search(ProviderHttp.text(body,"query","")));
  }else throw new IOException("Unsupported native provider operation");
  return result.toString().getBytes(StandardCharsets.UTF_8);
 }
 private static String fallback(JsonObject config)throws IOException {
  String provider=ProviderHttp.text(config,"local_fallback_provider","silero");
  if(!java.util.Set.of("silero","drama3").contains(provider))throw new IOException("Select a supported local TTS fallback");return provider;
 }
 boolean externalSpeech()throws Exception {
  JsonObject config=config();String provider=ProviderHttp.text(config,"provider","google");
  return provider.equals("silero")||provider.equals("google")&&config.has("local_fallback")&&config.get("local_fallback").getAsBoolean()&&fallback(config).equals("silero");
 }
 boolean externalRecognition()throws Exception {return java.util.Set.of("faster-whisper","cuda","rocm").contains(ProviderHttp.text(config(),"stt_engine","disabled"));}
 void presence(int players){audio.presence(players);}
 byte[] speech(String text,String emotion,String voice)throws Exception {
  return speech(text,emotion,voice,null);
 }
 byte[] speech(String text,String emotion,String voice,String language)throws Exception {
  synchronized(this){if(closed)throw new IOException("Server provider session ended");activeAudio++;}
  try{return speechRequest(text,emotion,voice,language);}finally{synchronized(this){activeAudio--;}}
 }
 private byte[] speechRequest(String text,String emotion,String voice,String language)throws Exception {
  if(closed)throw new IOException("Server provider session ended");
  JsonObject config=SpeechLanguages.request(config(),"tts_language",language);String provider=ProviderHttp.text(config,"provider","google");
  if(provider.equals("disabled"))throw new ServerBridge.SetupException("tts_disabled");
  if(provider.equals("google")){
   byte[] cached=speechCache.get(config,text,emotion,voice);if(cached!=null)return cached;
   try{requireExternal();byte[] complete=google.speech(text,emotion,voice,config);speechCache.put(config,text,emotion,voice,complete);return complete;}
   catch(Exception failure){
    if(closed||Thread.currentThread().isInterrupted()||failure instanceof InterruptedException)throw failure;
    if(!config.has("local_fallback")||!config.get("local_fallback").getAsBoolean())throw failure;
    config=config.deepCopy();provider=fallback(config);config.addProperty("provider",provider);
   }
  }
  config.addProperty("tts_language",SpeechLanguages.localTts(SpeechLanguages.selected(config,"tts_language",null),text));
  byte[] cached=speechCache.get(config,text,emotion,voice);if(cached!=null)return cached;
  byte[] complete;
  if(provider.equals("drama3"))complete=audio.speech(text,emotion,config);
  else if(provider.equals("silero")){
   JsonObject body=new JsonObject();body.addProperty("text",text);body.addProperty("emotion",emotion);body.addProperty("voice",voice);body.addProperty("language",ProviderHttp.text(config,"tts_language","auto"));
   complete=ServerBridge.bridgePost("/tts",body.toString().getBytes(StandardCharsets.UTF_8),"application/json");
  }else throw new IOException("Unsupported server TTS provider");
  NativeGoogle.validateWav(complete);speechCache.put(config,text,emotion,voice,complete);return complete;
 }
 String transcribe(byte[] wav)throws Exception {
  return transcribe(wav,null);
 }
 String transcribe(byte[] wav,String language)throws Exception {
  synchronized(this){if(closed)throw new IOException("Server provider session ended");activeAudio++;}
  try{return transcribeRequest(wav,language);}finally{synchronized(this){activeAudio--;}}
 }
 private String transcribeRequest(byte[] wav,String language)throws Exception {
  if(closed)throw new IOException("Server provider session ended");
  JsonObject config=SpeechLanguages.request(config(),"stt_language",language);String engine=ProviderHttp.text(config,"stt_engine","disabled");
  if(engine.equals("disabled"))throw new ServerBridge.SetupException("stt_disabled");
  if(engine.equals("google")){requireExternal();return google.transcribe(wav,config);}
  if(NativeAudioSpeech.nativeRecognition(engine))return audio.transcribe(wav,config);
  if(!java.util.Set.of("faster-whisper","cuda","rocm").contains(engine))throw new IOException("Unsupported server recognition backend");
  try{
   JsonObject reply=ProviderHttp.object(new String(ServerBridge.bridgePost("/stt",wav,"audio/wav",SpeechLanguages.whisper(ProviderHttp.text(config,"stt_language","auto"))),StandardCharsets.UTF_8));
   String text=ProviderHttp.text(reply,"text","");if(text.length()>6000)throw new IOException("Invalid recognition response");return text.strip();
  }catch(Exception unavailable){
   if(closed||Thread.currentThread().isInterrupted()||unavailable instanceof InterruptedException)throw unavailable;
   if(!config.has("stt_vulkan_fallback")||!config.get("stt_vulkan_fallback").getAsBoolean())throw unavailable;
   JsonObject nativeConfig=config.deepCopy();nativeConfig.addProperty("stt_engine","vulkan");nativeConfig.addProperty("stt_native_backend","vulkan");return audio.transcribe(wav,nativeConfig);
  }
 }
 JsonObject health()throws Exception {
  JsonObject config=config(),routing=local.status(),health=new JsonObject();JsonArray issues=new JsonArray();String provider=ProviderHttp.text(routing,"provider","google");
  if(!provider.equals("local")||ProviderHttp.text(config,"provider","google").equals("google")||ProviderHttp.text(config,"stt_engine","disabled").equals("google"))try{NativeGoogle.key(root,config);}catch(ServerBridge.SetupException missing){JsonObject issue=new JsonObject();issue.addProperty("code",missing.code);issue.addProperty("hint",SetupGuidance.message(missing.code,false));JsonArray features=new JsonArray();if(!provider.equals("local"))features.add("chat");if(ProviderHttp.text(config,"provider","google").equals("google"))features.add("tts");if(ProviderHttp.text(config,"stt_engine","disabled").equals("google"))features.add("stt");issue.add("features",features);issues.add(issue);}
  if(ProviderHttp.text(config,"stt_engine","disabled").equals("google")&&(!config.has("google_audio_transcription_confirmed")||!config.get("google_audio_transcription_confirmed").getAsBoolean())){JsonObject issue=new JsonObject();issue.addProperty("code","google_audio_confirmation");issue.addProperty("hint",SetupGuidance.message("google_audio_confirmation",false));JsonArray features=new JsonArray();features.add("stt");issue.add("features",features);issues.add(issue);}
  JsonObject audioConfig=config;
  if(java.util.Set.of("cuda","rocm","faster-whisper").contains(ProviderHttp.text(config,"stt_engine","disabled"))&&config.has("stt_vulkan_fallback")&&config.get("stt_vulkan_fallback").getAsBoolean()){
   audioConfig=config.deepCopy();audioConfig.addProperty("stt_engine","vulkan");audioConfig.addProperty("stt_native_backend","vulkan");
  }
  if("google".equals(ProviderHttp.text(audioConfig,"provider","google"))&&audioConfig.has("local_fallback")&&audioConfig.get("local_fallback").getAsBoolean()&&fallback(audioConfig).equals("drama3")){audioConfig=audioConfig.deepCopy();audioConfig.addProperty("provider","drama3");}
  JsonArray audioIssues=audio.readiness(audioConfig);issues.addAll(audioIssues);
  health.addProperty("ready",true);health.addProperty("runtime","native");health.addProperty("dependencies_ready",audioIssues.isEmpty());health.addProperty("chat_provider",provider);health.add("chat_model",routing.get("model"));health.add("setup_issues",issues);health.addProperty("external_requests",externalAllowed());health.addProperty("optional_speech_bridge",externalRecognition()||!ProviderHttp.text(config,"provider","google").equals("disabled")&&externalSpeech());return health;
 }
}
