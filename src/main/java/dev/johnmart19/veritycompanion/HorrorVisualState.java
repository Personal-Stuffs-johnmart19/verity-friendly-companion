package dev.johnmart19.veritycompanion;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-to-owner presentation only; never changes the world or client preferences. */
public record HorrorVisualState(int entityId,int durationTicks,boolean fog,boolean chase) implements CustomPacketPayload {
 public HorrorVisualState {durationTicks=Math.clamp(durationTicks,0,1200);if(entityId<0)entityId=-1;if(entityId<0)chase=false;if(durationTicks==0){fog=false;chase=false;}}
 public static final Type<HorrorVisualState> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("verity_companion","horror_state"));
 public static final StreamCodec<RegistryFriendlyByteBuf,HorrorVisualState> CODEC=StreamCodec.of(
  (b,p)->{b.writeInt(p.entityId());b.writeInt(p.durationTicks());b.writeBoolean(p.fog());b.writeBoolean(p.chase());},
  b->new HorrorVisualState(b.readInt(),b.readInt(),b.readBoolean(),b.readBoolean()));
 public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
