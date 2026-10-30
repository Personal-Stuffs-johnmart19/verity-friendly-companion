package dev.johnmart19.veritycompanion;
import java.util.Locale;
/** Building consent comes from the current human imperative, never assistant history. */
public final class BuildLanguage {
 private BuildLanguage(){}
 public static String normalized(String text){return HumanIntentLexicon.buildText(text);}
 private static String imperative(String text){return normalized(text).replaceFirst("^(?:(?:пожалуйста|please|ну|а|давай|слушай)[ ,!]+)+","");}
 private static boolean excluded(String text){String t=normalized(text);return t.matches("(?s).*(?:если.*(?:скажу|подтвержу|разрешу)|когда.*(?:скажу|подтвержу|разрешу)|после (?:моего |подтверждения)|if i (?:say|confirm|allow)|when i (?:say|confirm|allow)).*")||t.isBlank()||t.length()>1024||t.matches("(?s).*[\\n\\r].*")||t.matches("(?s).*(?:не (?:надо|нужно|строй|строить|постр\\p{L}*|делай|сделай|добав\\p{L}*|удал\\p{L}*|убир\\p{L}*|сноси)|не хочу|не надо|don't |do not |never |stop building|cancel building).*")||t.matches("(?s).*(?:чуж\\p{L}*|игрок\\p{L}*|другого игрока|other players?|another player|someone else's).*");}
 private static boolean target(String text){return text.matches("(?s).*(?<![\\p{L}])(?:замок|замка|замке|замки|замков|мастерск\\p{L}*|castle|workshop|skyscraper|apartment|outpost|watchtower|warehouse|library|barracks|greenhouse|stable|inn|factory|cottage|небоскреб\\p{L}*|аванпост\\p{L}*|застав\\p{L}*|склад\\p{L}*|библиотек\\p{L}*|казарм\\p{L}*|теплиц\\p{L}*|конюшн\\p{L}*|гостиниц\\p{L}*|фабрик\\p{L}*|завод\\p{L}*|дом\\p{L}*|домик\\p{L}*|постройк\\p{L}*|здани\\p{L}*|башн\\p{L}*|мост\\p{L}*|платформ\\p{L}*|стен\\p{L}*|крыш\\p{L}*|окн\\p{L}*|двер\\p{L}*|лестниц\\p{L}*|фундамент\\p{L}*|пол|блок\\p{L}*|house|home|building|structure|tower|bridge|platform|wall|roof|window|windows|door|doors|stairs|floor|blocks?)(?![\\p{L}]).*");}
 public static boolean isBuild(String text){String t=imperative(text);if(excluded(t))return false;
  if(t.matches("(?:построй|постройте|строй|возведи|сооруди|build|construct|erect) .+"))return true;
  return t.matches("(?:сделай|сделайте|создай|создайте|поставь|размести|make|create|place) .+")&&target(t);
 }
 public static boolean isModification(String text){String t=imperative(text);if(excluded(t))return false;
  if(t.matches("(?:сделай|сделайте|make) .+")&&target(t)&&t.matches("(?s).*(?:именно|настоящ\\p{L}*|красив\\p{L}*|уютн\\p{L}*|beautiful|pretty|proper|cozy).*")&&!t.matches("(?s).*(?<![\\p{L}])(?:новый|новую|новое|еще|new|another)(?![\\p{L}]).*"))return true;
  return t.matches("(?:сделай|сделайте|make) (?:именно |его |ее |это |дом |it |the house )*(?:красивее|лучше|покрасивее|побольше|поменьше|выше|ниже|шире|уютнее|красивым|красивой|красиво|prettier|nicer|better|bigger|smaller|taller|wider|beautiful).*")
   ||t.matches("(?:добавь|добавьте|измени|переделай|перестрой|укрась|расширь|улучши|замени|add|change|remodel|rebuild|decorate|expand|improve|replace) .+")&&target(t);
 }
 /** Removal targets the requesting player's verified latest build journal only. */
 public static boolean isUndo(String text){String t=imperative(text);if(excluded(t))return false;
  if(t.matches("(?s).*(?<![\\p{L}])(?:кроме|но|оставь|сохрани|except|but|leave|keep)(?![\\p{L}]).*"))return false;
  if(t.matches("(?:undo|revert)(?: (?:the |my |your |last |this |that )*(?:build|building|house|structure))?[.! ]*"))return true;
  if(t.matches("(?:отмени|отмените) (?:последнюю |эту |свою |твою |мною заказанную )?(?:постройку|строительство|дом)[.! ]*"))return true;
  if(!t.matches("(?:удали|удалите|убери|уберите|снеси|снесите|разбери|remove|delete|demolish|take down) .+"))return false;
  return target(t)&&t.matches("(?s).*(?<![\\p{L}])(?:этот|эту|это|последний|последнюю|построенный тобой|построенную тобой|this|that|last|you built)(?![\\p{L}]).*");
 }
}
