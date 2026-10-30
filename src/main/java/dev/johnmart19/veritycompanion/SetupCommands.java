package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Explicit host diagnostics; all I/O runs off the game thread. */
final class SetupCommands {
 static void login(net.minecraft.server.level.ServerPlayer player){
  if(!player.hasPermissions(2))return;
  String language=Relations.get(player).language;
  java.util.concurrent.CompletableFuture.supplyAsync(()->report(language)).thenAccept(result->player.getServer().execute(()->{if(!player.hasDisconnected())player.sendSystemMessage(Component.literal(result+"\n/companion setup"));}));
 }
 static int check(CommandSourceStack source){
  source.sendSuccess(()->Component.literal(ModTranslations.text(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p?Relations.get(p).language:"en","verity_companion.setup.checking")),false);
  java.util.concurrent.CompletableFuture.supplyAsync(()->report(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p?Relations.get(p).language:"en"))
   .thenAccept(result->source.getServer().execute(()->source.sendSuccess(()->Component.literal(result),false)));
  return 1;
 }
 static String report(boolean ru){return report(ru?"ru":"en");}
 static String report(String language){
  try{
   JsonObject health=ServerBridge.health();
   if(!health.has("setup_issues"))return ModTranslations.text(language,"verity_companion.setup_commands.update_the_server_bridge_code_this_version");
   StringBuilder result=new StringBuilder(ServerBridge.nativeRuntime()?(ModTranslations.text(language,"verity_companion.setup_commands.native_server_ai_providers_are_available_the")):(ModTranslations.text(language,"verity_companion.setup_commands.server_bridge_is_running")));
   JsonArray issues=health.getAsJsonArray("setup_issues");
   if(health.has("dependencies_ready")&&!health.get("dependencies_ready").getAsBoolean())result.append(ModTranslations.text(language,"verity_companion.setup_commands.voice_dependencies_are_incomplete_host_run_verity"));
   for(JsonElement issue:issues){JsonObject problem=issue.getAsJsonObject();String hint=SetupGuidance.message(problem.get("code").getAsString(),language);if(hint!=null){result.append('\n');JsonArray features=problem.getAsJsonArray("features");if(features!=null&&features.size()==1&&features.get(0).getAsString().equals("search"))result.append(ModTranslations.text(language,"verity_companion.setup_commands.optional_google_web_search"));result.append(hint);}}
   if(health.has("chat_provider")&&health.get("chat_provider").getAsString().equals("local")&&(!health.has("chat_model")||health.get("chat_model").isJsonNull()))result.append(ModTranslations.text(language,"verity_companion.setup_commands.no_local_model_selected_yet_llm_models"));
   if(issues.isEmpty())result.append(ModTranslations.text(language,"verity_companion.setup_commands.configuration_is_complete_this_does_not_test"));
   return result.toString();
  }catch(Exception failure){String hint=ServerBridge.setupFailure(failure,language);return hint!=null?hint:ModTranslations.text(language,"verity_companion.setup_commands.setup_check_failed_run_verity_service_setup");}
 }
}
