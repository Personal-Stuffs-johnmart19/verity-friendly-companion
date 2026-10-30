package dev.johnmart19.veritycompanion;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
public record VoiceSettings(boolean enabled) implements CustomPacketPayload {
 public static final Type<VoiceSettings> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("verity_companion","voice_settings"));
 public static final StreamCodec<RegistryFriendlyByteBuf,VoiceSettings> CODEC=StreamCodec.of((b,p)->b.writeBoolean(p.enabled()),b->new VoiceSettings(b.readBoolean()));
 public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
