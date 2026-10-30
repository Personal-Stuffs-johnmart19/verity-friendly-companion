package dev.johnmart19.veritycompanion;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.common.extensions.ILevelExtension;

/** Optional, read-only integrations through fixed public mod APIs. No model-selected reflection. */
final class PackAdapters {
 static void requireAccess(net.minecraft.server.level.ServerPlayer player,BlockPos pos){
  if(!ModList.get().isLoaded("mekanism"))return;
  try{Class<?> api=Class.forName("mekanism.api.security.IBlockSecurityUtils");Object security=api.getField("INSTANCE").get(null);boolean allowed=(boolean)api.getMethod("canAccess",net.minecraft.world.entity.player.Player.class,net.minecraft.world.level.Level.class,BlockPos.class).invoke(security,player,player.serverLevel(),pos);if(!allowed)throw new IllegalArgumentException("Mekanism security denies this player access");}catch(ReflectiveOperationException e){throw new IllegalArgumentException("Cannot verify Mekanism block security; inspection unavailable");}
 }
 static String read(ServerLevel level,BlockPos pos){StringBuilder out=new StringBuilder();Object block=level.getBlockEntity(pos);
  if(block!=null&&ModList.get().isLoaded("create"))try{
   Class<?> api=Class.forName("com.simibubi.create.content.kinetics.base.KineticBlockEntity");if(api.isInstance(block))out.append("\nCreate: RPM=").append(api.getMethod("getSpeed").invoke(block)).append(" theoreticalRPM=").append(api.getMethod("getTheoreticalSpeed").invoke(block)).append(" overstressed=").append(api.getMethod("isOverStressed").invoke(block)).append(" hasSource=").append(api.getMethod("hasSource").invoke(block));
  }catch(ReflectiveOperationException e){out.append("\nCreate adapter unavailable: API differs; do not infer RPM.");}
  if(ModList.get().isLoaded("mekanism"))try{
   Object multi=Class.forName("mekanism.common.capabilities.Capabilities").getField("CHEMICAL").get(null);BlockCapability cap=(BlockCapability)multi.getClass().getMethod("block").invoke(multi);Class<?> api=Class.forName("mekanism.api.chemical.IChemicalHandler"),stackApi=Class.forName("mekanism.api.chemical.ChemicalStack");
   for(Direction side:new Direction[]{null,Direction.DOWN,Direction.UP,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST}){Object handler=((ILevelExtension)(Object)level).getCapability(cap,pos,side);if(handler==null)continue;int count=(int)api.getMethod("getChemicalTanks").invoke(handler);out.append("\nMekanism chemicals side=").append(side==null?"internal (player security checked)":side).append(" tanks=").append(count);for(int i=0;i<Math.min(32,count);i++){Object stack=api.getMethod("getChemicalInTank",int.class).invoke(handler,i);out.append(" [").append(stackApi.getMethod("getTypeRegistryName").invoke(stack)).append(' ').append(stackApi.getMethod("getAmount").invoke(stack)).append('/').append(api.getMethod("getChemicalTankCapacity",int.class).invoke(handler,i)).append(']');}}
  }catch(ReflectiveOperationException e){out.append("\nMekanism chemical adapter unavailable: API differs; do not infer empty tanks.");}
  if(block!=null&&ModList.get().isLoaded("ae2"))try{
   Class<?> host=Class.forName("appeng.api.networking.IInWorldGridNodeHost"),nodeApi=Class.forName("appeng.api.networking.IGridNode");if(host.isInstance(block)){Object node=null;for(Direction side:Direction.values()){node=host.getMethod("getGridNode",Direction.class).invoke(block,side);if(node!=null)break;}if(node==null)out.append("\nAE2: no exposed grid node");else out.append("\nAE2: active=").append(nodeApi.getMethod("isActive").invoke(node)).append(" powered=").append(nodeApi.getMethod("isPowered").invoke(node)).append(" channelRequirementMet=").append(nodeApi.getMethod("meetsChannelRequirements").invoke(node)).append(" channelsUsed=").append(nodeApi.getMethod("getUsedChannels").invoke(node)).append('/').append(nodeApi.getMethod("getMaxChannels").invoke(node));}
  }catch(ReflectiveOperationException e){out.append("\nAE2 adapter unavailable: API differs; do not infer network state.");}
  return out.toString();
 }
}
