package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Function;

/** Immutable public recipe data. Queries use reverse item indexes; no world or client access. */
public final class RecipeCatalogue {
 public record Output(String item,int count){}
 public record CodecItems(List<List<String>> ingredients,List<Output> outputs,boolean limited){}
 /** Only explicit item keys in input/output fields count. Fluid/chemical IDs are never guessed as items. */
 public static CodecItems codecItems(JsonElement json,Predicate<String> registeredItem){return codecItems(json,registeredItem,tag->List.of());}
 public static CodecItems codecItems(JsonElement json,Predicate<String> registeredItem,Function<String,List<String>> tagItems){List<List<String>> ingredients=new ArrayList<>();List<Output> outputs=new ArrayList<>();int[] visited={0};if(json.isJsonObject())for(var field:json.getAsJsonObject().entrySet()){
  if(++visited[0]>8192)break;
  String key=field.getKey().toLowerCase(Locale.ROOT);boolean input=Set.of("ingredient","ingredients","input","inputs","item_input","item_inputs").contains(key),output=Set.of("result","results","output","outputs","item_output","item_outputs").contains(key);if(!input&&!output)continue;
  JsonElement value=field.getValue();boolean separateGroups=output||key.equals("ingredients")||key.endsWith("inputs");Collection<JsonElement> groups=value.isJsonArray()&&separateGroups?value.getAsJsonArray().asList():List.of(value);
  for(JsonElement group:groups){if(visited[0]>8192)break;LinkedHashMap<String,Integer> found=new LinkedHashMap<>();collectItems(group,registeredItem,tagItems,found,visited,0,true);if(input&&!found.isEmpty())ingredients.add(List.copyOf(found.keySet()));if(output)for(var item:found.entrySet())outputs.add(new Output(item.getKey(),item.getValue()));}
 }return new CodecItems(List.copyOf(ingredients),List.copyOf(outputs),visited[0]>8192);}
 private static void collectItems(JsonElement value,Predicate<String> valid,Function<String,List<String>> tags,Map<String,Integer> result,int[] visited,int depth,boolean itemContext){if(++visited[0]>8192)return;if(depth>32){visited[0]=8193;return;}
  if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()){String id=value.getAsString();if(itemContext&&valid.test(id))result.putIfAbsent(id,1);}
  else if(value.isJsonArray())for(JsonElement child:value.getAsJsonArray()){if(visited[0]>8192)break;collectItems(child,valid,tags,result,visited,depth+1,itemContext);}
  else if(value.isJsonObject()){JsonObject object=value.getAsJsonObject();String id=null;for(String key:List.of("item","id"))if(object.has(key)&&object.get(key).isJsonPrimitive()&&object.getAsJsonPrimitive(key).isString()){String candidate=object.get(key).getAsString();if(valid.test(candidate))id=candidate;}
   boolean nonItem=object.has("fluid")||object.has("chemical")||object.has("gas")||object.has("infuse_type")||object.has("pigment")||object.has("slurry");
   if(!nonItem&&object.has("tag")&&object.get("tag").isJsonPrimitive()){for(String tagItem:tags.apply(object.get("tag").getAsString()))if(valid.test(tagItem))result.putIfAbsent(tagItem,1);}
   if(id!=null&&!nonItem){int count=1;try{if(object.has("count"))count=Math.max(1,object.get("count").getAsInt());}catch(RuntimeException malformed){}result.putIfAbsent(id,count);}
   if(!nonItem)for(var child:object.entrySet()){if(visited[0]>8192)break;String key=child.getKey();if(Set.of("ingredient","ingredients","input","inputs","output","outputs","item","items","children","alternatives","stack","item_stack").contains(key))collectItems(child.getValue(),valid,tags,result,visited,depth+1,key.equals("item"));}
  }
 }
 /** Keep complete codec groups that mention an item absent from standard inputs; never turn them into mandatory costs. */
 public static List<List<String>> missingCodecInputs(List<List<String>> standard,List<List<String>> serialized) {
  Set<String> indexed=new HashSet<>();for(List<String> group:standard)indexed.addAll(group);
  List<List<String>> references=new ArrayList<>();
  for(List<String> group:serialized)if(group.stream().anyMatch(item->!indexed.contains(item)))references.add(List.copyOf(group));
  return List.copyOf(references);
 }
 public record Entry(String id,String type,String serializer,List<List<String>> ingredients,List<Output> outputs,String encoded,String coverage,List<List<String>> codecInputReferences){
  public Entry{ingredients=ingredients.stream().map(List::copyOf).toList();outputs=List.copyOf(outputs);codecInputReferences=codecInputReferences.stream().map(List::copyOf).toList();}
  public Entry(String id,String type,String serializer,List<List<String>> ingredients,List<Output> outputs,String encoded,String coverage){this(id,type,serializer,ingredients,outputs,encoded,coverage,List.of());}
 }
 private final List<Entry> entries;
 private final Map<String,Entry> ids=new HashMap<>();
 private final Map<String,Set<String>> inputs=new HashMap<>(),outputs=new HashMap<>();
 public RecipeCatalogue(Collection<Entry> source){
  for(Entry entry:source)if(ids.putIfAbsent(entry.id(),entry)!=null)throw new IllegalArgumentException("Duplicate recipe ID: "+entry.id());
  entries=ids.values().stream().sorted(Comparator.comparing(Entry::id)).toList();
  for(Entry entry:entries){for(List<String> ingredient:entry.ingredients())for(String item:ingredient)inputs.computeIfAbsent(item,k->new HashSet<>()).add(entry.id());for(List<String> group:entry.codecInputReferences())for(String item:group)inputs.computeIfAbsent(item,k->new HashSet<>()).add(entry.id());for(Output output:entry.outputs())outputs.computeIfAbsent(output.item(),k->new HashSet<>()).add(entry.id());}
 }
 public int size(){return entries.size();}
 public List<Entry> entries(){return entries;}
 public Optional<Entry> get(String id){return Optional.ofNullable(ids.get(id));}
 public List<Entry> matching(String query,String direction,Set<String> itemIds){
  if(!Set.of("input","output","either").contains(direction))throw new IllegalArgumentException("direction must be input, output or either");
  String key=ItemHints.key(query);if(key.isBlank())return entries;
  Set<String> selected=new HashSet<>();for(String item:itemIds){if(!direction.equals("output"))selected.addAll(inputs.getOrDefault(item,Set.of()));if(!direction.equals("input"))selected.addAll(outputs.getOrDefault(item,Set.of()));}
  // Exact item IDs also work while locale indexing is unavailable.
  if(!direction.equals("output"))selected.addAll(inputs.getOrDefault(query,Set.of()));if(!direction.equals("input"))selected.addAll(outputs.getOrDefault(query,Set.of()));
  if(itemIds.isEmpty()&&!inputs.containsKey(query)&&!outputs.containsKey(query))for(Entry entry:entries)if(entry.id().toLowerCase(Locale.ROOT).contains(key)||entry.type().toLowerCase(Locale.ROOT).contains(key))selected.add(entry.id());
  return selected.stream().map(ids::get).sorted(Comparator.comparing(Entry::id)).toList();
 }
 static int limit(int requested){return Math.clamp(requested,1,8);}
 static String text(String input,int maximum){if(input==null)return "";int end=Math.min(input.length(),maximum);if(end<input.length()&&end>0&&Character.isHighSurrogate(input.charAt(end-1)))end--;return input.substring(0,end);}
 static JsonObject page(int total,int offset,int limit){int start=Math.clamp(offset,0,total);JsonObject out=new JsonObject();out.addProperty("total",total);out.addProperty("offset",start);out.addProperty("limit",limit);out.addProperty("next_offset",start+limit<total?start+limit:-1);return out;}
 public String page(String query,String direction,Set<String> itemIds,int offset,int requested){List<Entry> found=matching(query,direction,itemIds);int count=limit(requested),start=Math.clamp(offset,0,found.size());JsonObject out=page(found.size(),start,count);JsonArray rows=new JsonArray();
  for(Entry entry:found.subList(start,Math.min(found.size(),start+count))){JsonObject row=new JsonObject();row.addProperty("id",entry.id());row.addProperty("type",entry.type());row.addProperty("ingredient_groups",entry.ingredients().size());JsonArray results=new JsonArray();for(Output output:entry.outputs().stream().limit(4).toList()){JsonObject result=new JsonObject();result.addProperty("id",output.item());result.addProperty("count",output.count());results.add(result);}row.add("outputs",results);row.addProperty("outputs_total",entry.outputs().size());row.addProperty("coverage",text(entry.coverage(),220));rows.add(row);}
  out.add("recipes",rows);out.addProperty("interpretation","All current RecipeManager IDs indexed, including unsupported/custom rows. Missing standard item inputs/outputs do not prove no recipe. Ingredient alternatives are OR choices within a group, not extra costs. Use recipe_details for paged ingredients and serialized public data.");return out.toString();
 }
 public String details(String id,int offset,int requested){Entry entry=ids.get(id);if(entry==null)return "{\"error\":\"No current indexed recipe with that ID\"}";
  int standardTotal=entry.ingredients().stream().mapToInt(List::size).sum(),total=standardTotal+entry.codecInputReferences().stream().mapToInt(List::size).sum(),count=limit(requested),start=Math.clamp(offset,0,total),position=0;JsonObject out=page(total,start,count);out.addProperty("id",entry.id());out.addProperty("type",entry.type());out.addProperty("serializer",entry.serializer());out.addProperty("coverage",entry.coverage());JsonArray choices=new JsonArray();
  for(int group=0;group<entry.ingredients().size()&&position<start+count;group++)for(String item:entry.ingredients().get(group)){if(position>=start&&position<start+count){JsonObject choice=new JsonObject();choice.addProperty("ingredient_group",group);choice.addProperty("item",item);choice.addProperty("alternatives_in_group",entry.ingredients().get(group).size());choices.add(choice);}if(++position>=start+count)break;}
  JsonArray references=new JsonArray();position=standardTotal;
  for(int group=0;group<entry.codecInputReferences().size()&&position<start+count;group++)for(String item:entry.codecInputReferences().get(group)) {
   if(position>=start&&position<start+count){JsonObject reference=new JsonObject();reference.addProperty("codec_reference_group",group);reference.addProperty("item",item);reference.addProperty("source","serialized input field; consumption, quantity and catalyst semantics unknown");references.add(reference);}
   if(++position>=start+count)break;
  }
  out.add("ingredient_alternatives",choices);out.add("codec_input_references",references);out.addProperty("standard_alternatives_total",standardTotal);out.addProperty("codec_input_references_total",total-standardTotal);JsonArray results=new JsonArray();for(Output output:entry.outputs().stream().limit(8).toList()){JsonObject result=new JsonObject();result.addProperty("item",output.item());result.addProperty("count",output.count());results.add(result);}out.add("outputs",results);out.addProperty("outputs_total",entry.outputs().size());out.addProperty("serialized_chars",entry.encoded().length());out.addProperty("note","Counts, fluid/chemical inputs, chances, conditions and machine behavior require serialized recipe details. Standard ingredient groups are OR alternatives, not all required simultaneously. Codec input references are additional discoveries, possibly catalysts; do not add them as consumed ingredient costs or assume their AND/OR semantics.");return out.toString();
 }
 public String serialized(String id,int offset,int requested){Entry entry=ids.get(id);if(entry==null)return "{\"error\":\"No current indexed recipe with that ID\"}";String raw=entry.encoded();int count=Math.clamp(requested,64,2000),start=Math.clamp(offset,0,raw.length());if(start>0&&start<raw.length()&&Character.isLowSurrogate(raw.charAt(start)))start--;int end=Math.min(raw.length(),start+count);if(end<raw.length()&&end>start&&Character.isHighSurrogate(raw.charAt(end-1)))end--;JsonObject out=page(raw.length(),start,count);out.addProperty("next_offset",end<raw.length()?end:-1);out.addProperty("id",id);out.addProperty("fragment",raw.substring(start,end));out.addProperty("coverage",entry.coverage());return out.toString();}
}
