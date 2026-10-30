package dev.johnmart19.veritycompanion;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
/** Per-player recording-language preference; upload consent remains host controlled. */
final class VoiceCommands {
 static void register(RegisterCommandsEvent event){event.getDispatcher().register(Commands.literal("voice")
  .executes(c->{var player=c.getSource().getPlayerOrException();player.sendSystemMessage(Component.literal(ModTranslations.text(Relations.get(player).language,"verity_companion.voice.help")));return LanguageCommands.show(player,LanguageCommands.Scope.VOICE);})
  .then(Commands.literal("status").executes(c->RuntimeCapabilities.status(c.getSource())))
  .then(Commands.literal("backend").requires(source->source.hasPermission(2)).then(Commands.argument("backend",com.mojang.brigadier.arguments.StringArgumentType.word()).suggests((c,b)->java.util.concurrent.CompletableFuture.completedFuture(RuntimeCapabilities.suggestions(b,RuntimeCapabilities.get().sttBackends()))).executes(c->RuntimeCapabilities.select(c.getSource(),false,com.mojang.brigadier.arguments.StringArgumentType.getString(c,"backend")))))
  .then(LanguageCommands.node(LanguageCommands.Scope.VOICE)));}
}
