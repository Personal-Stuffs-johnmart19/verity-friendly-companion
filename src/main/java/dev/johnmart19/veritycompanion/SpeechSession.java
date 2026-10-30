package dev.johnmart19.veritycompanion;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** A player-presence lease only. Login never loads or warms a speech model. */
public final class SpeechSession {
 private static volatile SpeechSession current;
 private static boolean registered;
 private final MinecraftServer server;
 private final String session=UUID.randomUUID().toString();
 private final Object lock=new Object();
 private final Thread worker;
 private int players;
 private long revision=1;
 private boolean closed,warned;
 private SpeechSession(MinecraftServer server){this.server=server;players=count(server,null,null);worker=new Thread(this::run,"Verity speech session lease");worker.setDaemon(true);}
 static synchronized void start(MinecraftServer server){
  if(!registered){
   NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event)->{if(event.getEntity() instanceof ServerPlayer player)changed(player,false);});
   NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event)->{if(event.getEntity() instanceof ServerPlayer player)changed(player,true);});
   registered=true;
  }
  if(current!=null)return;SpeechSession next=new SpeechSession(server);current=next;next.worker.start();
 }
 static synchronized void stop(){SpeechSession previous=current;current=null;if(previous!=null)previous.close();}
 private static int count(MinecraftServer server,UUID excluded,UUID included){
  HashSet<UUID> connected=new HashSet<>();for(ServerPlayer player:server.getPlayerList().getPlayers())if(!player.getUUID().equals(excluded))connected.add(player.getUUID());
  if(included!=null)connected.add(included);return Math.min(1024,connected.size());
 }
 private static void changed(ServerPlayer player,boolean logout){
  SpeechSession active=current;if(active==null||active.server!=player.getServer())return;
  // NeoForge logout fires before PlayerList removes this player; explicitly exclude it.
  int count=count(active.server,logout?player.getUUID():null,logout?null:player.getUUID());
  synchronized(active.lock){if(active.closed)return;active.players=count;active.revision++;active.lock.notifyAll();}
 }
 private void close(){
  synchronized(lock){closed=true;players=0;revision++;worker.interrupt();lock.notifyAll();}
  // Cancel an old in-flight heartbeat so the final zero has its own bounded chance.
  try{worker.join(3000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
 }
 private void run(){
  long sentRevision=0,nextHeartbeat=0;
  for(;;){
   int count;long sendingRevision;boolean finalUpdate;
   synchronized(lock){
    while(sentRevision==revision&&(!closed)&&(players==0||System.nanoTime()<nextHeartbeat)){
     try{if(players==0)lock.wait();else{long wait=Math.max(1,(nextHeartbeat-System.nanoTime())/1_000_000L);lock.wait(wait);}}catch(InterruptedException ignored){/* Recheck the latest state, including shutdown. */}
    }
    if(sentRevision==revision&&!closed)revision++; // Heartbeats also need a fresh wire revision.
    count=players;sendingRevision=revision;finalUpdate=closed;
   }
   // Requests are serialized on this one worker. Only the latest revision is retained;
   // once closed, no delayed positive heartbeat can be sent after the final zero.
   if(finalUpdate)Thread.interrupted();
   try{
    NativeRuntime.get().presence(count);
    if(!ServerBridge.needsSpeechBridge()){if(finalUpdate)return;sentRevision=sendingRevision;nextHeartbeat=System.nanoTime()+20_000_000_000L;continue;}
    var config=ServerBridge.config();JsonObject body=new JsonObject();body.addProperty("session",session);body.addProperty("players",count);body.addProperty("revision",sendingRevision);
    var request=HttpRequest.newBuilder(URI.create(ProviderHttp.loopback(config.endpoint)+"/stt-session")).timeout(Duration.ofSeconds(2)).header("Authorization","Bearer "+ServerBridge.token()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    int status=ServerBridge.HTTP.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
    if(status!=200)throw new java.io.IOException("Speech session rejected");warned=false;
   }catch(InterruptedException ignored){Thread.interrupted();}
   catch(Exception ignored){if(!warned){System.err.println("[Verity Companion] speech session lease unavailable");warned=true;}}
   if(finalUpdate)return;
   sentRevision=sendingRevision;nextHeartbeat=System.nanoTime()+20_000_000_000L;
  }
 }
}
