package dev.johnmart19.veritycompanion;
import java.util.*;import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
/** Short progress stays in the action bar; no TTS requests or debug payloads. */
final class RequestProgress {
 private static final Map<UUID,State> STATES=new ConcurrentHashMap<>();
 private static final class State {final ServerPlayer player;final long start=System.nanoTime();volatile String key="verity_companion.request_progress.thinking";volatile Object[] args=new Object[0];long next;State(ServerPlayer p){player=p;}}
 static void begin(ServerPlayer player,String request){State state=new State(player);STATES.put(player.getUUID(),state);if(RequestFeedback.building(request))player.getServer().execute(()->{if(STATES.get(player.getUUID())==state&&!player.hasDisconnected())player.sendSystemMessage(Component.literal("<"+Relations.name(player)+"> "+ModTranslations.text(Relations.get(player).language,"verity_companion.request_progress.building_request_received_selecting_a_site_and")));});}
 static void phase(ServerPlayer player,String key,Object...args){State s=STATES.get(player.getUUID());if(s!=null){s.key=key;s.args=args.clone();}}
 static void tool(ServerPlayer p,String tool){String key=switch(tool){case "survey_build_site"->"survey";case "build_structure","build_schematic","build_blocks"->"building";case "undo_build"->"undo";default->"checking";};phase(p,"verity_companion.request_progress."+key);}
 static void tick(MinecraftServer server){long now=System.nanoTime();for(var entry:STATES.entrySet()){State s=entry.getValue();ServerPlayer p=s.player;if(p.getServer()!=server||p.hasDisconnected()||server.getPlayerList().getPlayer(p.getUUID())!=p){STATES.remove(entry.getKey(),s);continue;}if(now<s.next)continue;s.next=now+1_500_000_000L;long seconds=TimeUnit.NANOSECONDS.toSeconds(now-s.start);p.displayClientMessage(Component.literal(ModTranslations.text(Relations.get(p).language,"verity_companion.request_progress.elapsed",Relations.name(p),ModTranslations.text(Relations.get(p).language,s.key,s.args),seconds)),true);}}
 static void end(ServerPlayer p){State state=STATES.remove(p.getUUID());if(state!=null&&!p.hasDisconnected())p.getServer().execute(()->{if(!STATES.containsKey(p.getUUID())&&!p.hasDisconnected())p.displayClientMessage(Component.empty(),true);});}
 static void clear(){STATES.clear();}
}
