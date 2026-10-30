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
  root.then(Commands.literal("stop").executes(c->{ServerPlayer p=c.getSource().getPlayerOrException();HorrorController.stop(p);p.sendSystemMessage(Component.literal(Relations.tr(p,"Текущий хоррор-эпизод остановлен. Режим сервера не изменён.","Your current horror encounter stopped. Server mode is unchanged.")));return 1;}));
  return root;
 }
 static int legacyBoss(CommandSourceStack source,boolean enabled){return change(source,o->{o.bossMode=enabled?"mood":"off";if(enabled&&o.mode.equals("off"))o.mode="mood";});}
 private static int change(CommandSourceStack source,java.util.function.Consumer<HorrorSettings.Options> edit){
  try{HorrorSettings.update(edit);HorrorController.configurationChanged(source.getServer());source.sendSuccess(()->Component.literal(source.getEntity() instanceof ServerPlayer p?Relations.tr(p,"Настройки хоррора Verity сохранены.","Verity horror configuration saved."):"Verity horror configuration saved."),true);return status(source);}catch(IllegalArgumentException|IllegalStateException failure){source.sendFailure(Component.literal(failure.getMessage()));return 0;}
 }
 private static int status(CommandSourceStack source){
  var o=HorrorSettings.get();ServerPlayer p=source.getEntity() instanceof ServerPlayer player?player:null;
  boolean ru=p!=null&&Relations.get(p).language.equals("ru");
  String text=(ru?"Хоррор: ":"Horror: ")+o.mode+"; boss="+o.bossMode+"; mood<="+o.moodThreshold+"; chance="+o.chancePercent+"%; cooldown="+o.cooldownSeconds+"s; darkness="+o.darkness+", fog="+o.fog+", sounds="+o.sounds+", stalker="+o.stalker+". "+(ru?"/companion horror mode off|mood|random; boss off|mood|random; event <имя> on|off; threshold 0 — только при настроении 0. Изменения — оператору; stop останавливает твой эпизод.":"/companion horror mode off|mood|random; boss off|mood|random; event <name> on|off; threshold 0 means mood zero only. Changes require operator; stop ends your encounter.");
  source.sendSuccess(()->Component.literal(text),false);return 1;
 }
}
