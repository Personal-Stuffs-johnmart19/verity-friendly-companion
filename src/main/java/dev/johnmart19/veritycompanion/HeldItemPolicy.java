package dev.johnmart19.veritycompanion;
import java.util.Optional;
/** Human consent for changing only the held item's vanilla durability component. */
public final class HeldItemPolicy {
 private static final java.util.regex.Pattern POLITE=java.util.regex.Pattern.compile("^(?:можешь|можете|могла бы|мог бы|can you|could you|would you)[, ]+(?:(?:пожалуйста|please)[, ]+)?");
 static String clean(String input){return ToolIntent.normalized(input).replaceFirst("^(?:верити|verity)[,! ]+","").replaceFirst("^(?:пожалуйста|please)[,! ]+","");}
 public static Optional<Boolean> requestedUnbreakable(String input){
  String s=clean(input);if(ToolIntent.excludedGiftText(s)||s.matches("(?s).*(?<![\\p{L}])(?:его|ее|их|чужую|чужой|игрока|игроку|his|her|their|someone|player)(?![\\p{L}]).*"))return Optional.empty();
  var prefix=POLITE.matcher(s);boolean polite=prefix.find();if(polite)s=s.substring(prefix.end());
  // A polite action may end in '?'; bare questions and embedded/conditional questions stay informational.
  if(s.contains("?")){if(!polite||!s.matches("[^?]+\\?[.! ]*"))return Optional.empty();s=s.replaceFirst("\\?[.! ]*$","");}
  if(polite){if(s.matches("(?s).*(?<![\\p{L}])(?:если|или|ли|if|or|whether)(?![\\p{L}]).*"))return Optional.empty();s=s.replaceFirst("[, ]+(?:пожалуйста|please)[.! ]*$","").replaceFirst("^сделать ","сделай ").replaceFirst("^вернуть ","верни ").replaceFirst("^восстановить ","восстанови ");}
  if(s.matches("(?:верни|восстанови) износ(?: (?:предмета|кирки|меча|топора|лопаты|мотыги|в руке))?[.! ]*")||s.matches("(?:restore|enable) (?:item )?(?:durability|wear)(?: (?:on|for) (?:my |the )?(?:held item|pickaxe|sword|axe|shovel|hoe))?[.! ]*"))return Optional.of(false);
  if(!s.matches("(?:сделай|сделайте|верни|make|set|restore) .+"))return Optional.empty();
  if(s.matches("(?s).*(?:неломаем[\\p{L}]*|неразрушим[\\p{L}]*|(?<![a-z])unbreakable(?![a-z])).*"))return Optional.of(true);
  if(s.matches("(?s).*(?:(?<![\\p{L}])ломаем[\\p{L}]*|(?<![a-z])breakable(?![a-z])).*"))return Optional.of(false);
  return Optional.empty();
 }
 static String requestedKind(String input){String s=clean(input);
  if(s.matches("(?s).*(?:кирк[\\p{L}]*|(?<![a-z])pickaxe(?:s)?(?![a-z])).*"))return "pickaxe";
  if(s.matches("(?s).*(?:меч[\\p{L}]*|(?<![a-z])sword(?:s)?(?![a-z])).*"))return "sword";
  if(s.matches("(?s).*(?:топор[\\p{L}]*|(?<![a-z])axe(?:s)?(?![a-z])).*"))return "axe";
  if(s.matches("(?s).*(?:лопат[\\p{L}]*|(?<![a-z])shovel(?:s)?(?![a-z])).*"))return "shovel";
  if(s.matches("(?s).*(?:мотыг[\\p{L}]*|(?<![a-z])hoe(?:s)?(?![a-z])).*"))return "hoe";
  if(s.matches("(?s).*(?:предмет[\\p{L}]*|в руке|износ|(?<![a-z])(?:held|item|durability|wear)(?![a-z])).*"))return "held";
  return "unknown";
 }
}
