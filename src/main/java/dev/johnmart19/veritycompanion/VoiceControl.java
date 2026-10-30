package dev.johnmart19.veritycompanion;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
public record VoiceControl(int epoch) implements CustomPacketPayload {
 public static final Type<VoiceControl> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("verity_companion","interrupt"));
 public static final StreamCodec<RegistryFriendlyByteBuf,VoiceControl> CODEC=new StreamCodec<>(){
  public VoiceControl decode(RegistryFriendlyByteBuf b){return new VoiceControl(b.readVarInt());}
  public void encode(RegistryFriendlyByteBuf b,VoiceControl p){b.writeVarInt(p.epoch);}
 };
 public Type<VoiceControl> type(){return TYPE;}
}
