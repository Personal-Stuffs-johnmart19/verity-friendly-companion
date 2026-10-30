package dev.johnmart19.veritycompanion;

import java.util.ArrayList;
import java.util.List;

/** Bounded, deterministic geometry, independent of the world and placement permissions. */
public final class BuildGeometry {
 private BuildGeometry(){}
 public record Cell(int x,int y,int z){}
 public static List<Cell> generate(String kind,int width,int length,int height,int maximum){
  BuildBudget.requireVolume(width,length,height,maximum);
  if(!List.of("house","tower","bridge","platform","castle").contains(kind))throw new IllegalArgumentException("Unknown structure kind "+kind);
  if(width<3||length<3||height<3)throw new IllegalArgumentException("Structure dimensions must be at least 3");
  if(kind.equals("castle")&&(width<9||length<9||height<6))throw new IllegalArgumentException("Castle requires width/length >=9 and height >=6");
  List<Cell> cells=new ArrayList<>();
  for(int x=0;x<width;x++)for(int z=0;z<length;z++)for(int y=0;y<height;y++){
   boolean boundary=x==0||x==width-1||z==0||z==length-1;
   boolean place;
   if(kind.equals("castle")){
    boolean tower=(x<3||x>=width-3)&&(z<3||z>=length-3);
    int tx=x<3?x:x-(width-3),tz=z<3?z:z-(length-3);
    boolean towerEdge=tx==0||tx==2||tz==0||tz==2;
    // Open courtyard, low curtain walls and four taller hollow corner towers.
    place=y==0||(tower&&(y==height-2||(towerEdge&&(y<height-1||(x+z)%2==0))))
      ||(!tower&&boundary&&(y<height-3||(y==height-3&&(x+z)%2==0)));
   }else place=switch(kind){case "platform","bridge"->y==0;default->y==0||y==height-1||boundary;};
   boolean door=z==0&&x==width/2&&(y==1||y==2);
   if(place&&!door)cells.add(new Cell(x,y,z));
  }
  return List.copyOf(cells);
 }
}
