package dev.johnmart19.veritycompanion;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
public record VoiceExpression(int entity,int epoch,String emotion,int requestEpoch) implements CustomPacketPayload {
 public static final Type<VoiceExpression> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("verity_companion","expression"));
 public static final int FACE_OVERRIDE=Integer.MIN_VALUE;
 public static final int TEXT_REPLY=Integer.MIN_VALUE+1;
 public static final int OBSERVED_REPLY=Integer.MIN_VALUE+2;
 public VoiceExpression(int entity,int epoch,String emotion){this(entity,epoch,emotion,epoch);}
 public boolean accepts(int localEpoch){return epoch==FACE_OVERRIDE||epoch==OBSERVED_REPLY||((epoch==TEXT_REPLY?requestEpoch:epoch)==localEpoch);}
 public static final int MAX_EMOTION_LENGTH=32;
 public static final StreamCodec<RegistryFriendlyByteBuf,VoiceExpression> CODEC=StreamCodec.of((b,p)->{b.writeInt(p.entity());b.writeInt(p.epoch());b.writeInt(p.requestEpoch());b.writeUtf(EmotionPolicy.wire(p.emotion()),MAX_EMOTION_LENGTH);},b->{int entity=b.readInt(),epoch=b.readInt(),request=b.readInt();return new VoiceExpression(entity,epoch,EmotionPolicy.wire(b.readUtf(MAX_EMOTION_LENGTH)),request);});
 public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
