package dev.johnmart19.veritycompanion;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
/** Per-player persisted selection; no provider, voice or shared model changes. */
final class LanguageCommands {
 static LiteralArgumentBuilder<CommandSourceStack> node(){return Commands.literal("language").executes(c->show(c.getSource().getPlayerOrException()))
  .then(Commands.argument("language",StringArgumentType.word()).suggests((c,b)->{for(String value:new String[]{"en","ru","auto"})b.suggest(value);return b.buildFuture();})
   .executes(c->{try{return set(c.getSource().getPlayerOrException(),StringArgumentType.getString(c,"language"));}catch(IllegalArgumentException|IllegalStateException failed){c.getSource().sendFailure(Component.literal(failed.getMessage()));return 0;}}));}
 static int show(ServerPlayer p){var e=Relations.get(p);p.sendSystemMessage(Component.literal(Relations.tr(p,"Язык ответов и инструкций: ","Reply and instruction language: ")+e.language+" ("+e.languageMode+"). /companion language en|ru|auto"));return 1;}
 static int set(ServerPlayer p,String value){return set(p,value,true);}
 static int set(ServerPlayer p,String value,boolean notify){String mode=PlayerLanguage.mode(value);
  synchronized(Relations.class){var e=Relations.get(p);String previous=e.language,previousMode=e.languageMode;
   e.languageMode=mode;e.language=PlayerLanguage.choose(mode,e.language,"");
   try{Relations.save(p);}catch(IllegalStateException failed){e.language=previous;e.languageMode=previousMode;throw failed;}
  }
  Relations.refresh(p);return notify?show(p):1;
 }
}
