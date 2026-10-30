package dev.johnmart19.veritycompanion;
import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
/** Same durable recovery policy as relationships; legacy array format is retained. */
public final class ConversationMemory {
 static void validate(JsonArray messages){
  if(messages==null||messages.size()>256)throw new IllegalArgumentException("Invalid conversation history");
  for(JsonElement value:messages){if(!value.isJsonObject())throw new IllegalArgumentException("Invalid history entry");JsonObject m=value.getAsJsonObject();
   if(!m.has("role")||!m.has("content")||!m.get("role").isJsonPrimitive()||!m.get("content").isJsonPrimitive()||!m.get("content").getAsJsonPrimitive().isString())throw new IllegalArgumentException("Invalid history fields");
   if(!java.util.Set.of("user","assistant").contains(m.get("role").getAsString())||m.get("content").getAsString().length()>16000)throw new IllegalArgumentException("Invalid history role/length");
  }
 }
 static JsonArray load(Path path,Gson gson)throws IOException{return SafeJsonStore.load(path,gson,JsonArray.class,JsonArray::new,ConversationMemory::validate);}
 static void save(Path path,Gson gson,JsonArray history)throws IOException{SafeJsonStore.save(path,gson,JsonArray.class,history,ConversationMemory::validate);}
}
