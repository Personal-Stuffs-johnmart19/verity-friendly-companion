package dev.johnmart19.veritycompanion;

/** Small conversational distinctions which must not silently authorize gameplay actions. */
final class GameplayLanguage {
 static boolean idiomaticStrength(String original){
  String s=ToolIntent.normalized(original);
  return s.matches("(?s).*(?:боже|господи|god|lord).*?(?:дай|give).*?(?:сил(?:ы)?|strength).*" )
   &&!s.matches("(?s).*(?:эффект|баф|зель|buff|effect|potion|minecraft:strength|уровень|level).*" );
 }
 static String hint(String text){return idiomaticStrength(text)?"The human's 'Боже, дай мне сил' is an idiom expressing frustration, not a request for a Minecraft Strength effect. Respond warmly in character; do not demand an effect ID or cast anything.":"";}
 static String hint(String text,String language){return CompanionLanguage.isRussian(language)?(idiomaticStrength(text)?"«Боже, дай мне сил» — выражение досады, не просьба об эффекте силы Minecraft. Ответь тепло в образе; не требуй ID эффекта и ничего не применяй.":""):hint(text);}
}
