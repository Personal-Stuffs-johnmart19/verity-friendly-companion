package dev.johnmart19.veritycompanion;
import java.net.*;import java.net.http.*;import java.nio.file.*;import java.time.Duration;import java.nio.charset.StandardCharsets;
import com.google.gson.*;
/** Never referenced by client code; all configuration and credentials are host-local. */
public final class ServerBridge {
 static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
 static class Config {String endpoint="http://127.0.0.1:8766",tokenFile="verity-service/.secrets/bridge-token";}
 static Config config()throws Exception {Path p=Path.of("config/verity-companion-server.json");if(!Files.exists(p)){Files.createDirectories(p.getParent());Files.writeString(p,new GsonBuilder().setPrettyPrinting().create().toJson(new Config()));}Config c=new Gson().fromJson(Files.readString(p),Config.class);URI u=URI.create(c.endpoint);if(!"http".equals(u.getScheme())||!java.util.Set.of("127.0.0.1","localhost","[::1]").contains(u.getHost())||u.getUserInfo()!=null)throw new IllegalArgumentException("Server bridge must use loopback HTTP");return c;}
 public static String token()throws Exception{return Files.readString(Path.of(config().tokenFile)).strip();}
 static byte[] post(String path,byte[] bytes,String type)throws Exception {Config c=config();var r=HTTP.send(HttpRequest.newBuilder(URI.create(c.endpoint+path)).timeout(Duration.ofSeconds(100)).header("Authorization","Bearer "+token()).header("Content-Type",type).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofByteArray());if(r.statusCode()!=200)throw new IllegalStateException("Server voice service HTTP "+r.statusCode());if(r.body().length>AudioFrames.MAX_BYTES&&path.equals("/tts"))throw new IllegalStateException("Voice reply too long; shorten response");return r.body();}
 static byte[] speech(String text)throws Exception {JsonObject b=new JsonObject();b.addProperty("text",text);return post("/tts",b.toString().getBytes(StandardCharsets.UTF_8),"application/json");}
 static void speechStream(String text,java.util.function.Consumer<byte[]> consumer)throws Exception {
  speechStream(text,"neutral",consumer);
 }
 static void speechStream(String text,String emotion,java.util.function.Consumer<byte[]> consumer)throws Exception {
  speechStream(text,emotion,"Achird",consumer);
 }
 static void speechStream(String text,String emotion,String voice,java.util.function.Consumer<byte[]> consumer)throws Exception {
  Config c=config();JsonObject body=new JsonObject();body.addProperty("text",text);body.addProperty("emotion",EmotionPolicy.normalize(emotion));body.addProperty("voice",TtsCommands.normalize(voice));
  var request=HttpRequest.newBuilder(URI.create(c.endpoint+"/tts-stream")).timeout(Duration.ofSeconds(120)).header("Authorization","Bearer "+token()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
  var response=HTTP.send(request,HttpResponse.BodyHandlers.ofInputStream());
  try(var in=new java.io.DataInputStream(new DeadlineInputStream(response.body(),210_000))){
   if(response.statusCode()!=200)throw new IllegalStateException("Server voice stream HTTP "+response.statusCode());
   int total=0,parts=0;
   while(true){int size=in.readInt();if(size==0){if(parts==0)throw new IllegalStateException("Empty voice stream");return;}if(size<44||size>AudioFrames.MAX_BYTES||size>VoiceStreamState.MAX_BYTES-total||++parts>2048)throw new IllegalStateException("Voice stream exceeds budget");byte[] wav=in.readNBytes(size);if(wav.length!=size)throw new java.io.EOFException("Truncated voice stream");total+=size;consumer.accept(wav);}
  }
 }
 static String transcribe(byte[] wav)throws Exception {return JsonParser.parseString(new String(post("/stt",wav,"audio/wav"),StandardCharsets.UTF_8)).getAsJsonObject().get("text").getAsString().strip();}
}
