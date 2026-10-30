package dev.johnmart19.veritycompanion;

/** Original human consent for moving, creating or stopping a companion. */
final class CompanionPresencePolicy {
 private CompanionPresencePolicy(){}
 static boolean explicit(String action,String text){
  String addressed=ToolIntent.normalized(text).replaceFirst("^(?:верити|verity)[,! ]+","");
  String s=CompanionChatIntent.request(addressed);
  if(s.isEmpty()||s.length()>512||s.contains("?")||s.matches("(?s).*[\"«»„“”`].*")||s.startsWith("'")||s.endsWith("'"))return false;
  if(s.matches("(?s).*(?:^|[^\\p{L}])(?:не|нет|никогда|not|no|never|don't|dont|cancel|отмена|отмени)(?:$|[^\\p{L}]).*"))return false;
  s=s.replaceFirst("^(?:верити|verity)[,! ]+","").replaceFirst("^(?:пожалуйста|please)[,! ]+","");
  s=s.replaceFirst("[.!]+$","").trim();
  return switch(action){
   case "follow"->s.matches("(?:следуй за мной|иди за мной|follow me)(?: пожалуйста| please)?");
   case "come"->s.matches("(?:приди|подойди|вернись|иди сюда|ко мне|телепортируйся|телепортируй(?: себя| верити| помощника)? ко мне|come|come here|come to me|teleport here|teleport to me|bring (?:yourself|verity|my companion|the companion) (?:here|to me))(?: ко мне)?(?: пожалуйста| please)?");
   case "summon"->s.matches("(?:призови|создай|summon|spawn|create) (?:себя|помощника|помощницу|верити|verity|companion|a companion)(?: рядом| со мной| здесь| here| near me)?(?: пожалуйста| please)?");
   case "box"->s.matches("(?:дай|создай|призови|give|create|spawn|summon) (?:мне |me |a )?(?:коробку|box)(?: рядом| здесь| here| near me)?(?: пожалуйста| please)?");
   case "stay"->s.matches("(?:перестань следовать за мной|stop following me|остановись(?: здесь| тут)?|стой|жди|жди здесь|стой здесь|stop|stay|stay here|wait|wait here)(?: пожалуйста| please)?");
   default->false;
  };
 }
}
