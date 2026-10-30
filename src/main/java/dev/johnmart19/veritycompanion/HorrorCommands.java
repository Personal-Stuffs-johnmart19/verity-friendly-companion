package dev.johnmart19.veritycompanion;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

final class HorrorCommands {
 static LiteralArgumentBuilder<CommandSourceStack> node(){
  com.mojang.brigadier.Command<CommandSourceStack> status=c->status(c.getSource());
  var root=Commands.literal("horror").executes(status);
  root.then(Commands.literal("status").executes(status));
  var mode=Commands.literal("mode").requires(s->s.hasPermission(2));
  for(String value:new String[]{"off","mood","random"})mode.then(Commands.literal(value).executes(c->change(c.getSource(),o->o.mode=value)));
  root.then(mode);
  var boss=Commands.literal("boss").requires(s->s.hasPermission(2));
  for(String value:new String[]{"off","mood","random"})boss.then(Commands.literal(value).executes(c->change(c.getSource(),o->o.bossMode=value)));
  root.then(boss);
  var events=Commands.literal("event").requires(s->s.hasPermission(2));
  for(String value:new String[]{"darkness","fog","sounds","stalker"}){
   var option=Commands.literal(value);
   for(boolean enabled:new boolean[]{true,false})option.then(Commands.literal(enabled?"on":"off").executes(c->change(c.getSource(),o->{switch(value){case "darkness"->o.darkness=enabled;case "fog"->o.fog=enabled;case "sounds"->o.sounds=enabled;case "stalker"->o.stalker=enabled;}})));
   events.then(option);
  }root.then(events);
  root.then(Commands.literal("threshold").requires(s->s.hasPermission(2)).then(Commands.argument("mood",IntegerArgumentType.integer(0,100)).executes(c->change(c.getSource(),o->o.moodThreshold=IntegerArgumentType.getInteger(c,"mood")))));
  root.then(Commands.literal("chance").requires(s->s.hasPermission(2)).then(Commands.argument("percent",IntegerArgumentType.integer(0,100)).executes(c->change(c.getSource(),o->o.chancePercent=IntegerArgumentType.getInteger(c,"percent")))));
  root.then(Commands.literal("cooldown").requires(s->s.hasPermission(2)).then(Commands.argument("seconds",IntegerArgumentType.integer(10,3600)).executes(c->change(c.getSource(),o->o.cooldownSeconds=IntegerArgumentType.getInteger(c,"seconds")))));
  root.then(Commands.literal("stop").executes(c->{ServerPlayer p=c.getSource().getPlayerOrException();HorrorController.stop(p);p.sendSystemMessage(Component.literal(ModTranslations.text(Relations.get(p).language,"verity_companion.horror_commands.your_current_horror_encounter_stopped_server_mode")));return 1;}));
  return root;
 }
 static int legacyBoss(CommandSourceStack source,boolean enabled){return change(source,o->{o.bossMode=enabled?"mood":"off";if(enabled&&o.mode.equals("off"))o.mode="mood";});}
 private static int change(CommandSourceStack source,java.util.function.Consumer<HorrorSettings.Options> edit){
  try{HorrorSettings.update(edit);HorrorController.configurationChanged(source.getServer());source.sendSuccess(()->Component.literal(ModTranslations.text(source.getEntity() instanceof ServerPlayer p?Relations.get(p).language:"en","verity_companion.horror_commands.verity_horror_configuration_saved")),true);return status(source);}catch(IllegalArgumentException|IllegalStateException failure){source.sendFailure(Component.literal(ModTranslations.text(source.getEntity() instanceof ServerPlayer p?Relations.get(p).language:"en","verity_companion.horror.config_failed")));return 0;}
 }
 private static int status(CommandSourceStack source){
  var o=HorrorSettings.get();ServerPlayer p=source.getEntity() instanceof ServerPlayer player?player:null;
  String language=p==null?"en":Relations.get(p).language;
  String text=ModTranslations.text(language,"verity_companion.horror.status",o.mode,o.bossMode,o.moodThreshold,o.chancePercent,o.cooldownSeconds,o.darkness,o.fog,o.sounds,o.stalker,ModTranslations.text(language,"verity_companion.horror_commands.companion_horror_mode_off_mood_random_boss"));
  source.sendSuccess(()->Component.literal(text),false);return 1;
 }
}
