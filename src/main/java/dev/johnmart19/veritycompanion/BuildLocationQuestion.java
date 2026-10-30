package dev.johnmart19.veritycompanion;
import java.util.*;
final class BuildLocationQuestion {
 static boolean matches(String text,String previousAssistant){
  String s=BuildLanguage.normalized(text).replaceAll("[.!?]+$","").trim();
  if(Set.of("где дом","где построен дом","где ты построил дом","где ты построила дом","где постройка","где эта постройка","where is the house","where did you build it","where is the building").contains(s))return true;
  String prior=previousAssistant.toLowerCase(Locale.ROOT);
  return Set.of("где","where","where is it").contains(s)&&prior.matches("(?s).*(?:дом|построй|постро|house|building|built).*" );
 }
}
