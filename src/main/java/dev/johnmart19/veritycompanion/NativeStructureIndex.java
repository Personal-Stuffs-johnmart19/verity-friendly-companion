package dev.johnmart19.veritycompanion;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
/** Discovery of already installed native templates; never runs jigsaws, entities or world generation. */
final class NativeStructureIndex {
 private static final ExecutorService WORK=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Verity native structure index");t.setDaemon(true);return t;});
 private static volatile Map<String,ResourceLocation> entries=Map.of();
 private static volatile String status="not_started";
 private static long generation;private static Future<?> task;
 static synchronized void clear(){generation++;entries=Map.of();status="not_started";if(task!=null)task.cancel(true);task=null;}
 static synchronized void start(MinecraftServer server){clear();long epoch=generation;status="indexing";
  var manager=server.getResourceManager();task=WORK.submit(()->{
   try{Map<String,ResourceLocation> found=new TreeMap<>();
    for(String folder:List.of("structure","structures"))for(var id:manager.listResources(folder,p->p.getPath().endsWith(".nbt")).keySet()){
     if(Thread.currentThread().isInterrupted())return;String path=id.getPath();String key=id.getNamespace()+":"+path.substring(folder.length()+1,path.length()-4);found.putIfAbsent(key,id);
    }
    synchronized(NativeStructureIndex.class){if(epoch==generation){entries=Collections.unmodifiableMap(found);status="ready";}}
   }catch(RuntimeException failure){synchronized(NativeStructureIndex.class){if(epoch==generation)status="unavailable";}}
  });
 }
 static String status(){return status;}
 static int size(){return entries.size();}
 static List<String> matching(String query){String q=query==null?"":query.strip().toLowerCase(Locale.ROOT).replaceFirst("^resource:","");
  String translated=q.replace("небоскреб","skyscraper").replace("небоскрёб","skyscraper").replace("башня","tower").replace("башни","tower").replace("замок","castle").replace("дома","house").replace("дом","house").replace("аванпост","outpost").replace("склад","warehouse");
  final String search=q,other=translated;return entries.keySet().stream().filter(id->id.contains(search)||!other.equals(search)&&id.contains(other)).toList();
 }
 static NativeSchematicAdapter.Plan read(ServerPlayer player,String file)throws IOException{
  if(file==null||!file.startsWith("resource:"))throw new IllegalArgumentException("Select a listed resource: template");
  String key=file.substring(9);ResourceLocation template=ResourceLocation.tryParse(key);
  if(template==null||template.getPath().length()>200)throw new IllegalArgumentException("Invalid native template ID");
  ResourceLocation resource=entries.get(key);if(resource==null)throw new IllegalArgumentException("Native template not indexed/available; query schematics first ("+status+")");
  var loaded=player.getServer().getResourceManager().getResource(resource).orElseThrow(()->new IllegalArgumentException("Template resource changed; refresh the catalogue"));
  byte[] bytes;try(var input=loaded.open()){bytes=input.readNBytes((int)NativeSchematicAdapter.MAX_COMPRESSED_BYTES+1);}
  if(bytes.length>NativeSchematicAdapter.MAX_COMPRESSED_BYTES)throw new IllegalArgumentException("Native template exceeds compressed input budget");
  CompoundTag tag;try(var input=new ByteArrayInputStream(bytes)){tag=NbtIo.readCompressed(input,NbtAccounter.create(NativeSchematicAdapter.MAX_NBT_BYTES));}
  return NativeSchematicAdapter.decode(tag,player.serverLevel().holderLookup(Registries.BLOCK),CompanionBrain.settings.maxBlocksPerBuild,NativeSchematicAdapter.Mode.DECORATIVE);
 }
}
