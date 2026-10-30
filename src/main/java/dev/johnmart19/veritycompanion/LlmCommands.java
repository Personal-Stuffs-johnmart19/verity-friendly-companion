package dev.johnmart19.veritycompanion;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Shared text-model controls; every branch requires operator level 2. */
public final class LlmCommands {
 static final String HELP="/llm models; local <model>; status; caps; cloud; reasoning off|on|low|medium|high; context <4096..1048576>; temperature <0..2>; max_tokens <128..4096>; system_prompt show|append <text>|clear; reset. OP: temporary shared server overrides. Context changes may reload the local model and increase memory use; model capacity limits apply. Prompt appends to Verity core instructions.";
 static final String HELP_RU="/llm models — список; local <модель> — выбрать; status; caps; cloud — Google; reasoning off|on|low|medium|high; context <4096..1048576>; temperature <0..2>; max_tokens <128..4096>; system_prompt show|append <текст>|clear; reset. Для OP: общие временные настройки до сброса/перезапуска моста. Контекст ограничен возможностями модели; изменение может перезагрузить модель и повысить расход памяти. Дополнение промпта сохраняет основные инструкции Verity.";
 static String help(net.minecraft.commands.CommandSourceStack source){return source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p?Relations.tr(p,HELP_RU,HELP):HELP;}
 static void register(RegisterCommandsEvent event){
  com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack> help=c->{c.getSource().sendSuccess(()->Component.literal(help(c.getSource())),false);return 1;};
  com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack> models=c->LocalModelCommands.request(c.getSource().getPlayerOrException(),null,false);
  com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack> prompt=c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"prompt_show",null);
  var root=Commands.literal("llm").requires(s->s.hasPermission(2)).executes(help);
  root.then(Commands.literal("help").executes(help));
  root.then(Commands.literal("local").executes(models).then(Commands.argument("model",StringArgumentType.greedyString()).suggests((c,b)->LocalModelCommands.suggest(b)).executes(c->LocalModelCommands.request(c.getSource().getPlayerOrException(),StringArgumentType.getString(c,"model"),false))));
  root.then(Commands.literal("models").executes(models));
  root.then(Commands.literal("status").executes(c->LocalModelCommands.status(c.getSource().getPlayerOrException())));
  root.then(Commands.literal("cloud").executes(c->LocalModelCommands.request(c.getSource().getPlayerOrException(),null,true)));
  root.then(Commands.literal("caps").executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"caps",null)));
  root.then(Commands.literal("reset").executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"reset",null)));
  var reasoning=Commands.literal("reasoning");
  for(String mode:java.util.List.of("on","off","low","medium","high"))reasoning.then(Commands.literal(mode).executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"reasoning",new JsonPrimitive(mode))));
  root.then(reasoning);
  root.then(Commands.literal("context").then(Commands.argument("value",IntegerArgumentType.integer(4096,1048576)).executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"context",new JsonPrimitive(IntegerArgumentType.getInteger(c,"value"))))));
  root.then(Commands.literal("temperature").then(Commands.argument("value",DoubleArgumentType.doubleArg(0,2)).executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"temperature",new JsonPrimitive(DoubleArgumentType.getDouble(c,"value"))))));
  root.then(Commands.literal("max_tokens").then(Commands.argument("value",IntegerArgumentType.integer(128,4096)).executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"max_tokens",new JsonPrimitive(IntegerArgumentType.getInteger(c,"value"))))));
  root.then(Commands.literal("system_prompt").executes(prompt)
   .then(Commands.literal("show").executes(prompt))
   .then(Commands.literal("clear").executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"prompt_clear",null)))
   .then(Commands.literal("append").then(Commands.argument("text",StringArgumentType.greedyString()).executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"system_prompt",new JsonPrimitive(StringArgumentType.getString(c,"text"))))))
   .then(Commands.argument("text",StringArgumentType.greedyString()).executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"system_prompt",new JsonPrimitive(StringArgumentType.getString(c,"text"))))));
  event.getDispatcher().register(root);
 }
}
