package dev.johnmart19.veritycompanion;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/** Render-thread-only, lazy and bounded (27 emotions x 5 mouth poses). */
public final class ClientFaceTextures {
 private static final ResourceLocation BASE=ResourceLocation.fromNamespaceAndPath("verity","textures/entity/default.png");
 private static final ResourceLocation FACES=asset("expressions-v1.png"),MOUTHS=asset("mouths-v1.png");
 private static final java.util.Map<String,ResourceLocation> CACHE=new java.util.HashMap<>();
 private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger(ClientFaceTextures.class);
 private static int[] faces,mouths;
 private static int base;
 private static boolean failed,registered;
 private ClientFaceTextures(){}
 private static ResourceLocation asset(String file){return ResourceLocation.fromNamespaceAndPath("verity_companion","textures/expression/"+file);}
 public static void register(){Minecraft.getInstance().execute(()->{
  if(!registered&&Minecraft.getInstance().getResourceManager() instanceof ReloadableResourceManager resources){resources.registerReloadListener((ResourceManagerReloadListener)manager->clear());registered=true;}
 });}
 public static ResourceLocation texture(String emotion,int mouthFrame){
  String normalized=EmotionPolicy.normalize(emotion);int frame=Math.max(0,Math.min(4,mouthFrame));
  ResourceLocation fallback=ResourceLocation.fromNamespaceAndPath("verity","textures/entity/"+EmotionPolicy.variant(normalized)+".png");
  if(failed)return fallback;
  String key=normalized+"_"+frame;ResourceLocation cached=CACHE.get(key);if(cached!=null)return cached;
  NativeImage image=null;DynamicTexture texture=null;
  try{
   if(faces==null)load();
   int[] pixels=FaceAtlas.compose(faces,1374,1145,mouths,2172,724,base,EmotionPolicy.CATALOG.indexOf(normalized),frame);
   image=new NativeImage(FaceAtlas.SIZE,FaceAtlas.SIZE,false);
   for(int y=0;y<FaceAtlas.SIZE;y++)for(int x=0;x<FaceAtlas.SIZE;x++)image.setPixelRGBA(x,y,pixels[y*FaceAtlas.SIZE+x]);
   texture=new DynamicTexture(image);image=null;
   ResourceLocation id=ResourceLocation.fromNamespaceAndPath("verity_companion","dynamic/expression/"+key);
   Minecraft.getInstance().getTextureManager().register(id,texture);texture=null;CACHE.put(key,id);return id;
  }catch(Exception failure){
   if(image!=null)image.close();if(texture!=null)texture.close();
   failed=true;LOGGER.warn("Verity expression atlas unavailable; using native faces until resource reload",failure);return fallback;
  }
 }
 private static NativeImage read(ResourceLocation id)throws java.io.IOException{
  try(var stream=Minecraft.getInstance().getResourceManager().getResourceOrThrow(id).open()){return NativeImage.read(NativeImage.Format.RGBA,stream);}
 }
 private static void load()throws java.io.IOException{
  try(NativeImage original=read(BASE);NativeImage expressions=read(FACES);NativeImage strip=read(MOUTHS)){
   if(expressions.getWidth()!=1374||expressions.getHeight()!=1145||strip.getWidth()!=2172||strip.getHeight()!=724)throw new java.io.IOException("Unexpected expression atlas dimensions");
   base=original.getPixelRGBA(0,0)|0xff000000;faces=expressions.getPixelsRGBA();mouths=strip.getPixelsRGBA();
  }
 }
 /** Reload apply phase runs on the client thread, where GPU resources may be released. */
 public static void clear(){
  for(ResourceLocation id:CACHE.values())Minecraft.getInstance().getTextureManager().release(id);
  CACHE.clear();faces=null;mouths=null;failed=false;
 }
}
