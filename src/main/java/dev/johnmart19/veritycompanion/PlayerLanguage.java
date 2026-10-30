package dev.johnmart19.veritycompanion;
/** Explicit language is sticky even when a request contains names in another language. */
final class PlayerLanguage {
 static String mode(String value){return Languages.mode(value,false);}
 static String choose(String mode,String current,String input){
  return choose(mode,current,input,null);
 }
 static String choose(String mode,String current,String input,String clientLocale){
  String selected=mode(mode);if(!selected.equals("auto"))return selected;
  if(Languages.knownClientLocale(clientLocale))return Languages.fromClientLocale(clientLocale);
  String previous;try{previous=Languages.normalize(current);}catch(IllegalArgumentException invalid){previous="en";}
  String text=input==null?"":input;
  if(text.codePoints().anyMatch(c->c>=0x3040&&c<=0x30ff))return "ja";
  if(text.codePoints().anyMatch(c->c>=0xac00&&c<=0xd7af))return "ko";
  if(text.codePoints().anyMatch(c->c>=0x4e00&&c<=0x9fff))return previous.equals("zh_tw")?previous:"zh";
  if(text.codePoints().anyMatch(c->c>=0x590&&c<=0x5ff))return "he";
  if(text.codePoints().anyMatch(c->c>=0x600&&c<=0x6ff))return "ar";
  if(text.codePoints().anyMatch(c->c>=0x900&&c<=0x97f))return "hi";
  if(text.codePoints().anyMatch(c->c>=0x370&&c<=0x3ff))return "el";
  if(text.codePoints().anyMatch(c->c>=0x400&&c<=0x4ff))return java.util.Set.of("ru","bg","sr").contains(previous)?previous:"ru";
  return previous;
 }
}
