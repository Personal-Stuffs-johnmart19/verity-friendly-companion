package dev.johnmart19.veritycompanion;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

/** Registry identity is captured on the server thread; locale I/O/indexing stays on one worker. */
public final class ServerItemCatalog {
 private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"Verity item catalog");thread.setDaemon(true);return thread;});
 private static volatile ItemCatalog catalog;private static volatile String status="Localized item index has not started";private static long generation;private static Future<?> loading;
 private static volatile ItemLocaleIndex.Snapshot locales;
 private static Iterator<net.minecraft.world.item.Item> pending;private static List<ItemCatalog.Entry> captured;private static List<Path> assetRoots;
 private ServerItemCatalog(){}
 public static void start(MinecraftServer server){if(!server.isSameThread()){server.execute(()->start(server));return;}
  synchronized(ServerItemCatalog.class){clear();status="Localized item index is loading; do not substitute a generic item for an unresolved modified name";}
  List<Path> roots=new ArrayList<>();for(var mod:ModList.get().getModFiles())roots.add(mod.getFile().findResource("assets"));
  pending=BuiltInRegistries.ITEM.iterator();captured=new ArrayList<>();assetRoots=List.copyOf(roots);
 }
 /** Server tick work is bounded; registry entries are never read from the I/O worker. */
 public static void tick(MinecraftServer server){if(!server.isSameThread()||pending==null)return;long deadline=System.nanoTime()+2_000_000;int processed=0;
  while(pending.hasNext()&&processed++<32&&System.nanoTime()<deadline){var item=pending.next();if(item==Items.AIR)continue;String id=BuiltInRegistries.ITEM.getKey(item).toString();try{captured.add(new ItemCatalog.Entry(id,item.getDescriptionId(),item.getDescription().getString()));}catch(RuntimeException|LinkageError unavailable){captured.add(new ItemCatalog.Entry(id,id,id));}}
  if(pending.hasNext())return;pending=null;List<ItemCatalog.Entry> entries=captured;captured=null;List<Path> roots=assetRoots;assetRoots=null;long epoch=generation;
  synchronized(ServerItemCatalog.class){if(epoch!=generation)return;loading=WORKER.submit(()->{
   try{ItemLocaleIndex.clear();var labels=ItemLocaleIndex.load(roots);if(Thread.currentThread().isInterrupted())return;ItemCatalog index=new ItemCatalog(entries,labels.english(),labels.russian());
    synchronized(ServerItemCatalog.class){if(epoch!=generation)return;locales=labels;catalog=index;status="Localized item index ready: registered_items="+entries.size()+"; locale_files="+labels.filesRead()+(labels.truncated()?"; some locale data unavailable or exceeded its budget":"");}
   }catch(Exception error){synchronized(ServerItemCatalog.class){if(epoch==generation)status="Localized item index unavailable; use an exact registered item ID or retry after restart";}}
  });}
 }
 public static synchronized void clear(){generation++;catalog=null;locales=null;pending=null;captured=null;assetRoots=null;status="Localized item index is stopped";if(loading!=null)loading.cancel(true);loading=null;RegistryLexicon.clear();}
 public static ItemLocaleIndex.Snapshot locales(){return locales;}
 static ItemCatalog current(){return catalog;}
 public static boolean ready(){return catalog!=null;}
 public static String status(){return status;}
 public static List<ItemCatalog.Match> search(String query,int limit){ItemCatalog index=catalog;if(index==null)return List.of();var result=index.search(query,limit);if(!result.isEmpty())return result;String hint=ItemHints.normalizeQuery(query);return hint.equals(query)?result:index.search(hint,limit);}
 public static Optional<ItemCatalog.Match> resolveUnique(String query){ItemCatalog index=catalog;if(index==null)return Optional.empty();var result=index.search(query,2);if(!result.isEmpty())return index.resolveUnique(query);return index.resolveUnique(ItemHints.normalizeQuery(query));}
 public static List<ItemCatalog.Match> mentioned(String original,int limit){ItemCatalog index=catalog;return index==null?List.of():index.mentioned(original,limit);}
}
