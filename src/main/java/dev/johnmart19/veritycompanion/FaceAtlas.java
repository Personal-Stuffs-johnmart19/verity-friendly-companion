package dev.johnmart19.veritycompanion;

/** CPU-only atlas compositing. Pixels use NativeImage's little-endian ABGR format. */
final class FaceAtlas {
 static final int SIZE=256;
 private static final double[] EYE_Y={.48,.41,.40,.37,.35},MOUTH_Y={.72,.67,.65,.63,.58};
 private FaceAtlas(){}
 static int[] compose(int[] faces,int width,int height,int[] mouths,int mouthWidth,int mouthHeight,int base,int emotion,int frame){
  if(width!=1374||height!=1145||faces.length!=width*height||mouthWidth!=2172||mouthHeight!=724||mouths.length!=mouthWidth*mouthHeight)throw new IllegalArgumentException("Unexpected expression atlas dimensions");
  if(emotion<0||emotion>=27||frame<0||frame>4)throw new IllegalArgumentException("Invalid expression frame");
  int[] result=new int[SIZE*SIZE];java.util.Arrays.fill(result,base|0xff000000);
  int row=emotion/6,col=emotion%6;
  double scaleY=.125/(MOUTH_Y[row]-EYE_Y[row]),top=.45-EYE_Y[row]*scaleY;
  for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++){
   double u=((x+.5)/SIZE-.28)/.44,v=((y+.5)/SIZE-top)/scaleY;
   if(u<0||u>=1||v<0||v>=1)continue;
   // Keep cheeks and eyebrows while replacing only the central resting mouth.
   if(frame>0&&v>EYE_Y[row]+.12&&u>.22&&u<.83)continue;
   int pixel=faces[(row*229+(int)(v*229))*width+col*229+(int)(u*229)];
   result[y*SIZE+x]=ink(base,pixel,false);
  }
  if(frame>0){
   int start=(frame-1)*543,minX=543,maxX=-1,minY=724,maxY=-1;
   for(int y=0;y<724;y++)for(int x=0;x<543;x++)if(coverage(mouths[y*mouthWidth+start+x],true)>.1){minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);}
   if(maxX<minX)throw new IllegalArgumentException("Empty mouth frame");
   double outWidth=frame==3?.17:.27,outHeight=new double[]{0,.065,.105,.15,.15}[frame];
   for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++){
    double u=((x+.5)/SIZE-(.5-outWidth/2))/outWidth,v=((y+.5)/SIZE-(.575-outHeight/2))/outHeight;
    if(u<0||u>=1||v<0||v>=1)continue;
    int pixel=mouths[(minY+(int)(v*(maxY-minY+1)))*mouthWidth+start+minX+(int)(u*(maxX-minX+1))];
    result[y*SIZE+x]=ink(base,pixel,true);
   }
  }
  return result;
 }
 private static double coverage(int pixel,boolean teeth){
  int r=pixel&255,g=pixel>>>8&255,b=pixel>>>16&255;
  double alpha=Math.max(0,Math.min(1,(190-Math.min(r,g))/70.0));
  if(teeth&&r>180&&g>180&&b>150)alpha=1;
  return alpha*(pixel>>>24)/255.0;
 }
 static int ink(int base,int pixel,boolean teeth){
  double alpha=coverage(pixel,teeth);int result=0xff000000;
  for(int shift=0;shift<=16;shift+=8){int a=base>>>shift&255,b=pixel>>>shift&255;result|=(int)Math.round(a+(b-a)*alpha)<<shift;}
  return result;
 }
}
