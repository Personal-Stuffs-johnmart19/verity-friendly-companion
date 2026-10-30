package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/** Server-reported block mutations, never a model's claim that a whole house is finished. */
public final class BuildOutcome {
 private static final Pattern BOUNDS=Pattern.compile("\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\.\\.\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*");
 private final String status,dimension,bounds;
 private final long placed,skipped,planned;
 private BuildOutcome(String status,long placed,long skipped,long planned,String dimension,String bounds){this.status=status;this.placed=placed;this.skipped=skipped;this.planned=planned;this.dimension=dimension;this.bounds=bounds;}
 public static Optional<BuildOutcome> parse(String text){
  if(text==null||text.length()>4096)return Optional.empty();
  try{
   JsonObject object=JsonParser.parseString(text).getAsJsonObject();String status=string(object,"status"),dimension=string(object,"dimension"),bounds=string(object,"bounds");
   if(!status.equals("completed")&&!status.equals("cancelled")&&!status.equals("timeout"))return Optional.empty();
   long placed=count(object,"placed"),skipped=count(object,"skipped"),planned=count(object,"planned");
   if(placed>planned||skipped>planned-placed||!dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")||dimension.length()>128)return Optional.empty();
   if(placed>0){
    var match=BOUNDS.matcher(bounds);if(!match.matches())return Optional.empty();int[] coordinates=new int[6];for(int i=0;i<6;i++)coordinates[i]=Integer.parseInt(match.group(i+1));
    for(int i=0;i<3;i++)if(coordinates[i]>coordinates[i+3])return Optional.empty();
    bounds=coordinates[0]+","+coordinates[1]+","+coordinates[2]+" .. "+coordinates[3]+","+coordinates[4]+","+coordinates[5];
   }else bounds="";
   return Optional.of(new BuildOutcome(status,placed,skipped,planned,dimension,bounds));
  }catch(RuntimeException invalid){return Optional.empty();}
 }
 private static String string(JsonObject object,String key){JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Missing string");return value.getAsString();}
 private static long count(JsonObject object,String key){JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Missing count");long count=new BigDecimal(value.getAsString()).longValueExact();if(count<0)throw new IllegalArgumentException("Negative count");return count;}
 public String reply(boolean ru){return reply(ru?"ru":"en");}
 public String reply(String language){
  String reason=switch(status){case "cancelled"->ModTranslations.text(language,"verity_companion.build_outcome.construction_cancelled");case "timeout"->ModTranslations.text(language,"verity_companion.build_outcome.construction_timed_out");default->"";};
  String skippedText=skipped>0?ModTranslations.text(language,"verity_companion.build_outcome.skipped_count",skipped):"";
  if(placed==0)return reason+ModTranslations.text(language,"verity_companion.build_outcome.no_blocks_changed_the_build_is_not")+skippedText;
  boolean partial=!status.equals("completed")||skipped>0||placed<planned;
  String applied=partial?ModTranslations.text(language,"verity_companion.build_outcome.partial_count",placed):ModTranslations.text(language,"verity_companion.build_outcome.completed_count",placed);
  return reason+applied+skippedText+(partial?ModTranslations.text(language,"verity_companion.build_outcome.planned_count",planned):"")+ModTranslations.text(language,"verity_companion.build_outcome.location",dimension,bounds);
 }
}
