package dev.johnmart19.veritycompanion;

import java.util.Optional;

/** Fast path for a single explicitly named item. Preserve every modifier in its name. */
final class ItemRequestPolicy {
 static Optional<String> plainNoun(String request){
  if(!ToolIntent.gifting(request)||ToolIntent.hasQuantity(request))return Optional.empty();
  String s=ToolIntent.normalized(request).replaceFirst("^(?:(?:хай|привет|hi|hello|hey|ну|а|пожалуйста|please)[,! ]+)+","");
  s=s.replaceFirst("^(?:можешь|можете|мог бы|могла бы|can you|could you|would you)\\s+","");
  String prefix="^(?:дай|дайте|выдай|выдайте|подари|подарите|дать|выдать|подарить|give|grant|gift)(?:\\s+мне|\\s+me)?\\s+";
  if(!s.matches(prefix+".+"))return Optional.empty();
  s=s.replaceFirst(prefix,"").replaceFirst("^(?:пожалуйста|please)\\s+","").replaceFirst("[,!. ]*(?:пожалуйста|please)[!. ]*$","").replaceAll("[!.]+$","").strip();
  if(s.isBlank()||s.length()>120||s.split("\\s+").length>12||s.matches("(?s).*(?:^|[^\\p{L}])(?:и|или|затем|потом|вместо|кроме|and|or|then|instead|except)(?:$|[^\\p{L}]).*")||!s.matches("[\\p{L}\\p{N}_:/ -]+"))return Optional.empty();
  return Optional.of(s);
 }
 static int count(String request,int proposed,int itemStackSize){return plainNoun(request).isPresent()&&itemStackSize==1?1:proposed;}
}
