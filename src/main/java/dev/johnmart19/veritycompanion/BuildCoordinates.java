package dev.johnmart19.veritycompanion;

/** Explicit coordinate frames; a nearby build cannot silently double absolute positions. */
final class BuildCoordinates {
 record Point(int x,int y,int z){}
 static Point resolve(String mode,int px,int py,int pz,int x,int y,int z){
  long ax,ay,az;
  switch(mode){
   case "relative"->{ax=(long)px+x;ay=(long)py+y;az=(long)pz+z;}
   case "absolute"->{ax=x;ay=y;az=z;}
   default->throw new IllegalArgumentException("coordinate_mode must be auto, relative or absolute");
  }
  if(Math.abs(ax-px)>48||Math.abs(az-pz)>48||Math.abs(ay-py)>32)
   throw new IllegalArgumentException("Build origin must be nearby (48 horizontal / 32 vertical blocks). Coordinates may have been absolute but marked relative; use coordinate_mode=auto for a nearby surface, or absolute for world coordinates.");
  return new Point(Math.toIntExact(ax),Math.toIntExact(ay),Math.toIntExact(az));
 }
}
