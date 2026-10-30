package dev.johnmart19.veritycompanion;

import com.google.gson.JsonObject;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** One owned, lazy audio.cpp API process. Never attaches to another service. */
final class NativeAudioProcess implements AutoCloseable {
 interface Starter {
  Process start(Path executable,Path config,Path workingDirectory)throws IOException;
  default Process start(Path executable,Path config,Path workingDirectory,List<Path> libraries)throws IOException{return start(executable,config,workingDirectory);}
 }
 interface Transport extends AutoCloseable {
  Response open(URI uri, String method, byte[] body, String contentType, long deadline) throws Exception;
  @Override default void close() {}
 }
 record Response(int status, InputStream body) implements AutoCloseable {
  @Override public void close() throws IOException {body.close();}
 }
 static final Starter PROCESS=new Starter(){
  public Process start(Path executable,Path config,Path directory)throws IOException{return launch(executable,config,directory,List.of());}
  public Process start(Path executable,Path config,Path directory,List<Path> libraries)throws IOException{return launch(executable,config,directory,libraries);}
 };
 private static Process launch(Path executable,Path config,Path directory,List<Path> libraries)throws IOException {
  ProcessBuilder builder=new ProcessBuilder(executable.toString(),"--config",config.toString(),"--no-ui")
   .directory(directory.toFile()).redirectInput(new File(System.getProperty("os.name").startsWith("Windows")?"NUL":"/dev/null"))
   .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
  if(!libraries.isEmpty()){
   String prefix=String.join(File.pathSeparator,libraries.stream().map(Path::toString).toList());
   builder.environment().put("PATH",prefix+File.pathSeparator+builder.environment().getOrDefault("PATH",""));
   if(System.getProperty("os.name").startsWith("Linux"))builder.environment().put("LD_LIBRARY_PATH",prefix+File.pathSeparator+builder.environment().getOrDefault("LD_LIBRARY_PATH",""));
  }
  return builder.start();
 }

 /** Headers and body share one absolute deadline; close cancels both stages. */
 static final class Network implements Transport {
  static {NativeBootstrap.preparePlatform();}
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
   .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector(){
    public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
    public void connectFailed(URI uri,SocketAddress address,IOException failure){}
   }).build();
  private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
  private final Set<InputStream> bodies=ConcurrentHashMap.newKeySet();
  private boolean closed;
  public Response open(URI uri,String method,byte[] body,String contentType,long deadline)throws Exception {
   if(!"http".equals(uri.getScheme())||!"127.0.0.1".equals(uri.getHost())||uri.getPort()<1)throw new IOException("Native audio requires numeric loopback");
   long remaining=remaining(deadline);
   var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(remaining));
   if(body==null)builder.GET();else builder.header("Content-Type",contentType).method(method,HttpRequest.BodyPublishers.ofByteArray(body));
   CompletableFuture<HttpResponse<InputStream>> future;
   synchronized(this){if(closed)throw new IOException("Native audio transport closed");future=client.sendAsync(builder.build(),HttpResponse.BodyHandlers.ofInputStream());pending.add(future);}
   InputStream stream=null;
   try {
    var response=future.get(remaining(deadline),TimeUnit.MILLISECONDS);
    stream=response.body();
    synchronized(this){if(closed){stream.close();throw new IOException("Native audio transport closed");}bodies.add(stream);}
    InputStream registered=stream;
    var bounded=new DeadlineInputStream(stream,remaining(deadline));
    return new Response(response.statusCode(),new FilterInputStream(bounded){
     public void close()throws IOException {try{super.close();}finally{bodies.remove(registered);}}
    });
   }catch(Exception failure){future.cancel(true);if(stream!=null){bodies.remove(stream);try{stream.close();}catch(IOException ignored){}}throw failure;}
   finally{pending.remove(future);}
  }
  public void close(){
   synchronized(this){if(closed)return;closed=true;for(var future:pending)future.cancel(true);}
   for(var stream:bodies)try{stream.close();}catch(IOException ignored){}
   client.shutdownNow();
  }
 }

 private final Transport transport;
 private final Path directory, configFile;
 private final Process process;
 private final URI origin;
 private final String backend;
 private volatile boolean closed;

 NativeAudioProcess(Path executable,Path workingDirectory,JsonObject settings,List<Path> libraries,Starter starter,Transport transport)throws Exception {
  this.transport=transport;backend=ProviderHttp.text(settings,"backend","");
  directory=Files.createTempDirectory("verity-native-audio-");configFile=directory.resolve("server.json");
  Process started=null;
  try {
   try{Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}
   int port;try(ServerSocket socket=new ServerSocket()){socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0));port=socket.getLocalPort();}
   origin=URI.create("http://127.0.0.1:"+port);
   settings=settings.deepCopy();settings.addProperty("host","127.0.0.1");settings.addProperty("port",port);
   settings.addProperty("lazy_load",true);settings.addProperty("max_loaded_models",1);settings.addProperty("ui_management",false);
   settings.addProperty("log_request_body",false);settings.addProperty("max_request_body_bytes",32_000_000);settings.addProperty("busy_timeout_ms",1000);
   Files.writeString(configFile,settings.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
   try{Files.setPosixFilePermissions(configFile,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
   started=starter.start(executable,configFile,workingDirectory,libraries);process=started;
  }catch(Exception failure){if(started!=null)started.destroyForcibly();transport.close();deleteOwnedFiles();throw failure;}
 }
 static long remaining(long deadline)throws IOException {
  long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(remaining<1)throw new IOException("Native audio deadline exceeded");return remaining;
 }
 boolean alive(){return !closed&&process.isAlive();}
 void awaitReady(long deadline)throws Exception {
  while(true){
   check();remaining(deadline);
   try(var response=open("GET","/health",null,null,Math.min(deadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(400)))){
    byte[] raw=readBounded(response.body(),65_536);
    JsonObject health=ProviderHttp.object(new String(raw,StandardCharsets.UTF_8));
    boolean management=health.has("ui_management")&&health.get("ui_management").getAsBoolean();
    if(response.status()==200&&"ok".equals(ProviderHttp.text(health,"status",""))&&backend.equals(ProviderHttp.text(health,"backend",""))&&ProviderHttp.integer(health,"models",0)==1&&!management){check();return;}
   }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
   catch(Exception transientFailure){check();remaining(deadline);}
   Thread.sleep(Math.min(50,remaining(deadline)));
  }
 }
 void check()throws IOException {if(!alive())throw new IOException("Owned native audio process stopped");}
 Response open(String method,String path,byte[] body,String contentType,long deadline)throws Exception {
  check();if(body!=null&&body.length>32_000_000)throw new IOException("Native audio request exceeds limit");
  return transport.open(origin.resolve(path),method,body,contentType,deadline);
 }
 static byte[] readBounded(InputStream input,int maximum)throws IOException {
  byte[] raw=input.readNBytes(maximum+1);if(raw.length>maximum)throw new IOException("Native audio response exceeds limit");return raw;
 }
 public void close(){
  if(closed)return;closed=true;transport.close();
  // Do not block Minecraft presence/shutdown on model termination.
  process.destroy();if(process.isAlive())process.destroyForcibly();
  deleteOwnedFiles();
 }
 private void deleteOwnedFiles(){
  // These two paths originate only from our createTempDirectory call; never recurse.
  try{Files.deleteIfExists(configFile);}catch(IOException ignored){}
  try{Files.deleteIfExists(directory);}catch(IOException ignored){}
 }
}
