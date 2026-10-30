package dev.johnmart19.veritycompanion;
/** Current job mutations only; previous undo ownership never seeds these bounds. */
final class BuildMutationBounds {
 private int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
 private boolean changed;
 void include(int x,int y,int z){changed=true;minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);}
 String bounds(){return changed?minX+","+minY+","+minZ+" .. "+maxX+","+maxY+","+maxZ:"";}
}
