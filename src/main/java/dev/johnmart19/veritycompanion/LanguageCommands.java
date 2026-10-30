package dev.johnmart19.veritycompanion;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import java.util.*;
/** Per-player persisted selections; no provider, consent or shared model changes. */
final class LanguageCommands {
 enum Scope {COMPANION("companion"),LLM("llm"),TTS("tts"),VOICE("voice");final String command;Scope(String command){this.command=command;}}
 static LiteralArgumentBuilder<CommandSourceStack> node(){return node(Scope.COMPANION);}
 static LiteralArgumentBuilder<CommandSourceStack> node(Scope scope){return Commands.literal("language").executes(c->show(c.getSource().getPlayerOrException(),scope))
  .then(Commands.argument("language",StringArgumentType.word()).suggests((c,b)->{List<String> modes=new ArrayList<>(Languages.codes());modes.add("auto");if(scope!=Scope.COMPANION)modes.add("inherit");for(String value:modes)if(value.startsWith(b.getRemainingLowerCase()))b.suggest(value);return b.buildFuture();})
   .executes(c->{ServerPlayer player=c.getSource().getPlayerOrException();try{return set(player,scope,StringArgumentType.getString(c,"language"),true);}catch(IllegalArgumentException invalid){c.getSource().sendFailure(Component.literal(ModTranslations.text(Relations.get(player).language,"verity_companion.language.invalid",String.join(", ",Languages.codes()))));return 0;}catch(IllegalStateException failed){c.getSource().sendFailure(Component.literal(ModTranslations.text(Relations.get(player).language,"verity_companion.settings.save_failed")));return 0;}}));}
 static int show(ServerPlayer p){return show(p,Scope.COMPANION);}
 static int show(ServerPlayer p,Scope scope){var e=Relations.get(p);String mode=switch(scope){case COMPANION->e.languageMode;case LLM->e.llmLanguage;case TTS->e.ttsLanguage;case VOICE->e.sttLanguage;};String effective=switch(scope){case COMPANION->e.language;case LLM->Relations.replyLanguage(p);case TTS->Relations.ttsLanguage(p);case VOICE->Relations.sttLanguage(p);};
  String selected=effective==null?ModTranslations.text(e.language,"verity_companion.language.host"):effective.equals("auto")?ModTranslations.text(e.language,"verity_companion.language.auto"):Languages.name(effective)+" ("+effective+")";
  p.sendSystemMessage(Component.literal(ModTranslations.text(e.language,"verity_companion.language.status",ModTranslations.text(e.language,"verity_companion.language.scope."+scope.command),selected,mode,"/"+scope.command+" language <code|auto"+(scope==Scope.COMPANION?"":"|inherit")+">")));return 1;}
 static int set(ServerPlayer p,String value){return set(p,value,true);}
 static int set(ServerPlayer p,String value,boolean notify){return set(p,Scope.COMPANION,value,notify);}
 static int set(ServerPlayer p,Scope scope,String value,boolean notify){String mode=Languages.mode(value,scope!=Scope.COMPANION);
  synchronized(Relations.class){var e=Relations.get(p);String previous=e.language,previousMode=e.languageMode,previousLlm=e.llmLanguage,previousTts=e.ttsLanguage,previousStt=e.sttLanguage;
   switch(scope){case COMPANION->{e.languageMode=mode;e.language=PlayerLanguage.choose(mode,e.language,"",p.clientInformation().language());}case LLM->e.llmLanguage=mode;case TTS->e.ttsLanguage=mode;case VOICE->e.sttLanguage=mode;}
   try{Relations.save(p);}catch(IllegalStateException failed){e.language=previous;e.languageMode=previousMode;e.llmLanguage=previousLlm;e.ttsLanguage=previousTts;e.sttLanguage=previousStt;throw failed;}
  }
  if(scope==Scope.COMPANION)Relations.refresh(p);return notify?show(p,scope):1;
 }
}
