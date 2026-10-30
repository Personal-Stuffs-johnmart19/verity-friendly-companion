package dev.johnmart19.veritycompanion;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

/** Shared natural-chat entry to existing personal controls; no command dispatcher or global settings. */
final class CompanionChatActions {
 static DirectRequests.Answer execute(ServerPlayer p,String original)throws Exception{
  String human=ToolIntent.unaddress(original,Relations.name(p));
  for(String action:new String[]{"follow","come","stay","summon","box"})if(CompanionPresence.explicit(action,human))
   {JsonObject args=new JsonObject();args.addProperty("action",action);return new DirectRequests.Answer(CompanionBrain.tool(p,"companion",args,original),"happy");}
  var change=CompanionChatIntent.parse(human);if(change.isEmpty())return null;
  var c=change.get();return new DirectRequests.Answer(CompanionBrain.playerMain(p.getServer(),p,()->{
   if(c.field().equals("name"))return CompanionCommands.rename(p,c.value());
   JsonObject args=new JsonObject();if(c.field().equals("tts"))args.addProperty("tts",Boolean.parseBoolean(c.value()));else args.addProperty(c.field(),c.value());
   persona(p,args,original);
   return switch(c.field()){
    case "tts"->Relations.tr(p,"Озвучка ","Voice replies ")+(c.value().equals("true")?Relations.tr(p,"включена.","enabled."):Relations.tr(p,"выключена.","disabled."));
    case "language"->Relations.tr(p,"Буду отвечать по-русски.","I will reply in English.");
    case "voice"->Relations.tr(p,"Буду использовать голос ","I will use the voice ")+TtsCommands.normalize(c.value())+".";
    case "gender"->Relations.tr(p,"Теперь буду говорить о себе в ","My persona is now ")+(c.value().equals("female")?Relations.tr(p,"женском роде.","female."):c.value().equals("male")?Relations.tr(p,"мужском роде.","male."):Relations.tr(p,"нейтральном роде.","neutral."));
    default->Relations.tr(p,"Роль обновлена. Внешняя модель не менялась.","Role updated. The visual model is unchanged.");
   };
  }),"happy");
 }
 static String persona(ServerPlayer p,JsonObject args,String original){
  String human=ToolIntent.unaddress(original,Relations.name(p));
  // Validate the entire proposal before changing any setting. Typed human evidence is authoritative.
  String gender=args.has("gender")?PersonaPolicy.normalize(args.get("gender").getAsString()):null;
  String role=args.has("role")?PersonaPolicy.role(args.get("role").getAsString()):null;
  String language=args.has("language")?PlayerLanguage.mode(args.get("language").getAsString()):null;
  String voice=args.has("voice")?TtsCommands.normalize(args.get("voice").getAsString()):null;
  Boolean tts=null;
  if(args.has("tts")){if(!args.get("tts").isJsonPrimitive()||!args.getAsJsonPrimitive("tts").isBoolean())throw new IllegalArgumentException("tts must be boolean");tts=args.get("tts").getAsBoolean();}
  if(language!=null&&!CompanionChatIntent.authorizes(human,"language",language))throw new IllegalArgumentException("Use the language requested by the player");
  if(gender!=null&&!CompanionChatIntent.authorizes(human,"gender",gender))throw new IllegalArgumentException("Use the gender requested by the player");
  if(role!=null&&!CompanionChatIntent.authorizes(human,"role",role))throw new IllegalArgumentException("Use the role requested by the player");
  if(voice!=null&&!CompanionChatIntent.authorizes(human,"voice",voice))throw new IllegalArgumentException("Use the voice requested by the player");
  if(tts!=null&&!CompanionChatIntent.authorizes(human,"tts",tts.toString()))throw new IllegalArgumentException("Use the speech preference requested by the player");
  // The returned natural answer is the confirmation; command handlers still notify by default.
  if(gender!=null&&CompanionCommands.gender(p,gender,false)!=1)throw new IllegalStateException("Persona not applied");
  if(role!=null&&CompanionCommands.role(p,role,false)!=1)throw new IllegalStateException("Role not applied");
  if(language!=null)LanguageCommands.set(p,language,false);
  if(voice!=null)TtsCommands.select(p,voice,false);
  if(tts!=null)LocalModelCommands.tts(p,tts,false);
  var e=Relations.get(p);
  JsonObject state=new JsonObject();state.addProperty("language",e.language);state.addProperty("language_mode",e.languageMode);state.addProperty("gender",e.gender);state.addProperty("role",e.role);state.addProperty("tts",e.tts);state.addProperty("voice",e.ttsVoice);return state.toString();
 }
}
