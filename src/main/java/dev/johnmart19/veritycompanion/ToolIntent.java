package dev.johnmart19.veritycompanion;
import java.util.Locale;
/** Human gifting and enchanting intent with authored local request vocabulary. */
final class ToolIntent {
 // Only human messages enter this policy. Model text must never create consent.
 static String normalized(String request){return HumanIntentLexicon.policyText(request);}
 /** Remove only the configured companion name at the start of a human message. */
 static String unaddress(String request,String alias){
  String text=request==null?"":request.trim();String name=alias==null?"":alias.trim();
  if(name.isEmpty()||text.length()<name.length()||!text.regionMatches(true,0,name,0,name.length()))return text;
  if(text.length()==name.length())return "";
  char next=text.charAt(name.length());
  if(Character.isLetterOrDigit(next)||next=='_')return text;
  return text.substring(name.length()).replaceFirst("^[\\s,!:;—–-]+","");
 }
 static boolean excludedGiftText(String request){String s=normalized(request);
  return s.isEmpty()||s.length()>512||s.matches("(?s).*[\"«»„“”`\\n\\r].*")||s.startsWith("'")||s.endsWith("'")||s.matches("(?s).*(?:^| )'[^']+'.*")
   ||s.matches("(?s).*(?:^|[^\\p{L}])(?:не|нет|без|not|no|never|don't|dont|do not|stop|cancel|отмена|отмени|перестань)(?:$|[^\\p{L}]).*")
   ||s.matches("(?s).*(?:^|[^\\p{L}])(?:расскажи|рассказать|объясни|объяснить|почему|зачем|как|сколько|что|где|когда|информация|информацию|рецепт|рецепты|совет|советы|совета|ответ|ответы|описание|историю|шутку|вопрос|значит|написал|сказал|сказала|цитата|переведи|перевод|tell|explain|why|how|what|where|when|information|recipe|recipes|advice|answer|answers|description|story|joke|question|news|said|says|quote|translate|translation|example)(?:$|[^\\p{L}]).*")
   ||s.matches("(?s).*(?:^|[^\\p{L}])(?:он|она|они|he|she|they)(?:$|[^\\p{L}]).*");
 }
 static boolean gifting(String request){String s=normalized(request);if(excludedGiftText(s))return false;
  s=s.replaceAll("^(?:(?:а|ну|слушай|эй|hey|well|привет|хай|hi|hello)[,! ]+)+","");
  s=s.replaceAll("^(?:верити[,! ]+|verity[,! ]+|пожалуйста[,! ]+|please[,! ]+)+","");
  if(s.matches("(?:дай|дайте|выдай|выдайте|подари|подарите|скинь|брось|бросай|выброси|выбрось|выплюнь|выплюни|положи|добавь|give|grant|gift|drop|spit|throw|put|add) .+"))return true;
  if(s.matches("(?:можешь|можете|могла бы|мог бы|can you|could you|would you) .*(?:дать|выдать|подарить|give|grant|gift|drop) .+"))return true;
  if(s.matches("(?:мне бы|мне нужно|мне нужна|мне нужен|мне нужны|хочу|я хочу|можно|можно мне|можно ли мне|i want|i need|i would like|i'd like|can i have|could i have|may i have|could i get|can i get|please give me) .+")){
   String item=s.replaceFirst("^(?:можно ли мне|можно мне|мне бы|мне нужно|мне нужна|мне нужен|мне нужны|я хочу|хочу|можно|i would like|i'd like|i want|i need|can i have|could i have|may i have|could i get|can i get|please give me) ","");
   return nounPhrase(item);
  }
  if(giftDetail(s)&&hasQuantity(s)&&hasItemName(s))return true;
  return s.matches("(?:я (?:прям |прямо |совсем |полностью |очень |сильно |так )*(?:голодный|голодная|голоден|голодна)|(?:i am|i'm|im) (?:really |very |so |completely |totally )*(?:hungry|starving))[.!? ]*");
 }
 // A deliberately small grammar for item/count clarifications, not arbitrary chat.
 static boolean hasItemName(String request){String rest=normalized(request).replaceAll("(?iu)(?<![\\p{L}])(?:[0-9]+|стак(?:а|ов)?|штук(?:а|и)?|шт|пожалуйста|один|одну|одно|два|две|три|пару|немного|a|an|the|of|stack(?:s)?|please|one|two|three|some)(?![\\p{L}])","").replaceAll("[\\s,.!?]+","");return rest.matches(".*[\\p{L}].*");}
 static boolean hasQuantity(String request){return normalized(request).matches("(?s).*(?:[0-9]|(?:^| )стак(?:а|ов)?(?:$|[ ,.!?])|(?:^| )stack(?:s)?(?:$|[ ,.!?])|(?:^| )(?:один|одну|одно|два|две|три|пару|немного|one|two|three|some)(?:$|[ ,.!?])).*");}
 static boolean giftDetail(String request){String s=normalized(request);
  return knownGiftDetail(s)||(nounPhrase(s)&&s.matches("(?s).*(?:[0-9]|:|(?:^| )стак(?:а|ов)?(?:$|[ ,.!?])|(?:^| )stack(?:s)?(?:$|[ ,.!?])|(?:^| )(?:один|одну|одно|два|две|три|пару|немного|one|two|three|some)(?:$|[ ,.!?])).*"));
 }
 private static boolean nounPhrase(String request){String s=normalized(request).replaceAll("[,!.?]"," ").trim();
  return !excludedGiftText(s)&&!s.isEmpty()&&s.length()<=120&&s.split("\\s+").length<=12
   &&s.matches("[\\p{L}\\p{N}_:/ -]+")
   &&!s.matches("(?s).*(?:^|[^\\p{L}])(?:я|ты|мы|вы|i|you|we|привет|спасибо|пока|hello|hi|thanks|thank|bye|погода|weather|пошел|пошла|иду|go|going|think|feel|know|see|to|знать|узнать|поговорить|спать|домой|помощь|help)(?:$|[^\\p{L}]).*")
   &&!s.matches("(?s).*\\p{L}{2,}ть(?:$|[^\\p{L}]).*");
 }
 private static boolean knownGiftDetail(String request){String s=normalized(request).replaceAll("[,!.?]"," ").trim();if(s.isEmpty()||s.length()>120||excludedGiftText(s))return false;
  String[] words=s.split("\\s+");if(words.length>12)return false;
  for(String word:words)if(!word.matches("(?:[1-9][0-9]{0,3}|[a-z0-9_]+:[a-z0-9_/]+|стак(?:а|ов)?|штук(?:и)?|штуку|шт|один|одно|одну|два|две|три|четыре|пять|десять|пару|немного|побольше|еще|мне|пожалуйста|золот(?:ое|ые|ых|ой|ого)|зачарованн(?:ое|ые|ых|ого)|алмаз(?:ы|ов|а)?|изумруд(?:ы|ов|а)?|желез(?:о|а)|золот(?:о|а)|незерит(?:а|овый|овую|овое)?|яблок(?:о|а|и)?|ед(?:а|ы|у)|хлеб(?:а)?|стейк(?:и|ов|а)?|мяс(?:о|а)|морков(?:ь|и)|картошк(?:а|и|у)|печень(?:е|я)|рыб(?:а|ы|у)|уг(?:оль|ля)|кам(?:ень|ня)|дерев(?:о|а)|доск(?:и|а|ок)|меч(?:а)?|кирк(?:а|у|и)|топор(?:а)?|брон(?:я|ю|и)|факел(?:ы|ов)?|a|an|the|of|some|more|please|me|one|two|three|four|five|ten|stack|stacks|pieces|golden|enchanted|apple|apples|food|bread|steak|steaks|meat|carrot|carrots|potato|potatoes|fish|diamond|diamonds|emerald|emeralds|iron|gold|netherite|coal|stone|wood|planks|sword|pickaxe|axe|armor|torches)"))return false;
  return true;
 }
 static boolean giftAffirmation(String request){return HumanIntentLexicon.affirmation(request)||normalized(request).matches("(?:да|ага|угу|конечно|давай|давай мне|да пожалуйста|да, пожалуйста|yes|yeah|yep|sure|yes please|yes, please|please do)[.! ]*");}
 static boolean ground(String request){String s=normalized(request);return !excludedGiftText(s)
  &&!s.matches("(?s).*(?:инвентар|inventory).*")
  &&s.matches("(?s).*(?:^|[^\\p{L}])(?:на землю|на пол|под ноги|брось|бросай|выброси|выбрось|выплюнь|выплюни|скинь|drop|spit|throw|on the ground|on the floor)(?:$|[^\\p{L}]).*");}
 static boolean enchanting(String request){String s=normalized(request);if(excludedGiftText(s))return false;
  s=s.replaceFirst("^(?:верити|verity)[,! ]+","").replaceFirst("^(?:пожалуйста|please)[,! ]+","");
  boolean polite=s.matches("(?:можешь|можете|могла бы|мог бы|can you|could you|would you) .+");
  s=s.replaceFirst("^(?:можешь|можете|могла бы|мог бы|can you|could you|would you) ","");
  if(s.matches("(?:зачаровать|enchant) .+"))return true;
  if(s.matches("(?:зачаруй|зачаруйте|наложи чары|enchant) .+"))return true;
  if(s.contains("?")&&!polite)return false;
  return s.matches("(?:примени|наложи|добавь|сделай|apply|add|make|put|set) .+")
   &&s.matches("(?s).*(?:шелков[\\p{L}]*|касани[\\p{L}]*|удач[\\p{L}]*|острот[\\p{L}]*|эффективност[\\p{L}]*|прочност[\\p{L}]*|починк[\\p{L}]*|защит[\\p{L}]*|добыч[\\p{L}]*|отдач[\\p{L}]*|заговор огня|fortune|silk touch|silk_touch|sharpness|efficiency|unbreaking|mending|protection|looting|knockback|fire aspect|зачар[\\p{L}]*|enchant[\\p{L}]*|чары).*" );
 }
}
