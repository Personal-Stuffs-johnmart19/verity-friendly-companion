package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.*;

/** Tick-bounded registry capture and asynchronous, generation-checked catalogue persistence. */
public final class GameCatalogue {
 private static final Gson JSON=new Gson();
 private static final ExecutorService WORK=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"Verity game catalogue");thread.setDaemon(true);return thread;});
 private static volatile RecipeCatalogue recipes;private static volatile String state="not_started";private static volatile long generation;
 private static MinecraftServer owner;private static Iterator<RecipeHolder<?>> pending;private static List<RecipeCatalogue.Entry> captured;private static boolean submitted;private static Future<?> work;
 private GameCatalogue(){}
 public static void start(MinecraftServer server){if(!server.isSameThread()){server.execute(()->start(server));return;}clear();owner=server;ServerItemCatalog.start(server);pending=server.getRecipeManager().getRecipes().iterator();captured=new ArrayList<>();state="capturing_current_registries";}
 public static synchronized void clear(){generation++;owner=null;pending=null;captured=null;submitted=false;recipes=null;state="stopped";if(work!=null)work.cancel(true);work=null;ServerItemCatalog.clear();}
 public static void tick(MinecraftServer server){if(!server.isSameThread()||owner!=server)return;ServerItemCatalog.tick(server);
  long deadline=System.nanoTime()+2_000_000;int processed=0;
  if(pending!=null){while(pending.hasNext()&&processed++<16&&System.nanoTime()<deadline)captured.add(capture(server,pending.next()));if(pending.hasNext())return;pending=null;state="waiting_for_localized_items";}
  ItemCatalog items=ServerItemCatalog.current();if(submitted||items==null)return;submitted=true;List<RecipeCatalogue.Entry> snapshot=captured;captured=null;long epoch=generation;Path directory=server.getServerDirectory().resolve("config/verity-catalogue");
  work=WORK.submit(()->{try{RecipeCatalogue index=new RecipeCatalogue(snapshot);synchronized(GameCatalogue.class){if(epoch!=generation||Thread.currentThread().isInterrupted())return;recipes=index;state="ready; persistence_pending";}persist(directory,epoch,items,index);synchronized(GameCatalogue.class){if(epoch==generation)state="ready; persisted";}}catch(Exception failure){synchronized(GameCatalogue.class){if(epoch==generation)state=recipes==null?"index_unavailable":"ready; persistence_unavailable";}}});
 }
 private static RecipeCatalogue.Entry capture(MinecraftServer server,RecipeHolder<?> holder){String id=holder.id().toString(),type="unavailable",serializer="unavailable",encoded="";List<List<String>> ingredients=new ArrayList<>(),codecReferences=List.of();List<RecipeCatalogue.Output> outputs=new ArrayList<>();StringJoiner coverage=new StringJoiner("; ");Recipe<?> recipe=holder.value();
  try{type=String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()));serializer=String.valueOf(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()));}catch(RuntimeException|LinkageError failure){coverage.add("type/serializer unavailable");}
  try{for(Ingredient ingredient:recipe.getIngredients()){LinkedHashSet<String> alternatives=new LinkedHashSet<>();for(var stack:ingredient.getItems())if(!stack.isEmpty())alternatives.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());ingredients.add(List.copyOf(alternatives));}coverage.add("standard ingredient choices captured; quantities require serialized data");}catch(RuntimeException|LinkageError failure){coverage.add("standard inputs partially unavailable");}
  try{var result=recipe.getResultItem(server.registryAccess());if(!result.isEmpty())outputs.add(new RecipeCatalogue.Output(BuiltInRegistries.ITEM.getKey(result.getItem()).toString(),result.getCount()));else coverage.add("standard output empty; custom/dynamic output may exist");}catch(RuntimeException|LinkageError failure){coverage.add("standard output unavailable");}
  try{JsonElement json=Recipe.CODEC.encodeStart(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),recipe).getOrThrow();encoded=json.toString();coverage.add("public serialized recipe captured");
   var codec=RecipeCatalogue.codecItems(json,value->{var key=net.minecraft.resources.ResourceLocation.tryParse(value);return key!=null&&BuiltInRegistries.ITEM.containsKey(key)&&!value.equals("minecraft:air");},tag->{var key=net.minecraft.resources.ResourceLocation.tryParse(tag);if(key==null)return List.of();var values=BuiltInRegistries.ITEM.getTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,key));return values.map(set->set.stream().map(holderItem->BuiltInRegistries.ITEM.getKey(holderItem.value()).toString()).toList()).orElse(List.of());});
   if(ingredients.isEmpty())ingredients.addAll(codec.ingredients());else codecReferences=RecipeCatalogue.missingCodecInputs(ingredients,codec.ingredients());
   if(!codecReferences.isEmpty())coverage.add("additional serialized input references captured separately; consumption/catalyst semantics unknown");
   for(var output:codec.outputs())if(outputs.stream().noneMatch(existing->existing.item().equals(output.item())))outputs.add(output);if(codec.limited())coverage.add("serialized reference indexing budget reached; consult raw recipe");coverage.add("generic codec item fields only; custom semantics require raw data");
  }catch(RuntimeException|LinkageError failure){coverage.add("serializer/custom indexing unavailable; custom fields unknown");}
  return new RecipeCatalogue.Entry(id,type,serializer,ingredients,outputs,encoded,coverage.toString(),codecReferences);
 }
 public static String status(){RecipeCatalogue index=recipes;return "catalogue="+state+"; indexed_recipes="+(index==null?0:index.size())+"; "+ServerItemCatalog.status()+". Current loaded server registries only; no external JEI-only recipes or client tooltip assumptions.";}
 public static String items(String query,int offset,int requested){ItemCatalog index=ServerItemCatalog.current();if(index==null)return unavailable();String search=RecipeCatalogue.text(query,240);int count=RecipeCatalogue.limit(requested);var page=index.page(search,offset,count);if(page.total()==0){String hint=ItemHints.normalizeQuery(search);if(!hint.equals(search))page=index.page(hint,offset,count);}JsonObject out=RecipeCatalogue.page(page.total(),page.offset(),count);JsonArray rows=new JsonArray();for(ItemCatalog.Match match:page.items()){JsonObject row=new JsonObject();row.addProperty("id",match.id());row.addProperty("en",match.english());row.addProperty("ru",match.russian());var details=index.details(match.id()).orElseThrow();row.addProperty("description_en",RecipeCatalogue.text(details.englishDescription(),240));row.addProperty("description_ru",RecipeCatalogue.text(details.russianDescription(),240));row.addProperty("description_source",details.englishDescription().isBlank()&&details.russianDescription().isBlank()?"unavailable; no exact locale description/tooltip key; absence is not no behavior":"exact item translation key plus .description/.desc/.tooltip; long descriptions shortened");rows.add(row);}out.add("items",rows);out.addProperty("registered_total",index.size());out.addProperty("coverage","All current registered non-air item IDs. RU/EN label availability depends on loaded assets; descriptions are locale text, not a complete client tooltip or guarantee of runtime behavior.");return out.toString();}
 public static String recipes(String query,String direction,int offset,int limit){RecipeCatalogue index=recipes;if(index==null)return unavailable();ItemCatalog items=ServerItemCatalog.current();String search=RecipeCatalogue.text(query,240);Set<String> ids=items==null||search.isBlank()?Set.of():items.matchingIds(search);if(ids.isEmpty()&&items!=null&&!search.isBlank()){String hint=ItemHints.normalizeQuery(search);if(!hint.equals(search))ids=items.matchingIds(hint);}return index.page(search,direction,ids,offset,limit);}
 public static String recipe(String id,int offset,int limit){RecipeCatalogue index=recipes;return index==null?unavailable():index.details(id,offset,limit);}
 public static String serializedRecipe(String id,int offset,int limit){RecipeCatalogue index=recipes;return index==null?unavailable():index.serialized(id,offset,limit);}
 private static String unavailable(){JsonObject out=new JsonObject();out.addProperty("status",status());out.addProperty("available",false);out.addProperty("interpretation","Index incomplete/unavailable; do not infer missing items or recipes. Retry after indexing completes.");return out.toString();}
 static void persist(Path directory,long epoch,ItemCatalog items,RecipeCatalogue index)throws IOException{Files.createDirectories(directory);Path target=directory.resolve("registry-index.jsonl.gz"),temporary=Files.createTempFile(directory,"registry-index-",".tmp");
  try{try(BufferedWriter writer=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temporary)),StandardCharsets.UTF_8))){JsonObject header=new JsonObject();header.addProperty("format",1);header.addProperty("source","current_loaded_server_registries");header.addProperty("captured_at_epoch_millis",System.currentTimeMillis());header.addProperty("items",items.size());header.addProperty("recipes",index.size());writer.write(header.toString());writer.newLine();for(ItemCatalog.Details item:items.details()){cancelled(epoch);JsonObject row=new JsonObject();row.addProperty("kind","item");row.add("data",JSON.toJsonTree(item));writer.write(row.toString());writer.newLine();}for(RecipeCatalogue.Entry recipe:index.entries()){cancelled(epoch);JsonObject row=new JsonObject();row.addProperty("kind","recipe");row.add("data",JSON.toJsonTree(recipe));writer.write(row.toString());writer.newLine();}}
   synchronized(GameCatalogue.class){cancelled(epoch);try{Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}}
  }finally{Files.deleteIfExists(temporary);}
 }
 private static void cancelled(long epoch){if(epoch!=generation||Thread.currentThread().isInterrupted())throw new CancellationException("Catalogue generation changed");}
}
