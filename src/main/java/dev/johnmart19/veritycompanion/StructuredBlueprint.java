package dev.johnmart19.veritycompanion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Pure, bounded block-state blueprints. Placement and permissions remain the job runner's responsibility. */
public final class StructuredBlueprint {
 private StructuredBlueprint(){}
 public record Cell(int x,int y,int z,String state){}
 public record Plan(List<Cell> cells,String description){}
 private record Position(int x,int y,int z){}
 private record Palette(String wall,String support,String glass,String trim,String floor,String roof,String roofCap,boolean create,boolean fallback){}

 /** registered receives registry IDs without state properties, and must never load chunks. */
 public static Plan generate(String kind,String style,int width,int length,int height,int maximum,String wallBlock,Predicate<String> registered){
  String canonical=StructureCatalog.canonical(kind);
  int floors=StructureCatalog.inferredFloors(canonical,width,length,height);
  return generate(canonical,style,width,length,height,floors,maximum,wallBlock,registered);
 }
 /** Explicit floors preserve the requested floor count instead of guessing from height. */
 public static Plan generate(String kind,String style,int width,int length,int height,int floors,int maximum,String wallBlock,Predicate<String> registered){
  BuildBudget.requireVolume(width,length,height,maximum);
  kind=StructureCatalog.canonical(kind);style=StructureCatalog.effectiveStyle(kind,style);
  if(!StructureCatalog.supports(kind))throw new IllegalArgumentException("Unknown structure kind "+kind);
  if(floors<1||floors>32)throw new IllegalArgumentException("Floors must be 1..32");
  if((kind.equals("bridge")||kind.equals("platform"))&&floors!=1)throw new IllegalArgumentException("Bridge/platform has one deck");
  boolean functional= !List.of("house","cottage","workshop","create_workshop","tower","castle","bridge","platform").contains(kind)
   ||floors>1&&width>=11&&length>=11&&!kind.equals("castle")||kind.equals("castle")&&width>=23&&length>=23;
  if(functional){
   return FunctionalStructures.generate(kind,style,width,length,height,floors,wallBlock,registered);
  }
  if(floors>1)throw new IllegalArgumentException("Multiple furnished floors require width/length >=11; castle/outpost >=23");
  if(kind.equals("bridge")){
   if(width<3||length<3||height<3)throw new IllegalArgumentException("Bridge dimensions must be >=3");
   List<Cell> cells=new ArrayList<>();String deck=wallBlock==null||wallBlock.isBlank()?"minecraft:stone_bricks":wallBlock;
   for(int x=0;x<width;x++)for(int z=0;z<length;z++)cells.add(new Cell(x,0,z,deck));
   for(int z=0;z<length;z++){cells.add(new Cell(0,1,z,"minecraft:oak_fence"));cells.add(new Cell(width-1,1,z,"minecraft:oak_fence"));}
   return new Plan(List.copyOf(cells),"Static bridge with continuous deck, guard rails and open end approaches. No moving rails or powered machinery.");
  }
  boolean house=List.of("house","cottage","workshop","create_workshop").contains(kind);
  boolean create=kind.equals("create_workshop")||kind.equals("workshop")&&(style.isEmpty()||style.equals("default"))||style.equals("create");
  if(house){
   if(width<5||length<5||height<5)throw new IllegalArgumentException("House/workshop requires width/length/height >=5 for its door, windows and roof");
   Palette palette=palette(create,style,wallBlock,registered);
   return cottage(width,length,height,palette);
  }
  if(kind.equals("tower")&&height<4)throw new IllegalArgumentException("Tower height must be >=4 for its two-part door and roof");
  String material=wallBlock==null||wallBlock.isBlank()?(kind.equals("castle")||kind.equals("tower")?"minecraft:stone_bricks":"minecraft:oak_planks"):wallBlock;
  List<Cell> cells=new ArrayList<>();
  for(BuildGeometry.Cell cell:BuildGeometry.generate(kind,width,length,height,maximum)){
   String state=material;
   if(kind.equals("tower")&&cell.y()==2&&((cell.x()==0||cell.x()==width-1)&&cell.z()==length/2||cell.z()==length-1&&cell.x()==width/2))state="minecraft:glass";
   cells.add(new Cell(cell.x(),cell.y(),cell.z(),state));
  }
  if(kind.equals("tower")&&height>=4){
   cells.add(new Cell(width/2,1,0,door("lower")));cells.add(new Cell(width/2,2,0,door("upper")));
   cells.add(new Cell(1,1,1,"minecraft:torch"));
  }
  return new Plan(List.copyOf(cells),kind.equals("tower")?"Tower with glazed windows, a usable wooden door and floor-supported light.":"Compact "+kind+" blueprint.");
 }

 private static Palette palette(boolean create,String style,String wallBlock,Predicate<String> registered){
  if(!create){
   Palette p=switch(style){
    case "modern"->new Palette("minecraft:white_concrete","minecraft:smooth_quartz","minecraft:light_blue_stained_glass","minecraft:smooth_quartz","minecraft:smooth_stone","minecraft:quartz_stairs","minecraft:quartz_block",false,false);
    case "deepslate"->new Palette("minecraft:deepslate_bricks","minecraft:polished_deepslate","minecraft:gray_stained_glass","minecraft:chiseled_deepslate","minecraft:deepslate_tiles","minecraft:deepslate_brick_stairs","minecraft:deepslate_bricks",false,false);
    case "desert"->new Palette("minecraft:smooth_sandstone","minecraft:cut_sandstone","minecraft:yellow_stained_glass","minecraft:chiseled_sandstone","minecraft:sandstone","minecraft:sandstone_stairs","minecraft:sandstone",false,false);
    case "industrial"->new Palette("minecraft:polished_andesite","minecraft:cut_copper","minecraft:glass","minecraft:cut_copper","minecraft:stone_bricks","minecraft:polished_andesite_stairs","minecraft:polished_andesite",false,false);
    case "medieval"->new Palette("minecraft:stone_bricks","minecraft:oak_log[axis=y]","minecraft:glass","minecraft:oak_log[axis=x]","minecraft:stone_bricks","minecraft:spruce_stairs","minecraft:spruce_planks",false,false);
    default->new Palette("minecraft:oak_planks","minecraft:oak_log[axis=y]","minecraft:glass","minecraft:oak_log[axis=x]","minecraft:stone_bricks","minecraft:spruce_stairs","minecraft:spruce_planks",false,false);
   };
   return wallBlock==null||wallBlock.isBlank()?p:new Palette(wallBlock,p.support,p.glass,p.trim,p.floor,p.roof,p.roofCap,false,false);
  }
  // IDs verified against installed Create 6.0.10 AllBlocks / AllPaletteBlocks registration bytecode.
  String[] wanted={"create:andesite_casing","create:metal_girder","create:industrial_iron_window","create:brass_casing"};
  String[] fallback={"minecraft:polished_andesite","minecraft:polished_andesite","minecraft:glass","minecraft:cut_copper"};
  String[] chosen=new String[wanted.length];boolean missing=false,used=false;
  for(int i=0;i<wanted.length;i++){boolean available=registered!=null&&registered.test(wanted[i]);chosen[i]=available?wanted[i]:fallback[i];missing|=!available;used|=available;}
  return new Palette(chosen[0],chosen[1],chosen[2],chosen[3],"minecraft:stone_bricks","minecraft:polished_andesite_stairs","minecraft:polished_andesite",used,missing);
 }

 private static Plan cottage(int width,int length,int height,Palette palette){
  LinkedHashMap<Position,String> blocks=new LinkedHashMap<>();
  int rise=Math.min((width-1)/2,height-4),eave=height-1-rise,doorX=width/2;
  // Bottom-up insertion leaves supports in place before windows, roof, lights and the two door halves.
  for(int x=0;x<width;x++)for(int z=0;z<length;z++)put(blocks,x,0,z,palette.floor);
  for(int y=1;y<eave;y++)for(int x=0;x<width;x++)for(int z=0;z<length;z++){
   boolean boundary=x==0||x==width-1||z==0||z==length-1;
   if(!boundary||z==0&&x==doorX&&(y==1||y==2))continue;
   boolean corner=(x==0||x==width-1)&&(z==0||z==length-1);
   boolean sideWindow=(x==0||x==width-1)&&z>=2&&z<length-2&&z%3!=1;
   boolean endWindow=(z==0||z==length-1)&&x>=1&&x<width-1&&Math.abs(x-doorX)>=2;
   put(blocks,x,y,z,corner?palette.support:y==2&&(sideWindow||endWindow)?palette.glass:palette.wall);
  }
  // Front and rear gables seal the area under both slopes without filling the interior.
  for(int x=0;x<width;x++){
   int roofY=eave+Math.min(Math.min(x,width-1-x),rise);
   for(int y=eave;y<roofY;y++){put(blocks,x,y,0,palette.wall);put(blocks,x,y,length-1,palette.wall);}
  }
  put(blocks,doorX,3,0,palette.trim);
  for(int x=0;x<width;x++)for(int z=0;z<length;z++){
   int edge=Math.min(x,width-1-x),roofY=eave+Math.min(edge,rise);
   String roof=edge<rise?palette.roof+"[facing="+(x<width/2?"east":"west")+",half=bottom,shape=straight,waterlogged=false]":palette.roofCap;
   put(blocks,x,roofY,z,roof);
  }
  // These are floor supported, so placement requires neither hanging-block ordering nor block entities.
  put(blocks,1,1,1,"minecraft:torch");put(blocks,width-2,1,length-2,"minecraft:torch");
  put(blocks,1,1,length-2,"minecraft:crafting_table");
  if(width>=7&&length>=7){
   put(blocks,1,1,length-3,"minecraft:red_bed[facing=south,occupied=false,part=foot]");
   put(blocks,1,1,length-2,"minecraft:red_bed[facing=south,occupied=false,part=head]");
   put(blocks,width-2,1,1,"minecraft:crafting_table");
   put(blocks,width-2,1,2,"minecraft:furnace[facing=west,lit=false]");
   put(blocks,width-2,1,3,"minecraft:barrel[facing=west,open=false]");
   // Keep both supported lights after assigning the right-hand workbench.
   put(blocks,1,1,2,"minecraft:torch");
  }
  put(blocks,doorX,1,0,door("lower"));put(blocks,doorX,2,0,door("upper"));
  List<Cell> cells=new ArrayList<>(blocks.size());blocks.forEach((pos,state)->cells.add(new Cell(pos.x,pos.y,pos.z,state)));
  String description="House with foundation, corner supports, glazed windows, a two-part usable wooden door, stepped pitched roof, lights and crafting table.";
  if(width>=7&&length>=7)description+=" Complete bed, empty barrel storage and furnace workspace; fresh default utility blocks without inventory/NBT.";
  if(palette.create||palette.fallback)description+=" Create-style decorative workshop; no powered or moving machine is built.";
  if(palette.fallback)description+=" Unregistered Create palette blocks use vanilla substitutes.";
  return new Plan(List.copyOf(cells),description);
 }
 private static String door(String half){return "minecraft:oak_door[facing=north,half="+half+",hinge=left,open=false,powered=false]";}
 private static void put(LinkedHashMap<Position,String> blocks,int x,int y,int z,String state){blocks.put(new Position(x,y,z),state);}
}
