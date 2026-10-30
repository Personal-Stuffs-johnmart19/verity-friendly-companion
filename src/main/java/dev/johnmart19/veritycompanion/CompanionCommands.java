package dev.johnmart19.veritycompanion;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import com.mojang.brigadier.arguments.BoolArgumentType;

public final class CompanionCommands {
 static String rename(ServerPlayer p,String name){String clean=name.strip();if(!clean.matches("[\\p{L}\\p{N} _-]{1,24}"))throw new IllegalArgumentException(Relations.tr(p,"Имя: от 1 до 24 букв, цифр, пробелов, дефисов или подчёркиваний.","Name: 1–24 letters, digits, spaces, hyphens or underscores."));synchronized(Relations.class){Relations.get(p).alias=clean;Relations.save(p);}Relations.refresh(p);return Relations.tr(p,"Теперь для тебя я ","For you, my name is now ")+clean;}
 static void register(RegisterCommandsEvent event){TtsCommands.register(event);LlmCommands.register(event);com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack> status=c->show(c.getSource().getPlayerOrException());event.getDispatcher().register(Commands.literal("companion")
  .executes(status)
  .then(HorrorCommands.node())
  .then(LanguageCommands.node())
  .then(Commands.literal("help").executes(c->help(c.getSource().getPlayerOrException())))
  .then(Commands.literal("schematics").executes(c->{ServerPlayer p=c.getSource().getPlayerOrException();p.sendSystemMessage(Component.literal(SchematicLibrary.list(p)));return 1;}))
  .then(Commands.literal("blueprints").executes(c->{c.getSource().sendSuccess(()->Component.literal(BuildExamples.catalog("")),false);return 1;}).then(Commands.argument("query",StringArgumentType.greedyString()).executes(c->{c.getSource().sendSuccess(()->Component.literal(BuildExamples.catalog(StringArgumentType.getString(c,"query"))),false);return 1;})))
  .then(Commands.literal("face").then(Commands.argument("face",StringArgumentType.word()).suggests((c,b)->{for(String value:EmotionPolicy.FACES)b.suggest(value);return b.buildFuture();}).executes(c->{ServerPlayer p=c.getSource().getPlayerOrException();try{p.sendSystemMessage(Component.literal(ExpressionCommands.set(p,StringArgumentType.getString(c,"face"))));return 1;}catch(IllegalArgumentException|IllegalStateException error){c.getSource().sendFailure(Component.literal(error.getMessage()));return 0;}})))
  .then(Commands.literal("girl").executes(c->gender(c.getSource().getPlayerOrException(),"female")))
  .then(Commands.literal("boy").executes(c->gender(c.getSource().getPlayerOrException(),"male")))
  .then(Commands.literal("role").then(Commands.argument("role",StringArgumentType.word()).suggests((c,b)->{for(String value:new String[]{"orb","protector","anime"})b.suggest(value);return b.buildFuture();}).executes(c->role(c.getSource().getPlayerOrException(),StringArgumentType.getString(c,"role")))))
  .then(Commands.literal("gender").executes(c->{ServerPlayer p=c.getSource().getPlayerOrException();p.sendSystemMessage(Component.literal("Verity: "+Relations.get(p).gender+"; /companion gender male|female|neutral. /tts — voice"));return 1;}).then(Commands.argument("gender",StringArgumentType.word()).suggests((c,b)->{for(String value:new String[]{"male","female","neutral"})b.suggest(value);return b.buildFuture();}).executes(c->gender(c.getSource().getPlayerOrException(),StringArgumentType.getString(c,"gender")))))
  .then(Commands.literal("find").executes(c->presence(c.getSource().getPlayerOrException(),"find")))
  .then(Commands.literal("come").executes(c->presence(c.getSource().getPlayerOrException(),"come")))
  .then(Commands.literal("summon").executes(c->presence(c.getSource().getPlayerOrException(),"summon")))
  .then(Commands.literal("box").executes(c->presence(c.getSource().getPlayerOrException(),"box")))
  .then(Commands.literal("stay").executes(c->presence(c.getSource().getPlayerOrException(),"stay")))
  .then(Commands.literal("follow").executes(c->follow(c.getSource().getPlayerOrException(),true)).then(Commands.literal("on").executes(c->follow(c.getSource().getPlayerOrException(),true))).then(Commands.literal("off").executes(c->follow(c.getSource().getPlayerOrException(),false))))
  .then(Commands.literal("mood").executes(status)
   .then(Commands.literal("auto").executes(c->display(c.getSource().getPlayerOrException(),0)))
   .then(Commands.literal("on").executes(c->display(c.getSource().getPlayerOrException(),1)))
   .then(Commands.literal("off").executes(c->display(c.getSource().getPlayerOrException(),2))))
  .then(Commands.literal("rename").then(Commands.argument("name",StringArgumentType.greedyString()).executes(c->{ServerPlayer p=c.getSource().getPlayerOrException();try{p.sendSystemMessage(Component.literal(rename(p,StringArgumentType.getString(c,"name"))));return 1;}catch(IllegalArgumentException invalid){c.getSource().sendFailure(Component.literal(invalid.getMessage()));return 0;}})))
  .then(Commands.literal("mode").requires(s->s.hasPermission(2)).then(Commands.argument("enabled",BoolArgumentType.bool()).executes(c->configure(c.getSource(),"relationships",BoolArgumentType.getBool(c,"enabled")))))
  .then(Commands.literal("retaliation").requires(s->s.hasPermission(2)).then(Commands.argument("enabled",BoolArgumentType.bool()).executes(c->configure(c.getSource(),"retaliation",BoolArgumentType.getBool(c,"enabled")))))
  .then(Commands.literal("reset").requires(s->s.hasPermission(2)).executes(c->{ServerPlayer p=c.getSource().getPlayerOrException();RivalController.remove(p.getUUID());synchronized(Relations.class){Relations.get(p).state=new MoodPolicy.State();Relations.save(p);}Relations.refresh(p);return show(p);})));}
 static int help(ServerPlayer p){p.sendSystemMessage(Component.literal(Relations.tr(p,
  "Напиши Verity обычным чатом. Настройки: /tts — голос; /llm — модели (OP); /companion horror — хоррор.\nПомощник: /companion find|come|summon|box|stay; follow [on|off]. Summon может создать второго Verity, если старый далеко; незагруженные области не проверяются.\nЯзык: /companion language en|ru|auto. Персона: /companion gender male|female|neutral; role orb|protector|anime; rename <имя>; face <emotion|auto>; mood [auto|on|off].\nСправочники: /companion schematics; blueprints [запрос]. Настройки OP: /companion mode <true|false>; retaliation <true|false>; reset.",
  "Talk to Verity in ordinary chat. Settings: /tts — voice; /llm — models (OP); /companion horror — horror.\nCompanion: /companion find|come|summon|box|stay; follow [on|off]. Summon can create an extra Verity if the old one is far away; unloaded areas are not searched.\nLanguage: /companion language en|ru|auto. Persona: /companion gender male|female|neutral; role orb|protector|anime; rename <name>; face <emotion|auto>; mood [auto|on|off].\nCatalogs: /companion schematics; blueprints [query]. OP settings: /companion mode <true|false>; retaliation <true|false>; reset.")));return 1;}
 static int display(ServerPlayer p,int value){synchronized(Relations.class){Relations.get(p).display=value;Relations.save(p);}Relations.refresh(p);return show(p);}
 static int gender(ServerPlayer p,String value){return gender(p,value,true);}
 static int gender(ServerPlayer p,String value,boolean notify){try{String gender=PersonaPolicy.normalize(value);synchronized(Relations.class){Relations.get(p).gender=gender;Relations.save(p);}if(notify)p.sendSystemMessage(Component.literal(Relations.tr(p,"Обращение Verity для тебя: ","Your Verity persona: ")+gender+Relations.tr(p,". Голос выбирается отдельно через /tts.",". Select the voice separately with /tts.")));return 1;}catch(IllegalArgumentException invalid){if(notify)p.sendSystemMessage(Component.literal("male / female / neutral"));return 0;}}
 static int role(ServerPlayer p,String value){return role(p,value,true);}
 static int role(ServerPlayer p,String value,boolean notify){try{String role=PersonaPolicy.role(value);synchronized(Relations.class){Relations.get(p).role=role;Relations.save(p);}if(notify)p.sendSystemMessage(Component.literal(Relations.tr(p,"Роль Verity: ","Verity role: ")+role+Relations.tr(p,". Внешняя 3D-модель и голос выбираются отдельно.",". Visual model and voice are configured separately.")));return 1;}catch(IllegalArgumentException invalid){if(notify)p.sendSystemMessage(Component.literal("orb / protector / anime"));return 0;}}
 static int follow(ServerPlayer p,boolean enabled){p.sendSystemMessage(Component.literal(CompanionFollow.set(p,enabled)));return 1;}
 static int presence(ServerPlayer p,String action){try{String result=switch(action){case "find"->CompanionPresence.find(p);case "come"->CompanionPresence.bring(p,false,false);case "summon"->CompanionPresence.bring(p,true,false);case "box"->CompanionPresence.bring(p,true,true);case "stay"->CompanionPresence.waitHere(p);default->throw new IllegalArgumentException("Unknown companion command");};p.sendSystemMessage(Component.literal(result));return 1;}catch(Exception error){p.sendSystemMessage(Component.literal(error.getMessage()));return 0;}}
 static int configure(net.minecraft.commands.CommandSourceStack source,String option,boolean enabled){
  CompanionBrain.init();if(option.equals("retaliation"))return HorrorCommands.legacyBoss(source,enabled);
  synchronized(CompanionBrain.class){
   var updated=CompanionBrain.JSON.fromJson(CompanionBrain.JSON.toJson(CompanionBrain.settings),CompanionBrain.Settings.class);
   if(option.equals("relationships"))updated.relationships=enabled;else throw new IllegalArgumentException("Unknown companion option");
   try{SafeJsonStore.save(CompanionBrain.CONFIG,CompanionBrain.JSON,CompanionBrain.Settings.class,updated,CompanionBrain::validateSettings);}
   catch(java.io.IOException error){source.sendFailure(Component.literal("Cannot save companion configuration; setting unchanged."));return 0;}
   CompanionBrain.settings=updated;
  }
  HorrorController.configurationChanged(source.getServer());if(!enabled)RivalController.clear();for(ServerPlayer p:source.getServer().getPlayerList().getPlayers())Relations.refresh(p);source.sendSuccess(()->Component.literal("Verity: "+option+" = "+enabled),true);return 1;
 }
 static int show(ServerPlayer p){CompanionBrain.init();var e=Relations.get(p);p.sendSystemMessage(Component.literal(Relations.name(p)+Relations.tr(p,": настроение ",": mood ")+e.state.mood+"/100; "+Relations.tr(p,"помощь: ","assistance: ")+(Relations.helps(p)?Relations.tr(p,"да","yes"):Relations.tr(p,"пока нет","paused"))+". persona="+e.gender+"; role="+e.role+". /companion help; /llm; /tts"));return 1;}
}
