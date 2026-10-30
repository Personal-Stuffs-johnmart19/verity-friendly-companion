package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bundled vanilla server lexicon first; actual installed mod locales override it. All reads are bounded. */
public final class ItemLocaleIndex {
 static final int MAX_FILES=8192,MAX_FILE_BYTES=1_048_576,MAX_TOTAL_BYTES=32*1_048_576,MAX_LABELS=250_000;
 private static final String FALLBACK="assets/verity_companion/catalogue/vanilla_";
 public record Snapshot(Map<String,String> english,Map<String,String> russian,boolean truncated,int filesRead){}
 private static List<Path> cachedRoots;private static ClassLoader cachedLoader;private static Snapshot cached;
 private ItemLocaleIndex(){}
 public static synchronized void clear(){cachedRoots=null;cachedLoader=null;cached=null;}
 /** Invoke on a worker thread; Paths may refer directly to the loaded mod's zip filesystem. */
 public static Snapshot load(Collection<Path> assetRoots){return load(assetRoots,ItemLocaleIndex.class.getClassLoader());}
 static synchronized Snapshot load(Collection<Path> assetRoots,ClassLoader loader){List<Path> roots=List.copyOf(new LinkedHashSet<>(assetRoots));if(roots.equals(cachedRoots)&&loader==cachedLoader&&cached!=null)return cached;
  Budget budget=new Budget();
  for(String locale:List.of("en_us","ru_ru"))try(InputStream input=loader.getResourceAsStream(FALLBACK+locale+".json")){if(input!=null)budget.read(input,locale);else budget.truncated=true;}catch(IOException|RuntimeException unavailable){budget.truncated=true;}
  outer:for(Path root:roots){if(Thread.currentThread().isInterrupted())break;if(!Files.isDirectory(root))continue;
   try(var namespaces=Files.newDirectoryStream(root)){int namespaceCount=0;for(Path namespace:namespaces){if(++namespaceCount>4096){budget.truncated=true;break;}if(!Files.isDirectory(namespace))continue;
    for(String locale:List.of("en_us","ru_ru")){Path file=namespace.resolve("lang").resolve(locale+".json");if(!Files.isRegularFile(file))continue;if(budget.full()){budget.truncated=true;break outer;}
     try(InputStream input=Files.newInputStream(file)){budget.read(input,locale);}catch(IOException|RuntimeException unavailable){budget.truncated=true;}
    }
   }}catch(IOException|RuntimeException unavailable){budget.truncated=true;}
  }
  if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Item locale indexing cancelled");cachedRoots=roots;cachedLoader=loader;cached=new Snapshot(Map.copyOf(budget.en),Map.copyOf(budget.ru),budget.truncated,budget.files);return cached;
 }
 private static final class Budget {
  final Map<String,String> en=new HashMap<>(),ru=new HashMap<>();int files,total;boolean truncated;
  boolean full(){return files>=MAX_FILES||total>=MAX_TOTAL_BYTES||en.size()+ru.size()>=MAX_LABELS;}
  void read(InputStream input,String locale)throws IOException{
   if(full()){truncated=true;return;}files++;int remaining=MAX_TOTAL_BYTES-total;byte[] bytes=input.readNBytes(Math.min(MAX_FILE_BYTES,remaining)+1);total+=bytes.length;
   if(bytes.length>MAX_FILE_BYTES||bytes.length>remaining){truncated=true;return;}
   JsonObject json=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();Map<String,String> target=locale.equals("en_us")?en:ru;
   for(var entry:json.entrySet()){if(en.size()+ru.size()>=MAX_LABELS){truncated=true;break;}if(!entry.getValue().isJsonPrimitive()||!entry.getValue().getAsJsonPrimitive().isString())continue;String label=entry.getValue().getAsString();boolean description=entry.getKey().matches(".*\\.(description|desc|tooltip)");if(label.length()<=(description?4096:240)&&!label.isBlank())target.put(entry.getKey(),label);}
  }
 }
}
