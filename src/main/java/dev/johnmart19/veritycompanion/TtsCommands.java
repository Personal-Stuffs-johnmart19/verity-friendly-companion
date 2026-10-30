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
 static int show(ServerPlayer p){var e=Relations.get(p);p.sendSystemMessage(Component.literal(Relations.tr(p,"Твоя озвучка: ","Your TTS: ")+(e.tts?"ON":"OFF")+"; Gemini / "+e.ttsVoice+". /tts on|off; /tts voices; /tts voice <name>"));return 1;}
 static int select(ServerPlayer p,String input){return select(p,input,true);}
 static int select(ServerPlayer p,String input,boolean notify){String voice=normalize(input);synchronized(Relations.class){Relations.get(p).ttsVoice=voice;Relations.save(p);}if(notify)p.sendSystemMessage(Component.literal(Relations.tr(p,"Твой голос Gemini: ","Your Gemini voice: ")+voice+Relations.tr(p,". Включение: /tts on. Квота общая для сервера.",". Enable: /tts on. Quota is shared by the server.")));return 1;}
 static void register(RegisterCommandsEvent event){event.getDispatcher().register(Commands.literal("tts")
  .executes(c->show(c.getSource().getPlayerOrException()))
  .then(Commands.literal("on").executes(c->LocalModelCommands.tts(c.getSource().getPlayerOrException(),true)))
  .then(Commands.literal("off").executes(c->LocalModelCommands.tts(c.getSource().getPlayerOrException(),false)))
  .then(Commands.literal("voices").executes(c->{c.getSource().sendSuccess(()->Component.literal(String.join(", ",VOICES)),false);return 1;}))
  .then(Commands.literal("voice").then(Commands.argument("name",StringArgumentType.word()).suggests((c,b)->{for(String v:VOICES)if(v.toLowerCase(java.util.Locale.ROOT).startsWith(b.getRemainingLowerCase()))b.suggest(v);return b.buildFuture();}).executes(c->{try{return select(c.getSource().getPlayerOrException(),StringArgumentType.getString(c,"name"));}catch(IllegalArgumentException error){c.getSource().sendFailure(Component.literal(error.getMessage()));return 0;}}))));}
}
