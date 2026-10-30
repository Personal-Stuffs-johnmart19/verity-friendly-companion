package dev.johnmart19.veritycompanion;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
/** Server entry point shared by the explicit command and the player's authorized face tool. */
public final class ExpressionCommands {
 public static String set(ServerPlayer p,String face){
  if(face==null||!EmotionPolicy.FACES.contains(face))throw new IllegalArgumentException("Supported faces: auto,"+EmotionPolicy.choices());
  var body=CompanionPresence.select(p);
  if(body==null||body.level()!=p.level()||!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(body.getType()).toString().equals("verity:verity"))throw new IllegalStateException("No loaded Verity in your dimension; use /companion come first");
  PacketDistributor.sendToPlayer(p,new VoiceExpression(body.getId(),VoiceExpression.FACE_OVERRIDE,"face:"+face));
  return Relations.tr(p,"Лицо: ","Face: ")+face+Relations.tr(p,". Только на твоём экране; до /companion face auto или отключения. Настроение отношений не меняется.",". Personal display until /companion face auto or disconnect. Relationship mood is unchanged.");
 }
}
