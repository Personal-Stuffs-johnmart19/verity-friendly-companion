package dev.johnmart19.veritycompanion;
import java.util.Locale;
/** Narrow plural request. Named/singular removals must not erase unrelated enchantments. */
final class EnchantmentIntent {
 static boolean clearAll(String input){
  String s=HumanIntentLexicon.policyText(input).replaceAll("[.!?,]"," ").replaceAll("\\s+"," ").trim();
  if(s.matches("(?s).*(?:не |don't |do not |не надо|если |if ).*"))return false;
  return s.matches("(?:убери|сними|удали|очисти)(?: пожалуйста)? (?:все |все мои )?(?:зачарования|чары)(?: (?:с|со) (?:моей |моего |моих |этой |этого )?(?:кирки|инструмента|предмета|меча|брони|книги|предмета в руке))?(?: пожалуйста)?")
   ||s.matches("(?:remove|clear|strip) (?:all |all the |the )?enchantments(?: (?:from|on|off) (?:my |the |this |held )*(?:item|tool|pickaxe|sword|book|held item))?(?: please)?");
 }
}
