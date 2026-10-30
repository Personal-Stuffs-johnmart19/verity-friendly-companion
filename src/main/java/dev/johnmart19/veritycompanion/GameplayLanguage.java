package dev.johnmart19.veritycompanion;

/** Small conversational distinctions which must not silently authorize gameplay actions. */
final class GameplayLanguage {
 static boolean idiomaticStrength(String original){
  String s=ToolIntent.normalized(original);
  return s.matches("(?s).*(?:боже|господи|god|lord).*?(?:дай|give).*?(?:сил(?:ы)?|strength).*" )
   &&!s.matches("(?s).*(?:эффект|баф|зель|buff|effect|potion|minecraft:strength|уровень|level).*" );
 }
 static String hint(String text){return idiomaticStrength(text)?"The human is expressing frustration, not requesting a Minecraft Strength effect. Respond warmly in character; do not demand an effect ID or cast anything.":"";}
 static String hint(String text,String language){return hint(text);}
}
