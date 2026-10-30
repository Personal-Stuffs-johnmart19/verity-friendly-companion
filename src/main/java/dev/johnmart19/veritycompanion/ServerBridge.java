package dev.johnmart19.veritycompanion;
import java.net.*;import java.net.http.*;import java.nio.file.*;import java.time.Duration;import java.nio.charset.StandardCharsets;
import com.google.gson.*;
/** Never referenced by client code; all configuration and credentials are host-local. */
public final class ServerBridge {
 static {NativeBootstrap.preparePlatform();}
 static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
 static class Config {String runtime="bridge",endpoint="http://127.0.0.1:8766",tokenFile="verity-service/.secrets/bridge-token";}
 static synchronized Config config()throws Exception {Path p=Path.of("config/verity-companion-server.json");if(!Files.exists(p)){Files.createDirectories(p.getParent());Config fresh=new Config();fresh.runtime="native";Files.writeString(p,new GsonBuilder().setPrettyPrinting().create().toJson(fresh));}if(Files.size(p)>65536)throw new IllegalArgumentException("Server provider config too large");Config c=new Gson().fromJson(Files.readString(p),Config.class);if(!java.util.Set.of("bridge","native").contains(c.runtime))throw new IllegalArgumentException("Runtime must be native or bridge");return c;}
 // The old bridge selector is accepted for saved configurations. Provider logic
 // now always belongs to the game; the optional bridge executes Python audio only.
 static boolean nativeRuntime()throws Exception{config();return true;}
 static boolean needsSpeechBridge()throws Exception{return NativeRuntime.get().externalRecognition()||NativeRuntime.get().externalSpeech();}
 static JsonObject health()throws Exception {
  nativeRuntime();JsonObject health=NativeRuntime.get().health();
  if(needsSpeechBridge())try{
   JsonObject bridge=bridgeHealth();health.addProperty("dependencies_ready",health.get("dependencies_ready").getAsBoolean()&&(!bridge.has("dependencies_ready")||bridge.get("dependencies_ready").getAsBoolean()));
  }catch(Exception unavailable){
   health.addProperty("dependencies_ready",false);JsonObject issue=new JsonObject();issue.addProperty("code","bridge_offline");JsonArray features=new JsonArray();features.add("audio");issue.add("features",features);health.getAsJsonArray("setup_issues").add(issue);
  }
  return health;
 }
 private static JsonObject bridgeHealth()throws Exception {
  return ProviderHttp.object(new String(bridgePost("/audio/status","{}".getBytes(StandardCharsets.UTF_8),"application/json"),StandardCharsets.UTF_8));
 }
 static final class SetupException extends IllegalStateException {final String code;SetupException(String code){super(code);this.code=code;}}
 public static String token()throws Exception{try{String token=Files.readString(Path.of(config().tokenFile)).strip();if(token.isEmpty())throw new SetupException("bridge_token_missing");return token;}catch(java.nio.file.NoSuchFileException missing){throw new SetupException("bridge_token_missing");}}
 static void checkResponse(int status,String body){
  if(status==200)return;
  if(status==401)throw new SetupException("bridge_auth");
  if(body.length()<4096)try{JsonObject error=JsonParser.parseString(body).getAsJsonObject();if(error.has("error")&&error.get("error").getAsString().equals("setup_required")){String code=error.get("code").getAsString();if(SetupGuidance.message(code,false)!=null)throw new SetupException(code);}}catch(SetupException setup){throw setup;}catch(RuntimeException malformed){/* Never expose arbitrary response text. */}
  throw new IllegalStateException("Server bridge HTTP "+status);
 }
 static String setupFailure(Throwable failure,boolean ru){return setupFailure(failure,ru?"ru":"en");}
 static String setupFailure(Throwable failure,String language){
  for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
   if(cause instanceof SetupException setup)return SetupGuidance.message(setup.code,language);
   if(cause instanceof SpeechLanguages.Unavailable unsupported)return ModTranslations.text(language,"verity_companion.provider.language_unavailable",unsupported.provider,unsupported.language.equals("auto")?ModTranslations.text(language,"verity_companion.language.auto"):Languages.englishName(unsupported.language));
   if(cause instanceof NativeGoogle.Failure google&&google.status==429)return ModTranslations.text(language,google.daily?"verity_companion.provider.quota_daily":"verity_companion.provider.quota_minute");
   if(cause instanceof java.net.ConnectException||cause instanceof java.net.http.HttpConnectTimeoutException)return SetupGuidance.message("bridge_offline",language);
  }
  return null;
 }
 static byte[] post(String path,byte[] bytes,String type)throws Exception {nativeRuntime();if(!java.util.Set.of("/stt","/tts","/stt-session").contains(path))return NativeRuntime.get().post(path,bytes);return bridgePost(path,bytes,type);}
 static byte[] bridgePost(String path,byte[] bytes,String type)throws Exception {
  return bridgePost(path,bytes,type,null);
 }
 static byte[] bridgePost(String path,byte[] bytes,String type,String language)throws Exception {
  Config c=config();URI origin=ProviderHttp.loopback(c.endpoint);var builder=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(100)).header("Authorization","Bearer "+token()).header("Content-Type",type).POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
  if(language!=null)builder.header("X-Verity-Language",SpeechLanguages.whisper(Languages.normalize(language,true)));var request=builder.build();
  var response=HTTP.send(request,HttpResponse.BodyHandlers.ofInputStream());
  try(var input=new DeadlineInputStream(response.body(),100000)){int limit=path.equals("/tts")?VoiceStreamState.MAX_BYTES:4_000_000;byte[] body=input.readNBytes(limit+1);if(body.length>limit)throw new IllegalStateException("Server bridge response exceeds size limit");checkResponse(response.statusCode(),new String(body,0,Math.min(body.length,4096),StandardCharsets.UTF_8));return body;}
 }
 static byte[] speech(String text)throws Exception {return NativeRuntime.get().speech(text,"neutral","Achird");}
 static void speechStream(String text,java.util.function.Consumer<byte[]> consumer)throws Exception {
  speechStream(text,"neutral",consumer);
 }
 static void speechStream(String text,String emotion,java.util.function.Consumer<byte[]> consumer)throws Exception {
  speechStream(text,emotion,"Achird",consumer);
 }
 static void speechStream(String text,String emotion,String voice,java.util.function.Consumer<byte[]> consumer)throws Exception {
  speechStream(text,emotion,voice,null,consumer);
 }
 static void speechStream(String text,String emotion,String voice,String language,java.util.function.Consumer<byte[]> consumer)throws Exception {
  byte[] complete=NativeRuntime.get().speech(text,EmotionPolicy.normalize(emotion),TtsCommands.normalize(voice),language);
  for(byte[] frame:NativeGoogle.frames(complete))consumer.accept(frame);
 }
 static String transcribe(byte[] wav)throws Exception{return NativeRuntime.get().transcribe(wav);}
 static String transcribe(byte[] wav,String language)throws Exception{return NativeRuntime.get().transcribe(wav,language);}
}
