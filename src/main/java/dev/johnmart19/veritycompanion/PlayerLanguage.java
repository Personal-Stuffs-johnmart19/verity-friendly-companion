package dev.johnmart19.veritycompanion;
import java.util.Locale;
/** Explicit language is sticky even when a request contains names in another language. */
final class PlayerLanguage {
 static String mode(String value){String mode=value==null?"auto":value.strip().toLowerCase(Locale.ROOT);if(!java.util.Set.of("auto","en","ru").contains(mode))throw new IllegalArgumentException("en / ru / auto");return mode;}
 static String choose(String mode,String current,String input){
  String selected=mode(mode);if(!selected.equals("auto"))return selected;
  String text=input==null?"":input;if(text.matches("(?s).*[а-яА-ЯёЁ].*"))return "ru";
  if(text.matches("(?s).*[a-zA-Z].*"))return "en";
  return "ru".equals(current)?"ru":"en";
 }
}
