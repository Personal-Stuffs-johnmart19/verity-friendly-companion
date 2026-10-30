package dev.johnmart19.veritycompanion;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
/** Per-player voice preference; never changes shared provider or quota settings. */
public final class TtsCommands {
 static final java.util.List<String> VOICES=java.util.List.of("Zephyr","Puck","Charon","Kore","Fenrir","Leda","Orus","Aoede","Callirrhoe","Autonoe","Enceladus","Iapetus","Umbriel","Algieba","Despina","Erinome","Algenib","Rasalgethi","Laomedeia","Achernar","Alnilam","Schedar","Gacrux","Pulcherrima","Achird","Zubenelgenubi","Vindemiatrix","Sadachbia","Sadaltager","Sulafat");
 static String normalize(String input){return VOICES.stream().filter(v->v.equalsIgnoreCase(input)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown voice; /tts voices"));}
 static int show(ServerPlayer p){var e=Relations.get(p);var commands=new java.util.ArrayList<String>(java.util.List.of("/tts off","/tts language <code|auto|inherit>","/tts status"));if(RuntimeCapabilities.ttsReady())commands.add("/tts on");if(RuntimeCapabilities.googleVoice())commands.addAll(java.util.List.of("/tts voices","/tts voice <name>"));if(p.hasPermissions(2))commands.add("/tts provider <provider>");p.sendSystemMessage(Component.literal(ModTranslations.text(e.language,"verity_companion.runtime.tts_status",ModTranslations.text(e.language,e.tts?"verity_companion.state.on":"verity_companion.state.off"),RuntimeCapabilities.get().provider(),String.join("; ",commands))));return LanguageCommands.show(p,LanguageCommands.Scope.TTS);}
 static int select(ServerPlayer p,String input){return select(p,input,true);}
 static int select(ServerPlayer p,String input,boolean notify){String voice=normalize(input);synchronized(Relations.class){var e=Relations.get(p);String previous=e.ttsVoice;e.ttsVoice=voice;try{Relations.save(p);}catch(IllegalStateException failed){e.ttsVoice=previous;throw failed;}}if(notify)p.sendSystemMessage(Component.literal(ModTranslations.text(Relations.get(p).language,"verity_companion.tts.selected",voice)));return 1;}
 private static int toggle(ServerPlayer p,boolean enabled){synchronized(Relations.class){var e=Relations.get(p);boolean previous=e.tts;e.tts=enabled;try{Relations.save(p);}catch(IllegalStateException failed){e.tts=previous;throw failed;}}net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new VoiceSettings(enabled));return show(p);}
 static void register(RegisterCommandsEvent event){VoiceCommands.register(event);event.getDispatcher().register(Commands.literal("tts")
  .executes(c->show(c.getSource().getPlayerOrException()))
  .then(Commands.literal("on").requires(source->RuntimeCapabilities.ttsReady()).executes(c->toggle(c.getSource().getPlayerOrException(),true)))
  .then(Commands.literal("off").executes(c->toggle(c.getSource().getPlayerOrException(),false)))
  .then(LanguageCommands.node(LanguageCommands.Scope.TTS))
  .then(Commands.literal("status").executes(c->RuntimeCapabilities.status(c.getSource())))
  .then(Commands.literal("provider").requires(source->source.hasPermission(2)).then(Commands.argument("provider",StringArgumentType.word()).suggests((c,b)->java.util.concurrent.CompletableFuture.completedFuture(RuntimeCapabilities.suggestions(b,RuntimeCapabilities.get().ttsProviders()))).executes(c->RuntimeCapabilities.select(c.getSource(),true,StringArgumentType.getString(c,"provider")))))
  .then(Commands.literal("voices").requires(source->RuntimeCapabilities.googleVoice()).executes(c->{c.getSource().sendSuccess(()->Component.literal(String.join(", ",VOICES)),false);return 1;}))
  .then(Commands.literal("voice").requires(source->RuntimeCapabilities.googleVoice()).then(Commands.argument("name",StringArgumentType.word()).suggests((c,b)->{for(String v:VOICES)if(v.toLowerCase(java.util.Locale.ROOT).startsWith(b.getRemainingLowerCase()))b.suggest(v);return b.buildFuture();}).executes(c->{var player=c.getSource().getPlayerOrException();try{return select(player,StringArgumentType.getString(c,"name"));}catch(IllegalArgumentException error){c.getSource().sendFailure(Component.literal(ModTranslations.text(Relations.get(player).language,"verity_companion.tts.unknown_voice")));return 0;}catch(IllegalStateException failed){c.getSource().sendFailure(Component.literal(ModTranslations.text(Relations.get(player).language,"verity_companion.settings.save_failed")));return 0;}}))));}
}
