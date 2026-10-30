package dev.johnmart19.veritycompanion;
import java.util.*;
/** One request: mutations are deduplicated; live observations expire at each thinking round/change. */
final class ToolCallCache {
 private final Map<String,String> completed=new HashMap<>(),observed=new HashMap<>();
 void beginRound(){observed.clear();}
 String get(String key,boolean mutation){return (mutation?completed:observed).get(key);}
 void record(String key,boolean mutation,String result){
  if(mutation){completed.put(key,result);observed.clear();}
  else if(observed.size()<64)observed.put(key,result);
 }
 void uncertainMutation(){observed.clear();}
}
