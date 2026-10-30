package dev.johnmart19.veritycompanion;

import java.util.regex.Pattern;

/** Final reply presentation only; quest tools and saved identifiers remain unchanged. */
public final class QuestPresentation {
 private static final Pattern QUEST_ID=Pattern.compile("(?<![\\p{L}\\p{N}_:/.-])(?:0[xX][0-9a-fA-F]{16}|(?=[0-9a-fA-F]{0,15}[a-fA-F])[0-9a-fA-F]{16})(?![\\p{L}\\p{N}_/-]|[:.][\\p{L}\\p{N}_])");
 private static final Pattern EMPTY_WRAPPER=Pattern.compile("\\([ \\t]*\\)|\\[[ \\t]*\\]|`[ \\t]*`|\"[ \\t]*\"|“[ \\t]*”|«[ \\t]*»");
 private QuestPresentation(){}
 public static String clean(String text){
  if(text==null||text.isEmpty())return "";
  var ids=QUEST_ID.matcher(text);boolean removed=ids.find();String cleaned=removed?ids.replaceAll(""):text;
  // A decimal number has no evidence of being a quest key; preserve it unless 0x-prefixed.
  if(removed){String previous;do{previous=cleaned;cleaned=EMPTY_WRAPPER.matcher(cleaned).replaceAll("");}while(!cleaned.equals(previous));cleaned=cleaned.replaceAll("[ \\t]+([)\\]])","$1").replaceAll("([\\[(])[ \\t]+","$1");}
  return cleaned.replaceAll("[ \\t]{2,}"," ").replaceAll("[ \\t]+([,.;:!?])","$1").replaceAll("(?m)[ \\t]+$","").strip();
 }
}
