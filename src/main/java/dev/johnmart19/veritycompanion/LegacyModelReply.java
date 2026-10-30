package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.util.*;

/** Narrow shipped-prompt compatibility. Every result still passes the game action validator. */
final class LegacyModelReply {
 private static final Map<String,String> ALIASES=Map.of("get_coords","inspect_world","get_dimension","inspect_world","get_biome","inspect_world","get_inventory","inspect_world","get_own_coords","companion_status","get_nearby_entities","nearby_mobs","get_block_player_is_looking_at","player_location");
 static JsonObject normalize(JsonObject parsed){
  if(parsed.has("reply"))return parsed;
  if(!parsed.has("message")||!parsed.get("message").isJsonPrimitive()||!parsed.getAsJsonPrimitive("message").isString()||!parsed.has("actions")||!parsed.get("actions").isJsonArray()||parsed.getAsJsonArray("actions").size()>16)throw new IllegalArgumentException("Invalid legacy reply");
  JsonArray actions=new JsonArray();
  for(JsonElement element:parsed.getAsJsonArray("actions")){
   JsonObject action=element.getAsJsonObject();if(!action.keySet().equals(Set.of("action","args"))||!action.get("action").isJsonPrimitive()||!action.getAsJsonPrimitive("action").isString()||!action.get("args").isJsonObject())throw new IllegalArgumentException("Invalid legacy action");
   String name=action.get("action").getAsString(),tool;JsonObject args=action.getAsJsonObject("args"),converted=new JsonObject();
   if(ALIASES.containsKey(name)){empty(args);tool=ALIASES.get(name);}
   else switch(name){
    case "get_nearest_village"->{empty(args);tool="locate_structure";converted.addProperty("query","village");}
    case "return_to_player"->{empty(args);tool="companion";converted.addProperty("action","come");}
    case "give_gift","drop_item"->{
     if(!Set.of("item_id","count").containsAll(args.keySet())||!args.has("item_id")||!args.get("item_id").isJsonPrimitive()||!args.getAsJsonPrimitive("item_id").isString())throw new IllegalArgumentException("Invalid legacy item");
     String item=args.get("item_id").getAsString();if(item.isBlank()||item.length()>256)throw new IllegalArgumentException("Invalid legacy item");
     tool="give_item";converted.addProperty("id",item);converted.addProperty("delivery",name.equals("give_gift")?"inventory":"ground");
     if(args.has("count")){JsonElement count=args.get("count");if(!count.isJsonPrimitive()||!count.getAsJsonPrimitive().isNumber()||!count.toString().matches("[0-9]+")||count.getAsBigDecimal().signum()<1)throw new IllegalArgumentException("Invalid legacy count");converted.add("count",count.deepCopy());}
    }
    case "answer"->{empty(args);continue;}
    default->throw new IllegalArgumentException("Unsupported legacy action");
   }
   JsonObject normalized=new JsonObject();normalized.addProperty("tool",tool);normalized.add("args",converted);actions.add(normalized);
  }
  JsonObject result=new JsonObject();result.add("reply",parsed.get("message").deepCopy());result.add("actions",actions);JsonElement emotion=parsed.get("emotion");result.addProperty("emotion",emotion!=null&&emotion.isJsonPrimitive()&&emotion.getAsJsonPrimitive().isString()?emotion.getAsString():"neutral");return result;
 }
 private static void empty(JsonObject args){if(!args.isEmpty())throw new IllegalArgumentException("Unexpected legacy arguments");}
}
