package dev.johnmart19.veritycompanion;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
/** Server entry point shared by the explicit command and the player's authorized face tool. */
public final class ExpressionCommands {
 public static String set(ServerPlayer p,String face){
  if(face==null||!EmotionPolicy.FACES.contains(face))throw new IllegalArgumentException(ModTranslations.text(Relations.get(p).language,"verity_companion.expression.supported",EmotionPolicy.choices()));
  var body=CompanionPresence.select(p);
  if(body==null||body.level()!=p.level()||!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(body.getType()).toString().equals("verity:verity"))throw new IllegalStateException(ModTranslations.text(Relations.get(p).language,"verity_companion.expression.no_body"));
  PacketDistributor.sendToPlayer(p,new VoiceExpression(body.getId(),VoiceExpression.FACE_OVERRIDE,"face:"+face));
  return ModTranslations.text(Relations.get(p).language,"verity_companion.expression_commands.face")+face+ModTranslations.text(Relations.get(p).language,"verity_companion.expression_commands.personal_display_until_companion_face_auto_or");
 }
}
