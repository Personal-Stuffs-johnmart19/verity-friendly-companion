package dev.johnmart19.veritycompanion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Reuses Minecraft/Create's native structure loader without ever calling placeInWorld. */
public final class NativeSchematicAdapter {
 private NativeSchematicAdapter(){}
 static final long MAX_COMPRESSED_BYTES=8L*1024*1024,MAX_NBT_BYTES=32L*1024*1024;
 private static final Set<String> UNSAFE=Set.of("minecraft:tnt","minecraft:fire","minecraft:soul_fire","minecraft:nether_portal","minecraft:end_portal","minecraft:end_gateway","minecraft:barrier","minecraft:light","minecraft:moving_piston","minecraft:piston_head","minecraft:structure_block","minecraft:jigsaw");
 public enum Mode { STRICT, DECORATIVE }
 public record Plan(List<StructuredBlueprint.Cell> cells,int width,int length,int height,int skippedAir,int replacedJigsaws,int strippedBlockEntities,int skippedUnsafe,String description){}

 /** approvedDirectory is chosen by server code; fileName is a basename, never a model-supplied path or URL. */
 public static Plan read(Path approvedDirectory,String fileName,HolderLookup<Block> lookup,int maximum)throws IOException{
  return read(approvedDirectory,fileName,lookup,maximum,Mode.STRICT);
 }
 public static Plan read(Path approvedDirectory,String fileName,HolderLookup<Block> lookup,int maximum,Mode mode)throws IOException{
  return decode(readTag(approvedDirectory,fileName),lookup,maximum,mode);
 }
 static Plan decode(CompoundTag input,HolderLookup<Block> lookup,int maximum,Mode mode)throws IOException{
  if(input.contains("DataVersion")&&(!input.contains("DataVersion",Tag.TAG_INT)||input.getInt("DataVersion")>SharedConstants.getCurrentVersion().getDataVersion().getVersion()))
   throw new IllegalArgumentException("Schematic targets a newer/invalid Minecraft data version; convert it with a compatible editor first");
  // Validate before native loading: vanilla's tolerant palette decoder can otherwise map unknown IDs to air.
  SchematicImportPolicy.decode(input,maximum,tag->BlockStateParser.serialize(decodeRegisteredState(lookup,tag)),true);
  CompoundTag safe=new CompoundTag();for(String key:List.of("size","palette","blocks"))safe.put(key,input.get(key).copy());
  ListTag palette=safe.getList("palette",Tag.TAG_COMPOUND),blocks=safe.getList("blocks",Tag.TAG_COMPOUND);int markers=0,stripped=0;
  for(Tag value:palette){CompoundTag tag=(CompoundTag)value;if(!tag.getString("Name").equals("minecraft:jigsaw"))requireSafe(decodeRegisteredState(lookup,tag),tag.getString("Name"),mode);}
  for(Tag value:blocks){
   CompoundTag block=(CompoundTag)value,paletteTag=palette.getCompound(block.getInt("state"));String name=paletteTag.getString("Name");
   if(name.equals("minecraft:jigsaw")){
    if(!block.contains("nbt",Tag.TAG_COMPOUND)||!block.getCompound("nbt").contains("final_state",Tag.TAG_STRING))throw new IllegalArgumentException("Jigsaw marker needs a valid final_state");
    String text=block.getCompound("nbt").getString("final_state");
    if(text.length()>512||text.contains("{")||text.contains("}"))throw new IllegalArgumentException("Jigsaw replacement must be a block state without NBT");
    try{
     BlockState replacement=BlockStateParser.parseForBlock(lookup,text,false).blockState();String id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(replacement.getBlock()).toString();requireSafe(replacement,id,mode);
     block.putInt("state",palette.size());palette.add(NbtUtils.writeBlockState(replacement));markers++;
    }catch(com.mojang.brigadier.exceptions.CommandSyntaxException error){throw new IllegalArgumentException("Invalid registered jigsaw final_state",error);}
   }else if(block.contains("nbt")){
    if(mode!=Mode.DECORATIVE||!safeDecorativeBlock(name))throw new IllegalArgumentException("Block-entity NBT unsupported for import: "+name);
    stripped++;
   }
   block.remove("nbt");
  }
  // Unused jigsaw palette entries must not survive into strict post-normalization validation.
  StructureTemplate template=new StructureTemplate();template.load(lookup,safe);
  CompoundTag canonical=template.save(new CompoundTag());
  Plan decoded=SchematicImportPolicy.decode(canonical,maximum,tag->{BlockState state=decodeRegisteredState(lookup,tag);requireSafe(state,tag.getString("Name"),mode);return BlockStateParser.serialize(state);});
  return new Plan(decoded.cells,decoded.width,decoded.length,decoded.height,decoded.skippedAir,markers,stripped,0,decoded.description+" Jigsaw markers resolved="+markers+"; decorative block-entity payloads stripped="+stripped+". No pools, world generation, entities, commands, inventory or machine data executed.");
 }

 static CompoundTag readTag(Path approvedDirectory,String fileName)throws IOException{
  Path file=resolveFile(approvedDirectory,fileName);
  if(Files.size(file)>MAX_COMPRESSED_BYTES)throw new IllegalArgumentException("Compressed schematic exceeds 8 MiB input budget");
  try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
   return NbtIo.readCompressed(input,NbtAccounter.create(MAX_NBT_BYTES));
  }
 }
 static Path resolveFile(Path approvedDirectory,String fileName)throws IOException{
  if(fileName!=null&&fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".schem"))throw new IllegalArgumentException("Sponge .schem needs a compatible mature WorldEdit reader/conversion; this adapter accepts native Create/Minecraft .nbt only");
  if(fileName==null||!fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.nbt"))throw new IllegalArgumentException("Choose a .nbt basename from the approved schematics folder");
  Path root=approvedDirectory.toRealPath(),candidate=root.resolve(fileName).toRealPath();
  if(!candidate.getParent().equals(root)||!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Schematic must be a regular file inside the approved folder");
  return candidate;
 }

 static String registeredState(HolderLookup<Block> lookup,CompoundTag tag){
  BlockState state=decodeRegisteredState(lookup,tag);requireSafe(state,tag.getString("Name"),Mode.STRICT);return BlockStateParser.serialize(state);
 }
 static BlockState decodeRegisteredState(HolderLookup<Block> lookup,CompoundTag tag){
  if(!tag.contains("Name",Tag.TAG_STRING))throw new IllegalArgumentException("Schematic palette block name must be a registered ID");
  String name=tag.getString("Name");ResourceLocation id=ResourceLocation.tryParse(name);
  if(id==null)throw new IllegalArgumentException("Invalid schematic block ID");
  Block block=lookup.get(ResourceKey.create(Registries.BLOCK,id)).orElseThrow(()->new IllegalArgumentException("Unregistered schematic block: "+name)).value();
  if(tag.contains("Properties")){
   if(!tag.contains("Properties",Tag.TAG_COMPOUND))throw new IllegalArgumentException("Schematic block properties must be a compound");
   CompoundTag properties=tag.getCompound("Properties");
   for(String key:properties.getAllKeys()){
    Property<?> property=block.getStateDefinition().getProperty(key);
    if(property==null||!properties.contains(key,Tag.TAG_STRING)||property.getValue(properties.getString(key)).isEmpty())throw new IllegalArgumentException("Invalid schematic property "+name+"["+key+"]");
   }
  }
  return NbtUtils.readBlockState(lookup,tag);
 }
 static void requireSafe(BlockState state,String name,Mode mode){
  name=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
  if(state.hasBlockEntity()&&(mode!=Mode.DECORATIVE||!safeDecorativeBlock(name)))throw new IllegalArgumentException("Block-entity state unsupported for safe import: "+name);
  Block block=state.getBlock();
  if(name.equals("minecraft:structure_void"))return;
  if(UNSAFE.contains(name)||block instanceof FallingBlock||block.defaultDestroyTime()<0||!state.getFluidState().isEmpty())throw new IllegalArgumentException("Unsafe/unsupported schematic state: "+name);
 }
 /** Only fresh default utility entities, never imported NBT or machine/controller entities. */
 public static boolean safeDecorativeBlock(String id){return id.matches("minecraft:(?:white|orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple|blue|brown|green|red|black)_bed")||Set.of("minecraft:chest","minecraft:barrel","minecraft:furnace","minecraft:blast_furnace","minecraft:smoker").contains(id);}
}
