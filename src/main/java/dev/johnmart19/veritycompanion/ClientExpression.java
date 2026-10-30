package dev.johnmart19.veritycompanion;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.ResourceLocation;
/** Client-only face selection; never writes entity data or relationship state. */
public final class ClientExpression {
 private static volatile VoiceExpression pending;
 private static Entity active,overrideEntity;
 private static String face="auto";
 private static final ExpressionPresentation presentation=new ExpressionPresentation();
 private static final java.util.concurrent.atomic.AtomicLong sequence=new java.util.concurrent.atomic.AtomicLong();
 private static boolean friendly(Entity e){return e!=null&&net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals("verity:verity");}
 public static void prepare(VoiceExpression value){
  if(!value.accepts(ClientVoice.currentEpoch()))return;
  if(value.epoch()==VoiceExpression.FACE_OVERRIDE){Minecraft.getInstance().execute(()->{
   var level=Minecraft.getInstance().level;if(level==null||!value.emotion().startsWith("face:"))return;
   Entity e=level.getEntity(value.entity());String requested=value.emotion().substring(5);
   if(!friendly(e)||!EmotionPolicy.FACES.contains(requested))return;
   overrideEntity=requested.equals("auto")?null:e;face=requested;
  });return;}
  if(value.epoch()==VoiceExpression.TEXT_REPLY||value.epoch()==VoiceExpression.OBSERVED_REPLY){Minecraft.getInstance().execute(()->{
   if(!value.accepts(ClientVoice.currentEpoch()))return;var level=Minecraft.getInstance().level;if(level==null)return;Entity e=level.getEntity(value.entity());
   if(!friendly(e)||!presentation.acceptsPreview(value.epoch()==VoiceExpression.OBSERVED_REPLY,System.nanoTime()))return;sequence.incrementAndGet();active=e;presentation.preview(value.emotion(),System.nanoTime());
  });return;}
  pending=value;
 }
 public static void interrupt(){pending=null;long ticket=sequence.incrementAndGet();Minecraft.getInstance().execute(()->{if(ticket==sequence.get()){active=null;presentation.clear();}});}
 /** A personal manual override lasts until auto or disconnect. */
 public static void clear(){interrupt();Minecraft.getInstance().execute(()->{active=null;presentation.clear();overrideEntity=null;face="auto";});}
 public static void begin(int epoch){
  VoiceExpression value=pending;pending=null;if(value==null||value.epoch()!=epoch)return;
  long ticket=sequence.get();Minecraft.getInstance().execute(()->{
   if(ticket!=sequence.get())return;var level=Minecraft.getInstance().level;if(level==null)return;
   Entity e=level.getEntity(value.entity());if(!friendly(e))return;
   active=e;presentation.begin(value.emotion(),System.nanoTime());
  });
 }
 public static void end(){long ticket=sequence.incrementAndGet();Minecraft.getInstance().execute(()->{if(ticket==sequence.get())presentation.end(System.nanoTime());});}
 /** Injected into original sphere renderer; audio cursor controls only the mouth. */
 public static ResourceLocation texture(Entity e){
  long now=System.nanoTime();boolean automatic=e==active&&presentation.visible(now);
  if(e==overrideEntity&&!face.equals("auto"))return ClientFaceTextures.texture(face,automatic?presentation.mouth(ClientVoice.mouthOpen(),now):0);
  if(automatic)return ClientFaceTextures.texture(presentation.emotion(),presentation.mouth(ClientVoice.mouthOpen(),now));
  return ResourceLocation.fromNamespaceAndPath("verity","textures/entity/default.png");
 }
}
