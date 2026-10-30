package dev.johnmart19.veritycompanion;

import java.util.*;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

/** Uses the real dispatcher and the requesting player's permissions, never console privileges. */
public final class ServerCommandTools {
 static final Set<String> ADMIN=Set.of("op","deop","stop","restart","save-off","whitelist","ban","ban-ip","pardon","pardon-ip","kick","reload","perf","jfr","debug","function","schedule","llm","companion");
 static net.minecraft.commands.CommandSourceStack source(ServerPlayer p){var s=p.createCommandSourceStack();return s.withPermission(s.hasPermission(2)?2:s.hasPermission(1)?1:0);}
 static String details(ServerPlayer p,String path,int offset){var dispatcher=p.getServer().getCommands().getDispatcher();com.mojang.brigadier.tree.CommandNode<net.minecraft.commands.CommandSourceStack> node=dispatcher.getRoot();String clean=path.strip();if(clean.startsWith("/"))clean=clean.substring(1);if(clean.isEmpty()||clean.length()>200)throw new IllegalArgumentException("Provide a command name or literal subcommand path");var source=source(p);for(String word:clean.split("\\s+")){node=node.getChild(word);if(node==null||!node.canUse(source))return "Unknown or inaccessible command path: "+clean;}String[] usages=dispatcher.getAllUsage(node,source,true);int start=Math.max(0,offset),end=Math.min(usages.length,start+40);StringBuilder out=new StringBuilder("Command /"+clean+"; branches="+usages.length+"; offset="+start+"\n");if(usages.length==0)out.append('/').append(clean).append(" (no subcommands)\n");for(int i=start;i<end;i++)out.append('/').append(clean).append(' ').append(usages[i]).append('\n');if(end<usages.length)out.append("More branches available; request offset=").append(end);return out.toString();}
 static String catalog(ServerPlayer p,String query){var source=source(p);StringBuilder out=new StringBuilder();p.getServer().getCommands().getDispatcher().getSmartUsage(p.getServer().getCommands().getDispatcher().getRoot(),source).entrySet().stream().filter(entry->(entry.getKey().getName()+" "+entry.getValue()).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).sorted(Comparator.comparing(entry->entry.getKey().getName())).limit(100).forEach(entry->out.append('/').append(entry.getValue()).append('\n'));return out.isEmpty()?"No accessible commands match. Commands use the requesting player's actual permissions.":out.toString();}
 static String validate(String input){String command=input.strip();if(command.startsWith("/"))command=command.substring(1);if(command.isBlank()||command.length()>2000||command.chars().anyMatch(c->c=='\n'||c=='\r'||c==0))throw new IllegalArgumentException("Expected one command, maximum 2000 characters");String root=command.split("\\s+",2)[0].toLowerCase(Locale.ROOT);if(root.contains(":"))root=root.substring(root.lastIndexOf(':')+1);if(root.equals("kill")||root.equals("damage"))throw new IllegalArgumentException("Use clear_nearby_mobs for explicitly requested scoped combat; raw selectors cannot protect players and pets");if(root.equals("effect"))throw new IllegalArgumentException("Use player_effects/apply_effect/remove_effect tools for player scope, effect checks and single undo");if(ADMIN.contains(root))throw new IllegalArgumentException("Server administration requires a human administrator");return command;}
 static boolean explicit(String original){String text=original.strip().toLowerCase(Locale.ROOT).replaceFirst("^(?:verity|верити)[\\s,:!]+","");if(text.matches("(?s).*(не выполняй|не запускай|не меняй|не делай|do not |don't ).*"))return false;return text.startsWith("/")||text.matches("(?s)^(?:please\\s+|пожалуйста[, ]+)?(?:run|execute|use|give|grant|teleport|tp|set|change|make|spawn|summon|enchant|clear|remove|kill|fill|place|build|выполни|запусти|используй|дай|выдай|добавь|телепортируй|перемести|установи|измени|сделай|призови|зачаруй|очисти|убери|удали|поставь|построй)(?:\\b|[ ,:]).*");}
 /** Redirected command contexts include the real root after each execute/run or alias. */
 static void validateContexts(com.mojang.brigadier.context.CommandContextBuilder<net.minecraft.commands.CommandSourceStack> context){
  for(var current=context;current!=null;current=current.getChild()){
   String target=current.getRootNode().getName();if(!target.isBlank())validate(target);
   if(!current.getNodes().isEmpty())validate(current.getNodes().getFirst().getNode().getName());
   for(var parsed:current.getNodes()){
    var redirect=parsed.getNode().getRedirect();Set<com.mojang.brigadier.tree.CommandNode<net.minecraft.commands.CommandSourceStack>> seen=Collections.newSetFromMap(new IdentityHashMap<>());
    while(redirect!=null&&seen.add(redirect)){if(!redirect.getName().isBlank())validate(redirect.getName());redirect=redirect.getRedirect();}
   }
  }
 }
 static void validateActionIntent(String original,com.mojang.brigadier.context.CommandContextBuilder<net.minecraft.commands.CommandSourceStack> context){
  String request=original.toLowerCase(Locale.ROOT);
  for(var current=context;current!=null;current=current.getChild()){
   if(current.getNodes().isEmpty())continue;String root=current.getNodes().getFirst().getNode().getName();if(root.contains(":"))root=root.substring(root.lastIndexOf(':')+1);
   String required=switch(root){case "kill","damage"->"(?s).*(kill|damage|убей|убить|урон|уничтож).*";case "clear"->"(?s).*(clear|очист|удали.*инвентар|убери.*инвентар).*";case "fill","clone","setblock"->"(?s).*(fill|clone|setblock|build|place|постр|постав|заполни|замени|клонир|скопир).*";default->null;};
   if(required!=null&&!request.matches(required))throw new IllegalArgumentException("This command requires an explicit request for its destructive/building effect");
  }
 }
 static String run(ServerPlayer p,JsonObject args,String original){if(!Relations.helps(p))throw new IllegalStateException("Assistance paused for this player");String command=validate(CompanionBrain.string(args,"command",""));if(!explicit(original.replaceFirst("(?iu)^\\s*"+java.util.regex.Pattern.quote(Relations.name(p))+"[\\s,:!]+","")))throw new IllegalArgumentException("Explicitly request the gameplay action or command in this message");var source=source(p);var dispatcher=p.getServer().getCommands().getDispatcher();var parsed=dispatcher.parse(command,source);if(parsed.getReader().canRead()||!parsed.getExceptions().isEmpty())throw new IllegalArgumentException("Invalid or unavailable command. Query server_commands first.");validateContexts(parsed.getContext());validateActionIntent(original,parsed.getContext());try{int result=dispatcher.execute(parsed);return "Command executed as "+p.getGameProfile().getName()+"; result="+result+". Command feedback is sent to the player. Report only this actual outcome.";}catch(com.mojang.brigadier.exceptions.CommandSyntaxException error){throw new IllegalArgumentException(error.getRawMessage().getString());}}
}
