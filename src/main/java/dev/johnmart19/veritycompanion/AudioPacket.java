package dev.johnmart19.veritycompanion;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record AudioPacket(boolean voice,UUID stream,int index,int count,int entity,int epoch,byte[] data) implements CustomPacketPayload {
 public static final Type<AudioPacket> MIC=new Type<>(ResourceLocation.fromNamespaceAndPath("verity_companion","microphone"));
 public static final Type<AudioPacket> VOICE=new Type<>(ResourceLocation.fromNamespaceAndPath("verity_companion","voice"));
 public AudioPacket {if(data.length>AudioFrames.CHUNK||count<1||count>AudioFrames.MAX_CHUNKS||index<0||index>=count)throw new IllegalArgumentException("Invalid audio packet");}
 public Type<AudioPacket> type(){return voice?VOICE:MIC;}
 public static StreamCodec<RegistryFriendlyByteBuf,AudioPacket> codec(boolean voice){return new StreamCodec<>(){
  public AudioPacket decode(RegistryFriendlyByteBuf b){return new AudioPacket(voice,b.readUUID(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readByteArray(AudioFrames.CHUNK));}
  public void encode(RegistryFriendlyByteBuf b,AudioPacket p){b.writeUUID(p.stream);b.writeVarInt(p.index);b.writeVarInt(p.count);b.writeVarInt(p.entity);b.writeVarInt(p.epoch);b.writeByteArray(p.data);}
 };}
 public static List<AudioPacket> split(boolean voice,byte[] bytes,int entity,int epoch){if(bytes.length<1||bytes.length>AudioFrames.MAX_BYTES)throw new IllegalArgumentException("Audio size unsupported");UUID id=UUID.randomUUID();int count=(bytes.length+AudioFrames.CHUNK-1)/AudioFrames.CHUNK;List<AudioPacket> out=new ArrayList<>();for(int i=0;i<count;i++)out.add(new AudioPacket(voice,id,i,count,entity,epoch,Arrays.copyOfRange(bytes,i*AudioFrames.CHUNK,Math.min(bytes.length,(i+1)*AudioFrames.CHUNK))));return out;}
}
