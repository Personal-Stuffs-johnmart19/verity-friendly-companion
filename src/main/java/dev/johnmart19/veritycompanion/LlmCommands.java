package dev.johnmart19.veritycompanion;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Shared text-model controls; global model branches require operator level 2. */
public final class LlmCommands {
 static String help(net.minecraft.commands.CommandSourceStack source){
  var s=RuntimeCapabilities.get();var commands=new java.util.ArrayList<String>(java.util.List.of("/llm help","/llm language <code|auto|inherit>"));
  if(source.hasPermission(2)){
   commands.addAll(java.util.List.of("/llm status","/llm refresh","/llm reset","/llm temperature <0..2>","/llm max_tokens <128..4096>","/llm system_prompt [show|clear|append <text>|<text>]"));
   if(s.lmReachable()){commands.add("/llm models");if(!s.models().isEmpty())commands.add("/llm local <model>");}
   if(s.google())commands.add("/llm cloud");
   if(s.localSelected()){commands.add("/llm caps");commands.add("/llm context <4096..1048576>");if(!s.reasoning().isEmpty())commands.add("/llm reasoning <"+String.join("|",s.reasoning().stream().sorted().toList())+">");}
  }
  return ModTranslations.text(RuntimeCapabilities.language(source),"verity_companion.runtime.llm_help",String.join("; ",commands));
 }
 private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> operator(String name){return operator(name,()->true);}
 private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> operator(String name,java.util.function.BooleanSupplier available){return Commands.literal(name).requires(source->source.hasPermission(2)&&available.getAsBoolean());}
 static void register(RegisterCommandsEvent event){
  com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack> help=c->{c.getSource().sendSuccess(()->Component.literal(help(c.getSource())),false);return 1;};
  com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack> models=c->LocalModelCommands.request(c.getSource().getPlayerOrException(),null,false);
  com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack> prompt=c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"prompt_show",null);
  var root=Commands.literal("llm").executes(help);
  root.then(Commands.literal("help").executes(help));
  root.then(LanguageCommands.node(LanguageCommands.Scope.LLM));
  root.then(operator("local",()->RuntimeCapabilities.local()&&!RuntimeCapabilities.get().models().isEmpty()).executes(models).then(Commands.argument("model",StringArgumentType.greedyString()).suggests((c,b)->LocalModelCommands.suggest(b)).executes(c->LocalModelCommands.request(c.getSource().getPlayerOrException(),StringArgumentType.getString(c,"model"),false))));
  root.then(operator("models",RuntimeCapabilities::local).executes(models));
  root.then(operator("status").executes(c->{RuntimeCapabilities.status(c.getSource());return c.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player?LocalModelCommands.status(player):1;}));
  root.then(operator("refresh").executes(c->RuntimeCapabilities.requestRefresh(c.getSource())));
  root.then(operator("cloud",RuntimeCapabilities::google).executes(c->LocalModelCommands.request(c.getSource().getPlayerOrException(),null,true)));
  root.then(operator("caps",RuntimeCapabilities::selectedLocal).executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"caps",null)));
  root.then(operator("reset").executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"reset",null)));
  var reasoning=operator("reasoning",()->RuntimeCapabilities.selectedLocal()&&!RuntimeCapabilities.get().reasoning().isEmpty());
  for(String mode:java.util.List.of("on","off","low","medium","high"))reasoning.then(Commands.literal(mode).requires(source->RuntimeCapabilities.get().reasoning().contains(mode)).executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"reasoning",new JsonPrimitive(mode))));
  root.then(reasoning);
  root.then(operator("context",RuntimeCapabilities::selectedLocal).then(Commands.argument("value",IntegerArgumentType.integer(4096,1048576)).executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"context",new JsonPrimitive(IntegerArgumentType.getInteger(c,"value"))))));
  root.then(operator("temperature").then(Commands.argument("value",DoubleArgumentType.doubleArg(0,2)).executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"temperature",new JsonPrimitive(DoubleArgumentType.getDouble(c,"value"))))));
  root.then(operator("max_tokens").then(Commands.argument("value",IntegerArgumentType.integer(128,4096)).executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"max_tokens",new JsonPrimitive(IntegerArgumentType.getInteger(c,"value"))))));
  root.then(operator("system_prompt").executes(prompt)
   .then(Commands.literal("show").executes(prompt))
   .then(Commands.literal("clear").executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"prompt_clear",null)))
   .then(Commands.literal("append").then(Commands.argument("text",StringArgumentType.greedyString()).executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"system_prompt",new JsonPrimitive(StringArgumentType.getString(c,"text"))))))
   .then(Commands.argument("text",StringArgumentType.greedyString()).executes(c->LocalModelCommands.setting(c.getSource().getPlayerOrException(),"system_prompt",new JsonPrimitive(StringArgumentType.getString(c,"text"))))));
  event.getDispatcher().register(root);
 }
}
