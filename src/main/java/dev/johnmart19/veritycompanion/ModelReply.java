package dev.johnmart19.veritycompanion;
import com.google.gson.*;
/** Validate the complete batch before any action can mutate the world. */
public final class ModelReply {
 static JsonObject parse(String raw){
  if(raw==null||raw.length()>262144)throw new IllegalArgumentException("Model reply exceeds limit");
  JsonObject output=JsonParser.parseString(raw).getAsJsonObject();
  if(!output.has("reply")||!output.get("reply").isJsonPrimitive()||!output.getAsJsonPrimitive("reply").isString()||output.get("reply").getAsString().length()>16000)throw new IllegalArgumentException("Invalid model reply");
  if(!output.has("actions")||!output.get("actions").isJsonArray())throw new IllegalArgumentException("Missing action list");
  JsonArray actions=output.getAsJsonArray("actions");if(actions.size()>16)throw new IllegalArgumentException("Too many actions in one reply");
  for(JsonElement element:actions){JsonObject a=element.getAsJsonObject();
   if(!a.has("tool")||!a.get("tool").isJsonPrimitive()||!a.getAsJsonPrimitive("tool").isString()||!a.get("tool").getAsString().matches("[a-z_]{1,64}"))throw new IllegalArgumentException("Invalid tool name");
   if(!CompanionToolCatalog.VALUES.contains(a.get("tool").getAsString()))throw new IllegalArgumentException("Unsupported tool name");
   if(!a.has("args")||!a.get("args").isJsonPrimitive()||!a.getAsJsonPrimitive("args").isString()||!JsonParser.parseString(a.get("args").getAsString()).isJsonObject())throw new IllegalArgumentException("Invalid tool arguments");
  }
  JsonElement emotion=output.get("emotion");output.addProperty("emotion",EmotionPolicy.normalize(emotion!=null&&emotion.isJsonPrimitive()&&emotion.getAsJsonPrimitive().isString()?emotion.getAsString():"neutral"));
  return output;
 }
 static String actionKey(String tool,JsonObject args){return tool+" "+canonical(args);}
 static String canonical(JsonElement value){if(value.isJsonObject()){java.util.SortedMap<String,String> parts=new java.util.TreeMap<>();for(var e:value.getAsJsonObject().entrySet())parts.put(e.getKey(),canonical(e.getValue()));return parts.toString();}if(value.isJsonArray()){java.util.List<String> parts=new java.util.ArrayList<>();for(var v:value.getAsJsonArray())parts.add(canonical(v));return parts.toString();}return value.toString();}
 static boolean mutates(String tool,JsonObject args){if(tool.equals("persona")&&args.keySet().stream().noneMatch(java.util.Set.of("gender","role","tts","voice","language")::contains))return false;if(tool.equals("companion")&&args.has("action")&&"find".equals(args.get("action").getAsString()))return false;return java.util.Set.of("swap_inventory_slots","set_face","set_held_unbreakable","clear_nearby_mobs","help_survive","apply_effect","remove_effect","persona","run_command","companion","interact_block","give_item","enchant_item","clear_item_enchantments","rename","build_structure","build_schematic","build_blocks","undo_build").contains(tool);}
}
