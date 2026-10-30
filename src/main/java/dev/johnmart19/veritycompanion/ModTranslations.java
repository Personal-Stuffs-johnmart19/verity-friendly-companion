package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;

/** Standard Minecraft language JSON, also available to dedicated-server replies. */
final class ModTranslations {
 private static final Map<String,Map<String,String>> CACHE=new ConcurrentHashMap<>();
 private ModTranslations() {}
 private static Map<String,String> load(String locale){return CACHE.computeIfAbsent(locale,code->{
  try(InputStream input=ModTranslations.class.getResourceAsStream("/assets/verity_companion/lang/"+code+".json")){
   if(input==null)return Map.of();byte[] bytes=input.readNBytes(2_000_001);if(bytes.length>2_000_000)return Map.of();
   JsonObject json=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();Map<String,String> values=new HashMap<>();
   for(var entry:json.entrySet())if(entry.getValue().isJsonPrimitive()&&entry.getValue().getAsJsonPrimitive().isString())values.put(entry.getKey(),entry.getValue().getAsString());
   return Map.copyOf(values);
  }catch(IOException|RuntimeException invalid){return Map.of();}
 });}
 static String text(String language,String key,Object...args){
  String english=load("en_us").getOrDefault(key,key),pattern=load(Languages.locale(language)).getOrDefault(key,english);
  try{return format(pattern,args);}catch(IllegalFormatException invalid){try{return format(english,args);}catch(IllegalFormatException badFallback){return english;}}
 }
 private static String format(String pattern,Object[] args){if(args.length==0)return pattern;Object[] text=Arrays.stream(args).map(value->value instanceof Component component?component.getString():value).toArray();return String.format(Locale.ROOT,pattern,text);}
 static Component component(String key,Object...args){return Component.translatableWithFallback(key,load("en_us").getOrDefault(key,key),args);}
}
