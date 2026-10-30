package dev.johnmart19.veritycompanion;
import java.util.*;
import java.util.regex.*;

/** Small human-only personal controls. Model text and quoted instructions never open consent. */
final class CompanionChatIntent {
 record Change(String field,String value){}
 private static final int FLAGS=Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE;
 private CompanionChatIntent(){}
 static String request(String human){
  if(human==null||human.length()>512)return "";
  String s=human.strip().replaceFirst("(?iu)^(?:please|пожалуйста)[,! ]+","");
  if(s.matches("(?s).*[\"«»„“”`\\n\\r].*")||s.startsWith("'")||s.endsWith("'"))return "";
  boolean polite=s.matches("(?iu)^(?:can you|could you|would you|можешь|можете|мог бы ты|могла бы ты)\\s+.*");
  if(s.contains("?")&&!polite)return "";
  s=s.replaceFirst("(?iu)^(?:can you|could you|would you|можешь|можете|мог бы ты|могла бы ты)\\s+","");
  s=s.replaceFirst("(?iu)^(?:please|пожалуйста)[,! ]+","").replaceFirst("(?iu)[,! ]+(?:please|пожалуйста)[.!?]*$","");
  s=s.replaceFirst("[.!?]+$","").strip();
  if(polite){
   int space=s.indexOf(' ');String verb=space<0?s:s.substring(0,space);
   String imperative=switch(verb.toLowerCase(Locale.ROOT)){
    case "подойти"->"подойди";case "следовать"->"следуй";case "идти"->"иди";case "остановиться"->"остановись";case "ждать"->"жди";
    case "создать"->"создай";case "призвать"->"призови";case "включить"->"включи";case "выключить"->"выключи";case "отключить"->"отключи";
    case "говорить"->"говори";case "отвечать"->"отвечай";case "стать"->"стань";case "быть"->"будь";case "выбрать"->"выбери";
    case "перестать"->"перестань";case "изменить"->"измени";case "использовать"->"используй";case "поменять"->"поменяй";case "сменить"->"смени";case "переименовать"->"переименуй";case "называть"->"называй";default->verb;
   };s=imperative+(space<0?"":s.substring(space));
  }
  return s.replaceFirst("(?iu)^теперь (?=(?:будь|стань|говори|отвечай) )","");
 }
 static Optional<Change> parse(String human){
  String text=request(human),s=text.toLowerCase(Locale.ROOT).replace('ё','е');if(s.isEmpty())return Optional.empty();
  if(s.matches("(?:говори|отвечай|пиши) (?:по русски|по-русски|на русском|на русском языке)|(?:speak|reply|respond|write) (?:in )?russian|change (?:your )?(?:reply )?language to russian|(?:смени|поменяй|измени) (?:свой )?язык на русский"))return change("language","ru");
  if(s.matches("(?:говори|отвечай|пиши) (?:по английски|по-английски|на английском|на английском языке)|(?:speak|reply|respond|write) (?:in )?english|change (?:your )?(?:reply )?language to english|(?:смени|поменяй|измени) (?:свой )?язык на английский"))return change("language","en");
  if(s.matches("(?:выключи|отключи|убери) (?:озвучку|голос|tts)|(?:disable|turn off|switch off) (?:tts|voice|speech)|(?:stop speaking|не озвучивай)"))return change("tts","false");
  if(s.matches("(?s).*(?<!\\p{L})(?:не|нет|never|not|don't|dont|cancel|отмена|если|if)(?!\\p{L}).*"))return Optional.empty();
  if(s.matches("(?:включи|верни) (?:озвучку|голос|tts)|(?:enable|turn on|switch on) (?:tts|voice|speech)|speak aloud"))return change("tts","true");
  if(s.matches("(?:(?:смени|измени|поменяй) (?:свой )?пол на женский|change (?:your )?gender to female)"))return change("gender","female");
  if(s.matches("(?:(?:смени|измени|поменяй) (?:свой )?пол на мужской|change (?:your )?gender to male)"))return change("gender","male");
  if(s.matches("(?:будь|стань|теперь ты) (?:девушкой|девочкой|женщиной|девушка|девочка)|(?:be|become) (?:a )?(?:girl|woman)|(?:говори|отвечай) (?:в женском роде|как девушка)"))return change("gender","female");
  if(s.matches("(?:будь|стань|теперь ты) (?:парнем|мальчиком|мужчиной|парень|мальчик)|(?:be|become) (?:a )?(?:boy|man)|(?:говори|отвечай) (?:в мужском роде|как парень)"))return change("gender","male");
  if(s.matches("(?:используй нейтральный род|говори нейтрально|use neutral gender|(?:смени|измени|поменяй) (?:свой )?пол на нейтральный|change (?:your )?gender to neutral)"))return change("gender","neutral");
  if(s.matches("(?:будь|стань) (?:защитником|защитницей|стражем|хранителем)(?: деревни)?|(?:be|become) (?:a )?(?:village )?(?:guardian|protector)"))return change("role","protector");
  if(s.matches("(?:будь|стань) (?:аниме девочкой|аниме девушкой)|(?:be|become) (?:an? )?anime (?:girl|companion)"))return change("role","anime");
  if(s.matches("(?:будь|стань) (?:шариком|смайликом)|(?:be|become) (?:a )?(?:smiley|orb)"))return change("role","orb");
  Matcher voice=Pattern.compile("^(?:(?:выбери|используй|поставь|смени|поменяй) голос(?: на)?|(?:choose|use|select|set|change) (?:your )?voice(?: to)?) ([a-z]+)$",FLAGS).matcher(text);
  if(voice.matches())return change("voice",voice.group(1));
  Matcher name=Pattern.compile("^(?:теперь тебя зовут|называй себя|зови себя|переименуй себя в|call yourself|rename yourself to|your name is now) ([\\p{L}\\p{N} _-]{1,24})$",FLAGS).matcher(text);
  if(name.matches()){
   String label=name.group(1).strip();
   for(String tail:label.split("(?iu)\\s+(?:и|and)\\s+|,\\s*"))if(parse(tail).isPresent())return Optional.empty();
   return change("name",label);
  }
  return Optional.empty();
 }
 private static Optional<Change> change(String field,String value){return Optional.of(new Change(field,value));}
 static boolean authorizes(String human,String field,String value){
  var direct=parse(human);if(direct.isPresent())return direct.get().field().equals(field)&&direct.get().value().equalsIgnoreCase(value);
  String safe=request(human);if(safe.isEmpty())return false;
  var parts=safe.split("(?iu)\\s+(?:и|and)\\s+|,\\s*");if(parts.length<2||parts.length>4)return false;
  boolean matched=false;for(String part:parts){var change=parse(part);if(change.isEmpty())return false;if(change.get().field().equals(field)){if(!change.get().value().equalsIgnoreCase(value))return false;matched=true;}}
  return matched;
 }
}
