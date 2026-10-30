package dev.johnmart19.veritycompanion;
/** Compare before multiplying the third dimension, including hostile model-supplied integers. */
public final class BuildBudget {
 private BuildBudget(){}
 public static void requireVolume(int width,int length,int height,int maximum){
  if(width<1||length<1||height<1||maximum<1||(long)width*length>(long)maximum/height)
   throw new IllegalArgumentException("Bounding volume exceeds configured resource budget; split into phases");
 }
}
