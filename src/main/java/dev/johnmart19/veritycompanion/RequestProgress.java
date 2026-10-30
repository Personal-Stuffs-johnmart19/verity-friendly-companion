package dev.johnmart19.veritycompanion;
import java.util.*;import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
/** Short progress stays in the action bar; no TTS requests or debug payloads. */
final class RequestProgress {
 private static final Map<UUID,State> STATES=new ConcurrentHashMap<>();
 private static final class State {final ServerPlayer player;final long start=System.nanoTime();volatile String ru="Обдумываю запрос",en="Thinking about the request";long next;State(ServerPlayer p){player=p;}}
 static void begin(ServerPlayer player,String request){State state=new State(player);STATES.put(player.getUUID(),state);if(RequestFeedback.building(request))player.getServer().execute(()->{if(STATES.get(player.getUUID())==state&&!player.hasDisconnected())player.sendSystemMessage(Component.literal("<"+Relations.name(player)+"> "+Relations.tr(player,"Принял запрос на постройку. Выбираю место и составляю план; ход работы будет над панелью предметов.","Building request received. Selecting a site and plan; progress appears above the hotbar.")));});}
 static void phase(ServerPlayer player,String ru,String en){State s=STATES.get(player.getUUID());if(s!=null){s.ru=ru;s.en=en;}}
 static void tool(ServerPlayer p,String tool){switch(tool){case "survey_build_site"->phase(p,"Выбираю место для постройки","Surveying a building site");case "build_structure","build_schematic","build_blocks"->phase(p,"Строю: проверяю и размещаю блоки","Building: checking and placing blocks");case "undo_build"->phase(p,"Отменяю подтверждённые изменения","Undoing confirmed changes");default->phase(p,"Проверяю игровые данные","Checking game data");}}
 static void tick(MinecraftServer server){long now=System.nanoTime();for(var entry:STATES.entrySet()){State s=entry.getValue();ServerPlayer p=s.player;if(p.getServer()!=server||p.hasDisconnected()||server.getPlayerList().getPlayer(p.getUUID())!=p){STATES.remove(entry.getKey(),s);continue;}if(now<s.next)continue;s.next=now+1_500_000_000L;long seconds=TimeUnit.NANOSECONDS.toSeconds(now-s.start);p.displayClientMessage(Component.literal(Relations.name(p)+": "+Relations.tr(p,s.ru,s.en)+" · "+seconds+Relations.tr(p," с"," s")),true);}}
 static void end(ServerPlayer p){State state=STATES.remove(p.getUUID());if(state!=null&&!p.hasDisconnected())p.getServer().execute(()->{if(!STATES.containsKey(p.getUUID())&&!p.hasDisconnected())p.displayClientMessage(Component.empty(),true);});}
 static void clear(){STATES.clear();}
}
