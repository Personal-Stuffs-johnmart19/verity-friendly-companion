package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Prepared local ASR and full-quality Drama3, orchestrated by the game JVM. */
final class NativeAudioSpeech implements AutoCloseable {
 private static final int MAX_PCM=6_000_000, MAX_WAV=12_000_000;
 private final Path root;
 private final NativeAudioProcess.Starter starter;
 private final Supplier<NativeAudioProcess.Transport> transports;
 private final ReentrantLock sttLock=new ReentrantLock(), ttsLock=new ReentrantLock();
 private final Object lifecycle=new Object();
 private NativeAudioProcess stt,tts;
 private String sttIdentity="",ttsIdentity="";
 private long generation;
 private boolean closed, absent;
 private volatile long gpuBlockedUntil;

 NativeAudioSpeech(Path root){this(root,NativeAudioProcess.PROCESS,NativeAudioProcess.Network::new);}
 NativeAudioSpeech(Path root,NativeAudioProcess.Starter starter,Supplier<NativeAudioProcess.Transport> transports){this.root=root.toAbsolutePath().normalize();this.starter=starter;this.transports=transports;}
 static boolean nativeRecognition(String engine){return Set.of("vulkan","native-cuda","native-cpu").contains(engine);}
 private long generation()throws IOException {synchronized(lifecycle){if(closed||absent)throw new IOException("Native speech has no active players");return generation;}}
 private void current(long expected)throws IOException {synchronized(lifecycle){if(closed||absent||expected!=generation)throw new IOException("Native speech request cancelled");}}
 void presence(int players){
  NativeAudioProcess oldStt=null,oldTts=null;
  synchronized(lifecycle){if(closed)return;if(players<=0){absent=true;generation++;oldStt=stt;oldTts=tts;stt=tts=null;}else absent=false;}
  if(oldStt!=null)oldStt.close();if(oldTts!=null)oldTts.close();
 }
 public void close(){
  NativeAudioProcess oldStt,oldTts;
  synchronized(lifecycle){if(closed)return;closed=true;absent=true;generation++;oldStt=stt;oldTts=tts;stt=tts=null;}
  if(oldStt!=null)oldStt.close();if(oldTts!=null)oldTts.close();
 }
 private record Prepared(Path executable,Path working,JsonObject settings,List<Path> libraries) {
  String identity(){return executable+"\n"+working+"\n"+settings+"\n"+libraries;}
 }
 private Path path(String value)throws IOException {if(value==null||value.isBlank())throw new IOException("Prepared native speech path is missing");try{return root.resolve(value).toAbsolutePath().normalize();}catch(InvalidPathException bad){throw new IOException("Invalid prepared native speech path");}}
 private static Path file(Path path)throws IOException {if(!Files.isRegularFile(path)||Files.size(path)==0)throw new IOException("Prepared native speech file is missing");return path.toRealPath();}
 private static Path model(Path path)throws IOException {if(!path.toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))throw new IOException("Prepared speech model must be GGUF");return file(path);}
 private static JsonObject readJson(Path path,int maximum)throws IOException {
  try(var input=Files.newInputStream(path)){return ProviderHttp.object(new String(NativeAudioProcess.readBounded(input,maximum),StandardCharsets.UTF_8).replace("\ufeff",""));}
  catch(RuntimeException invalid){throw new IOException("Invalid prepared native speech settings");}
 }
 private Prepared prepareStt(JsonObject config)throws IOException {
  String engine=ProviderHttp.text(config,"stt_engine","disabled");
  String backend=switch(engine){case "vulkan"->"vulkan";case "native-cuda"->"cuda";case "native-cpu"->"cpu";default->throw new IOException("Select a native recognition backend");};
  if(!backend.equals(ProviderHttp.text(config,"stt_native_backend",backend)))throw new IOException("Native recognition engine/backend do not match");
  String executableSetting=ProviderHttp.text(config,"stt_native_executable","");
  if(backend.equals("cpu"))executableSetting=ProviderHttp.text(config,"stt_native_cpu_executable",executableSetting);
  Path executable=file(path(executableSetting));
  Path model=model(path(ProviderHttp.text(config,"stt_native_model","")));
  JsonObject settings=new JsonObject();settings.addProperty("backend",backend);
  JsonObject entry=new JsonObject();entry.addProperty("id","verity-stt");entry.addProperty("family","qwen3_asr");entry.addProperty("path",model.toString());entry.addProperty("task","asr");entry.addProperty("mode","offline");entry.addProperty("lazy",true);
  JsonArray models=new JsonArray();models.add(entry);settings.add("models",models);
  List<Path> libraries=new ArrayList<>();
  if(backend.equals("cuda")&&config.has("stt_native_library_directories")){
   JsonArray configured=config.getAsJsonArray("stt_native_library_directories");if(configured.size()>64)throw new IOException("Too many native library directories");
   Path runtimeRoot=path(ProviderHttp.text(config,"stt_native_runtime_directory",executable.getParent().toString())).toRealPath();
   if(!executable.startsWith(runtimeRoot))throw new IOException("Native executable is outside its configured runtime");
   for(JsonElement configuredPath:configured){Path library=path(configuredPath.getAsString()).toRealPath();if(!Files.isDirectory(library)||!library.startsWith(runtimeRoot))throw new IOException("Native library directory is outside its configured runtime");libraries.add(library);}
  }
  return new Prepared(executable,executable.getParent(),settings,List.copyOf(libraries));
 }
 private Path dramaDirectory(JsonObject config)throws IOException {return path(ProviderHttp.text(config,"drama3_directory","../verity-tts")).toRealPath();}
 private Prepared prepareTts(JsonObject config)throws IOException {
  Path directory=dramaDirectory(config);
  Path executable=file(path(ProviderHttp.text(config,"drama3_executable",directory.resolve("runtime/audiocpp_server.exe").toString())));
  JsonObject source=readJson(directory.resolve("server.json"),262_144),settings=new JsonObject();
  // Copy execution controls, never additional listeners, UI, logging or management services.
  for(String key:List.of("backend","device","threads","default_session_options","model_spec_override","min_free_memory_mb"))if(source.has(key))settings.add(key,source.get(key).deepCopy());
  String backend=ProviderHttp.text(settings,"backend","");if(!Set.of("cpu","cuda","vulkan","hip","metal").contains(backend))throw new IOException("Unsupported prepared Drama3 backend");
  JsonObject selected=null;
  for(JsonElement candidate:ProviderHttp.array(source,"models")){
   JsonObject entry=candidate.getAsJsonObject();if("verity-drama3".equals(ProviderHttp.text(entry,"id",""))){if(selected!=null)throw new IOException("Duplicate prepared Drama3 model");selected=entry.deepCopy();}
  }
  if(selected==null||!"fish_drama3".equals(ProviderHttp.text(selected,"family",""))||!Set.of("clon","tts").contains(ProviderHttp.text(selected,"task",""))||!"streaming".equals(ProviderHttp.text(selected,"mode","")))throw new IOException("Prepared Drama3 streaming voice model is missing");
  selected.addProperty("path",model(directory.resolve(ProviderHttp.text(selected,"path",""))).toString());selected.addProperty("lazy",true);
  if(selected.has("model_spec_override")){String value=selected.get("model_spec_override").getAsString();if(!value.equals("embedded"))selected.addProperty("model_spec_override",directory.resolve(value).normalize().toString());}
  // Preserve the installed model/session settings, never enable pinned experts.
  for(String key:List.of("session_options","load_options"))if(selected.has(key)&&selected.get(key).isJsonObject())rejectPinned(selected.getAsJsonObject(key));
  if(settings.has("default_session_options"))rejectPinned(settings.getAsJsonObject("default_session_options"));
  JsonArray models=new JsonArray();models.add(selected);settings.add("models",models);
  // These are config-relative paths; our private config lives in a temporary leaf.
  for(String key:List.of("model_spec_override"))if(settings.has(key)){
   String value=settings.get(key).getAsString();if(!value.equals("embedded"))settings.addProperty(key,directory.resolve(value).normalize().toString());
  }
  return new Prepared(executable,directory,settings,List.of());
 }
 private static void rejectPinned(JsonObject options)throws IOException {if(options.has("fish_drama3.pin_experts")&&options.get("fish_drama3.pin_experts").getAsBoolean())throw new IOException("Drama3 pinned experts must remain disabled");}
 private NativeAudioProcess worker(boolean recognition,Prepared prepared,long expected)throws Exception {
  NativeAudioProcess worker,old=null;
  synchronized(lifecycle){
   current(expected);worker=recognition?stt:tts;String identity=recognition?sttIdentity:ttsIdentity;
   if(worker==null||!worker.alive()||!identity.equals(prepared.identity())){
    old=worker;
    if(recognition)stt=null;else tts=null;
    if(old!=null){old.close();old=null;}
    worker=new NativeAudioProcess(prepared.executable(),prepared.working(),prepared.settings(),prepared.libraries(),starter,transports.get());
    if(recognition){stt=worker;sttIdentity=prepared.identity();}else{tts=worker;ttsIdentity=prepared.identity();}
   }
  }
  if(old!=null)old.close();
  return worker;
 }
 private void fail(boolean recognition,NativeAudioProcess failed){
  synchronized(lifecycle){if(recognition&&stt==failed)stt=null;if(!recognition&&tts==failed)tts=null;}
  if(failed!=null)failed.close();
 }
 private void acquire(ReentrantLock lock,long deadline,long expected)throws Exception {
  while(!lock.tryLock(Math.min(50,NativeAudioProcess.remaining(deadline)),TimeUnit.MILLISECONDS))current(expected);
  try{current(expected);}catch(Exception cancelled){lock.unlock();throw cancelled;}
 }

 /** Validate bounded PCM before model initialization; quiet speech is retained. */
 static boolean signal(byte[] audio)throws IOException {
  if(audio==null||audio.length<44||audio.length>MAX_WAV)throw new IOException("Expected bounded PCM16 WAV");
  // Parse RIFF directly: no implicit format conversion, attacker-sized allocations or compressed decoders.
  ByteBuffer b=ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);
  if(b.getInt(0)!=0x46464952||b.getInt(8)!=0x45564157||Integer.toUnsignedLong(b.getInt(4))+8!=audio.length)throw new IOException("Invalid microphone WAV");
  int channels=0,rate=0,align=0,bits=0,dataOffset=-1,dataSize=0;boolean format=false;
  for(int offset=12;offset+8<=audio.length;){
   int tag=b.getInt(offset);long length=Integer.toUnsignedLong(b.getInt(offset+4));long end=offset+8L+length;
   if(end>audio.length)throw new IOException("Truncated microphone WAV");
   if(tag==0x20746d66){if(format||length<16||b.getShort(offset+8)!=1)throw new IOException("Expected PCM WAV");format=true;channels=Short.toUnsignedInt(b.getShort(offset+10));rate=b.getInt(offset+12);align=Short.toUnsignedInt(b.getShort(offset+20));bits=Short.toUnsignedInt(b.getShort(offset+22));if(b.getInt(offset+16)!=(long)rate*channels*2)throw new IOException("Invalid microphone byte rate");}
   if(tag==0x61746164){if(dataOffset>=0)throw new IOException("Duplicate microphone data");dataOffset=offset+8;dataSize=(int)length;}
   long next=end+(length&1);if(next>audio.length)throw new IOException("Invalid microphone WAV padding");offset=(int)next;
  }
  if(!format||channels<1||channels>2||rate<8000||rate>96000||bits!=16||align!=channels*2||dataOffset<0||dataSize<align||dataSize%align!=0||dataSize/(long)align>30L*rate)throw new IOException("Expected up to 30 seconds of mono/stereo PCM16");
  boolean signal=false;for(int i=dataOffset;i<dataOffset+dataSize;i+=2)signal|=Math.abs((int)b.getShort(i))>2;return signal;
 }
 String transcribe(byte[] wav,JsonObject config)throws Exception {
  // Keep silence outside initialization and retry: it never needs any runtime/model.
  boolean signal=signal(wav);SpeechLanguages.nativeAsr(SpeechLanguages.selected(config,"stt_language",null));if(!signal)return "";
  String engine=ProviderHttp.text(config,"stt_engine","disabled");
  boolean fallback=!engine.equals("native-cpu")&&nativeRecognition(engine)&&(!config.has("stt_cpu_fallback")||config.get("stt_cpu_fallback").getAsBoolean());
  long expected=generation(),deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);acquire(sttLock,deadline,expected);
  try {
   if(!fallback)return transcribeAttempt(wav,config,expected,deadline);
   prepareStt(config); // Misconfigured or missing prepared assets never silently change profiles.
   JsonObject cpu=config.deepCopy();cpu.addProperty("stt_engine","native-cpu");cpu.addProperty("stt_native_backend","cpu");
   if(System.nanoTime()<gpuBlockedUntil)return transcribeAttempt(wav,cpu,expected,deadline);
   try{return transcribeAttempt(wav,config,expected,deadline);}catch(Exception unavailable){
    current(expected);if(unavailable instanceof InterruptedException){Thread.currentThread().interrupt();throw unavailable;}
    gpuBlockedUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);return transcribeAttempt(wav,cpu,expected,deadline);
   }
  }finally{sttLock.unlock();}
 }
 private String transcribeAttempt(byte[] wav,JsonObject config,long expected,long deadline)throws Exception {
  String language=SpeechLanguages.nativeAsr(SpeechLanguages.selected(config,"stt_language",null));NativeAudioProcess worker=null;
  try {
   worker=worker(true,prepareStt(config),expected);worker.awaitReady(Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(30)));current(expected);
   String boundary="verity-"+UUID.randomUUID().toString().replace("-","");
   ByteArrayOutputStream body=new ByteArrayOutputStream(wav.length+1024);
   multipart(body,boundary,"model","verity-stt");if(!language.equals("auto"))multipart(body,boundary,"language",language);
   body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.US_ASCII));body.write(wav);body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
   try(var response=worker.open("POST","/v1/audio/transcriptions",body.toByteArray(),"multipart/form-data; boundary="+boundary,Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(90)))){
    if(response.status()!=200)throw new IOException("Local recognition request failed");
    JsonObject result=ProviderHttp.object(new String(NativeAudioProcess.readBounded(response.body(),65_536),StandardCharsets.UTF_8));
    JsonElement text=result.get("text");if(text==null||!text.isJsonPrimitive()||!text.getAsJsonPrimitive().isString()||text.getAsString().length()>6000)throw new IOException("Invalid local transcript");
    current(expected);worker.check();return text.getAsString().strip();
   }
  }catch(Exception failure){fail(true,worker);throw failure;}
 }
 private static void multipart(OutputStream body,String boundary,String name,String value)throws IOException {body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n").getBytes(StandardCharsets.US_ASCII));}
 static JsonObject speechBody(String text,Path directory)throws IOException {
  return speechBody(text,directory,"auto");
 }
 static JsonObject speechBody(String text,Path directory,String requested)throws IOException {
  String language=SpeechLanguages.localTts(requested,text);
  JsonObject references=readJson(directory.resolve("voices/reference-text.json"),65_536);
  JsonElement reference=references.get(language);if(reference==null||!reference.isJsonPrimitive()||!reference.getAsJsonPrimitive().isString()||reference.getAsString().isBlank()||reference.getAsString().length()>16000)throw new IOException("Prepared voice reference text is missing");
  Path voice=file(directory.resolve("voices/verity-"+language+".wav"));
  JsonObject body=new JsonObject();body.addProperty("model","verity-drama3");body.addProperty("input",text);body.addProperty("voice_ref",voice.toString());body.addProperty("reference_text",reference.getAsString());body.addProperty("response_format","pcm");body.addProperty("stream_format","sse");body.addProperty("max_tokens",1024);body.addProperty("seed",1234);
  JsonObject options=new JsonObject();options.addProperty("temperature",0.8);options.addProperty("top_p",0.9);options.addProperty("top_k",4097);body.add("options",options);return body;
 }
 byte[] speech(String text,String emotion,JsonObject config)throws Exception {
  text=text.replaceAll("https?://\\S+","").replaceAll("[`*_#]","").strip();if(text.isBlank()||text.length()>6000)throw new IOException("Speech transcript must contain 1–6000 characters");
  long expected=generation(),deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(150);acquire(ttsLock,deadline,expected);NativeAudioProcess worker=null;
  try {
   Path directory=dramaDirectory(config);JsonObject body=speechBody(text,directory,SpeechLanguages.selected(config,"tts_language",null));Prepared prepared=prepareTts(config);
   worker=worker(false,prepared,expected);worker.awaitReady(Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(30)));current(expected);
   // Drama3's established request has no emotion field; preserve its exact sampling controls.
   try(var response=worker.open("POST","/v1/audio/speech",body.toString().getBytes(StandardCharsets.UTF_8),"application/json",Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(120)))){
    if(response.status()!=200)throw new IOException("Local Drama3 request failed");
    byte[] complete=NativeGoogle.wav(readSse(response.body()),44100);NativeGoogle.validateWav(complete);current(expected);worker.check();return complete;
   }
  }catch(Exception failure){fail(false,worker);throw failure;}finally{ttsLock.unlock();}
 }
 static byte[] readSse(InputStream response)throws IOException {
  response=new BufferedInputStream(response,8192);
  ByteArrayOutputStream pcm=new ByteArrayOutputStream();boolean done=false;int bytes=0,events=0;
  while(true){
   ByteArrayOutputStream line=new ByteArrayOutputStream();int c;
   while((c=response.read())!=-1){if(++bytes>12_000_000||line.size()>=524288)throw new IOException("Oversized local speech stream");line.write(c);if(c=='\n')break;}
   if(line.size()==0)break;
   String value=line.toString(StandardCharsets.UTF_8);if(!value.startsWith("data:"))continue;
   if(++events>100_000)throw new IOException("Too many local speech events");String data=value.substring(5).strip();if(data.equals("[DONE]"))break;
   try {
    JsonObject event=ProviderHttp.object(data);String kind=ProviderHttp.text(event,"type","");
    if(event.has("error")||kind.equals("error")||kind.equals("speech.error"))throw new IOException("Local Drama3 stream failed");
    if(kind.equals("speech.audio.delta")){
     if(done)throw new IOException("Speech audio after completion");byte[] chunk=Base64.getDecoder().decode(event.get("audio").getAsString());
     if(chunk.length%2!=0||chunk.length>MAX_PCM-pcm.size())throw new IOException("Invalid local speech PCM bounds");pcm.write(chunk);
    }else if(kind.equals("speech.audio.done")){if(done)throw new IOException("Duplicate speech completion");done=true;}
   }catch(RuntimeException malformed){throw new IOException("Invalid local speech event");}
  }
  if(!done||pcm.size()==0)throw new IOException("Incomplete local speech stream");return pcm.toByteArray();
 }
 /** File-only readiness. Never starts a process, fetches a model or generates speech. */
 JsonArray readiness(JsonObject config){
  JsonArray issues=new JsonArray();
  if(nativeRecognition(ProviderHttp.text(config,"stt_engine","disabled")))try{
   SpeechLanguages.nativeAsr(SpeechLanguages.selected(config,"stt_language",null));prepareStt(config);
   if(!config.has("stt_cpu_fallback")||config.get("stt_cpu_fallback").getAsBoolean()){JsonObject cpu=config.deepCopy();cpu.addProperty("stt_engine","native-cpu");cpu.addProperty("stt_native_backend","cpu");prepareStt(cpu);}
  }catch(Exception missing){issue(issues,"native_stt_missing","Prepared native recognition executable/model/backend is unavailable","stt");}
  if("drama3".equals(ProviderHttp.text(config,"provider","")))try{SpeechLanguages.localTts(SpeechLanguages.selected(config,"tts_language",null),"ready");prepareTts(config);Path directory=dramaDirectory(config);speechBody("ready",directory);speechBody("готово",directory);}catch(Exception missing){issue(issues,"drama3_missing","Prepared Drama3 runtime, model settings, requested language or voice references are unavailable","tts");}
  return issues;
 }
 private static void issue(JsonArray issues,String code,String hint,String feature){JsonObject issue=new JsonObject();issue.addProperty("code",code);issue.addProperty("hint",hint);JsonArray features=new JsonArray();features.add(feature);issue.add("features",features);issues.add(issue);}
}
