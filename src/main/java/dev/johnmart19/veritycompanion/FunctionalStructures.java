package dev.johnmart19.veritycompanion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

/** Finite, local architectural recipes. Never reads or changes a world. */
final class FunctionalStructures {
 private FunctionalStructures(){}
 private record Pos(int x,int y,int z){}
 private record Materials(String wall,String trim,String floor,String glass,String stairs){}
 private static final class Canvas {
  final LinkedHashMap<Pos,String> cells=new LinkedHashMap<>();
  void put(int x,int y,int z,String state){cells.put(new Pos(x,y,z),state);}
  void remove(int x,int y,int z){cells.remove(new Pos(x,y,z));}
  StructuredBlueprint.Plan plan(String description){
   List<StructuredBlueprint.Cell> result=new ArrayList<>();
   cells.forEach((p,s)->result.add(new StructuredBlueprint.Cell(p.x,p.y,p.z,s)));
   // Stable ordering keeps paired bed foot/head ordering; all supports precede higher cells.
   result.sort(Comparator.comparingInt(StructuredBlueprint.Cell::y));
   return new StructuredBlueprint.Plan(List.copyOf(result),description);
  }
 }
 static StructuredBlueprint.Plan generate(String kind,String style,int w,int l,int h,int floors,String wall,Predicate<String> registered){
  if(floors<1||floors>32)throw new IllegalArgumentException("Floors must be 1..32");
  if(w<11||l<11||h<4*floors+1)throw new IllegalArgumentException("Functional "+kind+" needs width/length >=11 and height >= floors*4+1 ("+(4*floors+1)+")");
  Materials m=materials(style,wall,registered);Canvas c=new Canvas();
  if(kind.equals("castle")||kind.equals("outpost")){
   if(w<23||l<23||h<9)throw new IllegalArgumentException("Castle/outpost needs width/length >=23 and height >=9 for courtyard, accessible towers and keep");
   compound(c,kind,w,l,h,floors,m);
  }else building(c,kind,0,0,w,l,h,floors,m);
  String description="Original "+kind+"; "+floors+" furnished floors, 3-block interior headroom, glazed windows, usable two-part doors, connected two-wide switchback stairs, beds, empty storage, crafting/smelting workspaces and permanent lighting.";
  if(floors==1)description="Original "+kind+"; furnished ground floor, glazed windows, usable two-part door, empty storage, workspaces and lighting.";
  if(kind.equals("castle")||kind.equals("outpost"))description+=" Walled courtyard, open two-block gate and four roof-accessible corner towers surround the furnished keep.";
  if(style.equals("create"))description+=" Registered Create facade palette with vanilla fallback; no powered or moving machines.";
  return c.plan(description+" Fresh default utility blocks only; no imported NBT, stocked inventory, entities or automation. No air clearing.");
 }
 private static Materials materials(String style,String override,Predicate<String> registered){
  Materials m=switch(style){
   case "modern"->new Materials("minecraft:white_concrete","minecraft:smooth_quartz","minecraft:smooth_stone","minecraft:light_blue_stained_glass","minecraft:quartz_stairs");
   case "industrial","create"->new Materials("minecraft:polished_andesite","minecraft:cut_copper","minecraft:stone_bricks","minecraft:glass","minecraft:polished_andesite_stairs");
   case "deepslate"->new Materials("minecraft:deepslate_bricks","minecraft:polished_deepslate","minecraft:deepslate_tiles","minecraft:gray_stained_glass","minecraft:deepslate_brick_stairs");
   case "desert"->new Materials("minecraft:smooth_sandstone","minecraft:cut_sandstone","minecraft:sandstone","minecraft:yellow_stained_glass","minecraft:sandstone_stairs");
   case "timber"->new Materials("minecraft:spruce_planks","minecraft:oak_log[axis=y]","minecraft:oak_planks","minecraft:glass","minecraft:spruce_stairs");
   default->new Materials("minecraft:stone_bricks","minecraft:chiseled_stone_bricks","minecraft:spruce_planks","minecraft:glass","minecraft:stone_brick_stairs");
  };
  if(style.equals("create"))m=new Materials(available("create:andesite_casing",m.wall,registered),available("create:brass_casing",m.trim,registered),m.floor,available("create:industrial_iron_window",m.glass,registered),m.stairs);
  return new Materials(override==null||override.isBlank()?m.wall:override,m.trim,m.floor,m.glass,m.stairs);
 }
 private static String available(String id,String fallback,Predicate<String> registered){return registered!=null&&registered.test(id)?id:fallback;}
 private static void building(Canvas c,String kind,int ox,int oz,int w,int l,int h,int floors,Materials m){
  int roof=4*floors;
  for(int y=0;y<=roof;y++)for(int x=0;x<w;x++)for(int z=0;z<l;z++){
   boolean edge=x==0||z==0||x==w-1||z==l-1;
   if(y%4==0){c.put(ox+x,y,oz+z,y==roof?m.trim:m.floor);continue;}
   if(!edge)continue;
   boolean corner=(x==0||x==w-1)&&(z==0||z==l-1);
   boolean window=y%4==2&&!corner&&((x==0||x==w-1)?z%4!=0:x%4!=0);
   c.put(ox+x,y,oz+z,corner?m.trim:window?m.glass:kind.equals("greenhouse")?m.glass:m.wall);
  }
  // Every higher floor has a headroom opening over the arriving flight, and a full landing at each end.
  for(int floor=0;floor<floors-1;floor++){
   int base=4*floor,x0=floor%2==0?2:5;
   boolean south=floor%2==0;
   for(int lane=0;lane<2;lane++)for(int step=0;step<4;step++){
    int z=south?2+step:5-step;
    for(int y=base+step+2;y<=base+step+3;y++)c.remove(ox+x0+lane,y,oz+z);
    c.put(ox+x0+lane,base+step+1,oz+z,stairs(m.stairs,south?"south":"north"));
    // Structural underfill never reaches into the previous flight (which uses the other lane pair).
    for(int y=base+1;y<base+step+1;y++)c.put(ox+x0+lane,y,oz+z,m.wall);
   }
  }
  // Footprints and stair/landing circulation occupy x<=7,z<=7; furniture occupies the rear/right wings.
  for(int floor=0;floor<floors;floor++){
   int y=4*floor+1;
   furnish(c,kind,ox,oz,w,l,y,m);
   for(int x=1;x<w-1;x+=5)for(int z=1;z<l-1;z+=5){
    if(x<=7&&z<=7)continue;
    c.put(ox+x,y-1,oz+z,"minecraft:sea_lantern");
   }
   c.put(ox+1,y,oz+1,"minecraft:torch");c.put(ox+w-2,y,oz+l-2,"minecraft:torch");
  }
  // Roof guard rails avoid a fall when approaching the roof edge; no ladder/roof-access promise for the main building.
  if(h>roof+1)for(int x=0;x<w;x++)for(int z=0;z<l;z++)if(x==0||z==0||x==w-1||z==l-1)c.put(ox+x,roof+1,oz+z,m.trim);
  door(c,ox+w/2,1,oz,"north");
 }
 private static void furnish(Canvas c,String kind,int ox,int oz,int w,int l,int y,Materials m){
  c.put(ox+w-2,y,oz+2,"minecraft:crafting_table");
  c.put(ox+w-2,y,oz+3,"minecraft:furnace[facing=west,lit=false]");
  c.put(ox+w-2,y,oz+4,"minecraft:barrel[facing=west,open=false]");
  bed(c,ox+2,y,oz+l-3,"south");
  if(List.of("apartment","skyscraper","inn","barracks").contains(kind)){
   // Rear bedrooms separated from the central hall; each has an operable interior door.
   if(l>=13){
    int partition=l-5;
    for(int x=1;x<w-1;x++)for(int dy=0;dy<3;dy++)c.put(ox+x,y+dy,oz+partition,m.wall);
    door(c,ox+w/2,y,oz+partition,"north");
   }
   for(int x=5;x<w-2;x+=3)if(Math.abs(x-w/2)>1){bed(c,ox+x,y,oz+l-3,"south");c.put(ox+x+1,y,oz+l-3,"minecraft:barrel[facing=north,open=false]");}
  }
  if(kind.equals("warehouse")||kind.equals("factory"))for(int x=2;x<w-2;x+=3)for(int z=8;z<l-2;z+=3)c.put(ox+x,y,oz+z,"minecraft:barrel[facing=north,open=false]");
  if(kind.equals("library"))for(int x=2;x<w-2;x+=3)for(int z=8;z<l-2;z++)if(x!=2||z<l-3)for(int dy=0;dy<2;dy++)c.put(ox+x,y+dy,oz+z,"minecraft:bookshelf");
  if(kind.equals("greenhouse"))for(int x=2;x<w-2;x+=3)for(int z=8;z<l-2;z++)c.put(ox+x,y-1,oz+z,"minecraft:rooted_dirt");
  if(kind.equals("stable"))for(int z=8;z<l-2;z+=3){for(int x=1;x<4;x++)c.put(ox+x,y,oz+z,"minecraft:oak_fence");c.put(ox+4,y,oz+z,"minecraft:oak_fence_gate[facing=east,in_wall=false,open=false,powered=false]");c.put(ox+1,y,oz+z+1,"minecraft:hay_block[axis=y]");}
  // Tables and seats stay in the right wing, preserving a two-block-wide central passage.
  if(w>=15&&l>=15){c.put(ox+w-4,y,oz+6,"minecraft:oak_planks");c.put(ox+w-5,y,oz+6,stairs("minecraft:oak_stairs","east"));}
  // Preserve a complete matched bed pair after category-specific shelving/pen decoration.
  bed(c,ox+2,y,oz+l-3,"south");
 }
 private static void compound(Canvas c,String kind,int w,int l,int h,int floors,Materials m){
  for(int x=0;x<w;x++)for(int z=0;z<l;z++)c.put(x,0,z,"minecraft:stone_bricks");
  int wallHeight=4;
  for(int y=1;y<=wallHeight;y++)for(int x=0;x<w;x++)for(int z=0;z<l;z++){
   if(x!=0&&z!=0&&x!=w-1&&z!=l-1)continue;
   if(z==0&&(x==w/2||x==w/2-1)&&y<=3)continue;
   if(y<wallHeight||(x+z)%2==0)c.put(x,y,z,m.wall);
  }
  int th=Math.min(h,9);
  for(int x:new int[]{0,w-5})for(int z:new int[]{0,l-5})tower(c,x,z,th,z==0,m);
  building(c,kind.equals("castle")?"barracks":"workshop",6,6,w-12,l-12,h,floors,m);
  // Paved access crosses the gate and reaches the keep door without furniture or stairs.
  for(int z=0;z<7;z++)for(int x=w/2-1;x<=w/2;x++)c.put(x,0,z,"minecraft:polished_andesite");
 }
 private static void tower(Canvas c,int ox,int oz,int h,boolean north,Materials m){
  for(int y=1;y<h;y++)for(int x=0;x<5;x++)for(int z=0;z<5;z++){
   boolean edge=x==0||z==0||x==4||z==4;
   if(y==h-2||edge&&(y<h-1||(x+z)%2==0))c.put(ox+x,y,oz+z,y==3&&edge&&x==2?m.glass:m.wall);
  }
  for(int y=1;y<h-1;y++){
   c.remove(ox+1,y,oz+2);
   c.put(ox+1,y,oz+2,"minecraft:ladder[facing=east,waterlogged=false]");
  }
  door(c,ox+2,1,oz+(north?4:0),north?"south":"north");
  c.put(ox+3,h-1,oz+3,"minecraft:torch");
 }
 private static void bed(Canvas c,int x,int y,int z,String facing){
  c.put(x,y,z,"minecraft:red_bed[facing="+facing+",occupied=false,part=foot]");
  c.put(x,y,z+1,"minecraft:red_bed[facing="+facing+",occupied=false,part=head]");
 }
 private static void door(Canvas c,int x,int y,int z,String facing){
  c.put(x,y,z,"minecraft:oak_door[facing="+facing+",half=lower,hinge=left,open=false,powered=false]");
  c.put(x,y+1,z,"minecraft:oak_door[facing="+facing+",half=upper,hinge=left,open=false,powered=false]");
 }
 private static String stairs(String id,String facing){return id+"[facing="+facing+",half=bottom,shape=straight,waterlogged=false]";}
}
