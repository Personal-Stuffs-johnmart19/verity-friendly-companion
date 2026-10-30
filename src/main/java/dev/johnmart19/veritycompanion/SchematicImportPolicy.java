package dev.johnmart19.veritycompanion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Schema boundary over Minecraft's NBT decoder, not an independent NBT parser. */
final class SchematicImportPolicy {
 private SchematicImportPolicy(){}
 record Position(int x,int y,int z){}
 static NativeSchematicAdapter.Plan decode(CompoundTag root,int maximum,Function<CompoundTag,String> stateDecoder){
  return decode(root,maximum,stateDecoder,false);
 }
 static NativeSchematicAdapter.Plan decode(CompoundTag root,int maximum,Function<CompoundTag,String> stateDecoder,boolean metadataPreflight){
  if(root.contains("palettes"))throw new IllegalArgumentException("Multiple/random palettes are unsupported; export one deterministic palette");
  ListTag size=list(root,"size",Tag.TAG_INT);if(size.size()!=3)throw new IllegalArgumentException("Structure size must contain three integers");
  int width=size.getInt(0),height=size.getInt(1),length=size.getInt(2);BuildBudget.requireVolume(width,length,height,maximum);
  if(root.contains("entities")&&!list(root,"entities",Tag.TAG_COMPOUND).isEmpty())throw new IllegalArgumentException("Entity-bearing schematic refused; export blocks only");
  ListTag palette=list(root,"palette",Tag.TAG_COMPOUND),blocks=list(root,"blocks",Tag.TAG_COMPOUND);
  if(palette.isEmpty()||palette.size()>Math.min(maximum,65536))throw new IllegalArgumentException("Schematic palette is empty or exceeds its resource budget");
  if(blocks.size()>maximum)throw new IllegalArgumentException("Schematic block records exceed configured maxBlocksPerBuild");
  List<String> states=new ArrayList<>(palette.size());for(Tag entry:palette)states.add(stateDecoder.apply((CompoundTag)entry));
  List<StructuredBlueprint.Cell> cells=new ArrayList<>();Set<Position> positions=new HashSet<>();int skipped=0;
  for(Tag value:blocks){
   CompoundTag block=(CompoundTag)value;
   if(block.contains("nbt")&&!metadataPreflight)throw new IllegalArgumentException("Block-entity NBT refused; inventories, commands and machine data are not imported");
   if(!block.contains("state",Tag.TAG_INT))throw new IllegalArgumentException("Block palette index must be an integer");
   int index=block.getInt("state");if(index<0||index>=states.size())throw new IllegalArgumentException("Block palette index is out of bounds");
   ListTag pos=list(block,"pos",Tag.TAG_INT);if(pos.size()!=3)throw new IllegalArgumentException("Block position must contain three integers");
   int x=pos.getInt(0),y=pos.getInt(1),z=pos.getInt(2);
   if(x<0||x>=width||y<0||y>=height||z<0||z>=length)throw new IllegalArgumentException("Schematic block lies outside declared bounds");
   if(!positions.add(new Position(x,y,z)))throw new IllegalArgumentException("Duplicate schematic block position");
   String state=states.get(index);String id=state.split("\\[",2)[0];
   if(Set.of("minecraft:air","minecraft:cave_air","minecraft:void_air","minecraft:structure_void").contains(id)){skipped++;continue;}
   cells.add(new StructuredBlueprint.Cell(x,y,z,state));
  }
  if(cells.isEmpty())throw new IllegalArgumentException("Schematic contains no placeable blocks");
  return new NativeSchematicAdapter.Plan(List.copyOf(cells),width,length,height,skipped,0,0,0,"Native Minecraft/Create NBT, registered block states only; air omitted; no entities or block-entity data. Placement still requires the existing bounded permission-checked build job.");
 }
 static ListTag list(CompoundTag root,String name,int elementType){
  if(!root.contains(name,Tag.TAG_LIST))throw new IllegalArgumentException("Missing/invalid schematic list: "+name);
  ListTag result=(ListTag)root.get(name);
  if(!result.isEmpty()&&result.getElementType()!=elementType)throw new IllegalArgumentException("Invalid schematic list element type: "+name);
  return result;
 }
}
