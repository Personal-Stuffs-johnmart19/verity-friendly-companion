package dev.johnmart19.veritycompanion;
import java.nio.file.*;
import net.minecraft.nbt.*;
/** Read-only facts from one parent-selected player journal; never scans or edits the world. */
public final class BuildJournalSummary {
 private static final long MAX_COMPRESSED=16L*1024*1024,MAX_DECOMPRESSED=128L*1024*1024;
 private static final int MAX_ENTRIES=262144;
 private BuildJournalSummary(){}
 public record Design(int x,int y,int z,int width,int length,int height){
  public Design{BuildBudget.requireVolume(width,length,height,1048576);if(x < -30000000||x>30000000||z < -30000000||z>30000000||y < -2048||y>2047)throw new IllegalArgumentException("Invalid design origin");}
  public CompoundTag tag(){CompoundTag out=new CompoundTag();out.putInt("x",x);out.putInt("y",y);out.putInt("z",z);out.putInt("width",width);out.putInt("length",length);out.putInt("height",height);return out;}
  String context(){return "design_origin="+x+","+y+","+z+"; design_dimensions="+width+"x"+length+"x"+height+"; ";}
 }
 private static Design design(CompoundTag root){try{if(!root.contains("design",Tag.TAG_COMPOUND))return null;CompoundTag value=root.getCompound("design");for(String key:java.util.List.of("x","y","z","width","length","height"))if(!value.contains(key,Tag.TAG_INT))return null;return new Design(value.getInt("x"),value.getInt("y"),value.getInt("z"),value.getInt("width"),value.getInt("length"),value.getInt("height"));}catch(IllegalArgumentException invalid){return null;}}
 public record Facts(boolean exists,boolean verified,int appliedBlocks,String dimension,String bounds,String status,String template,String style,String sourceAsset,int floors,Design design,String wallBlock,boolean wallBlockKnown){
  public Facts(boolean exists,boolean verified,int appliedBlocks,String dimension,String bounds,String status,String template,String style,String sourceAsset,int floors,Design design){this(exists,verified,appliedBlocks,dimension,bounds,status,template,style,sourceAsset,floors,design,"",false);}
  public Facts(boolean exists,boolean verified,int appliedBlocks,String dimension,String bounds,String status){this(exists,verified,appliedBlocks,dimension,bounds,status,"","","",0,null);}
  public Facts(boolean exists,boolean verified,int appliedBlocks,String dimension,String bounds,String status,String template,String style,String sourceAsset){this(exists,verified,appliedBlocks,dimension,bounds,status,template,style,sourceAsset,0,null);}
  public Facts(boolean exists,boolean verified,int appliedBlocks,String dimension,String bounds,String status,String template,String style,String sourceAsset,int floors){this(exists,verified,appliedBlocks,dimension,bounds,status,template,style,sourceAsset,floors,null);}
  public boolean canUndo(String currentDimension){return exists&&verified&&appliedBlocks>0&&dimension.equals(currentDimension);}
  public String context(String currentDimension,String language){return context(currentDimension);}
  public String context(String currentDimension){
   if(!exists)return "BUILD JOURNAL FACTS: No saved build journal exists for this player. This says nothing about unrelated world buildings.";
   if(!verified)return "BUILD JOURNAL FACTS: A journal exists but is unavailable or unverified: "+status+". Do not claim nothing was built. Do not overwrite or undo legacy/unknown entries automatically.";
   if(appliedBlocks==0)return "BUILD JOURNAL FACTS: The verified journal has zero applied entries (empty, consumed undo, or unfinished/crashed construction). No saved cells are available for undo; do not infer the world is empty.";
   return "BUILD JOURNAL FACTS: This player's last build recorded "+appliedBlocks+" applied block changes; dimension="+dimension+"; bounds="+bounds+"; template="+(template.isBlank()?"unknown/legacy":template)+"; style="+style+"; wall_block="+(wallBlockKnown?(wallBlock.isEmpty()?"default":wallBlock):"unknown/legacy")+"; floors="+(floors>0?floors:"unknown/legacy")+"; "+(design==null?"":design.context())+"source_asset="+sourceAsset+"; undo_in_current_dimension="+canUndo(currentDimension)+". These are saved changes, not a live world scan. undo_build rechecks actual blocks/protection. Other dimensions require returning there. Never claim no build exists because chat history forgot it.";
  }
 }
 public static Facts read(Path journal){
  if(!Files.exists(journal))return new Facts(false,false,0,"","","missing");
  try{
   if(!Files.isRegularFile(journal)||Files.size(journal)>MAX_COMPRESSED)return unavailable("journal exceeds read budget or is not a regular file");
   CompoundTag root=NbtIo.readCompressed(journal,NbtAccounter.create(MAX_DECOMPRESSED));
   if(!root.getBoolean("appliedOnly"))return unavailable("legacy journal does not prove applied changes");
   String dimension=root.getString("dimension");if(dimension.length()>200||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||!root.contains("blocks",9))return unavailable("invalid journal metadata");
   ListTag blocks=(ListTag)root.get("blocks");if(!blocks.isEmpty()&&blocks.getElementType()!=10)return unavailable("invalid journal entry type");if(blocks.size()>MAX_ENTRIES)return unavailable("journal entry count exceeds read budget");
   int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
   for(int i=0;i<blocks.size();i++){CompoundTag block=blocks.getCompound(i);if(!block.contains("pos",4)||!block.contains("before",10)||!block.contains("after",10))return unavailable("malformed applied block entry");long pos=block.getLong("pos");int x=(int)(pos>>38),y=(int)(pos<<52>>52),z=(int)(pos<<26>>38);minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);}
   String bounds=blocks.isEmpty()?"empty":minX+","+minY+","+minZ+" .. "+maxX+","+maxY+","+maxZ;
   String template=StructureCatalog.canonical(root.getString("template")),style=root.getString("style");
   if(!StructureCatalog.supports(template)&&!java.util.Set.of("custom","schematic").contains(template))template="";
   if(style.equals("create_workshop"))style="create";
   if(!StructureCatalog.STYLES.contains(style)&&!style.equals("vanilla"))style="";
   int floors=root.contains("floors",Tag.TAG_INT)?root.getInt("floors"):0;if(floors<1||floors>32)floors=0;
   String source=root.getString("schematic");if(!source.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.nbt"))source="";
   String wall=root.getString("wallBlock");boolean wallKnown=root.contains("wallBlock",Tag.TAG_STRING);if(!wall.isEmpty()&&(wall.length()>512||!wall.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+(?:\\[[a-z0-9_]+=[a-z0-9_-]+(?:,[a-z0-9_]+=[a-z0-9_-]+)*\\])?"))){wall="";wallKnown=false;}
   return new Facts(true,true,blocks.size(),dimension,bounds,"verified applied-only journal",template,style,source,floors,design(root),wall,wallKnown);
  }catch(Exception unreadable){return unavailable("journal could not be read safely");}
 }
 private static Facts unavailable(String reason){return new Facts(true,false,0,"","",reason);}
}
