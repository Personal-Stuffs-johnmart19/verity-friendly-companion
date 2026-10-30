package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;

/** Completed audio only. get/put must use the same effective provider configuration. */
final class NativeSpeechCache {
 private static final long MAX_BYTES=64L*1024*1024;
 private static final int MAX_FILES=256, MAX_WAV=6_100_000;
 private final Path root;
 private final LinkedHashMap<String,String> observed=new LinkedHashMap<>();
 NativeSpeechCache(Path root){this.root=root.toAbsolutePath().normalize();}
 private Path directory()throws IOException {
  Files.createDirectories(root);Path current=root.toRealPath();
  for(String segment:List.of("cache","native-speech")){
   Path next=current.resolve(segment);
   if(!Files.exists(next,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(next);
   if(Files.isSymbolicLink(next)||!Files.isDirectory(next,LinkOption.NOFOLLOW_LINKS)||!next.toRealPath().equals(next))throw new IOException("Unsafe speech cache directory");
   current=next;
  }
  return current;
 }
 private static BasicFileAttributes regular(Path file)throws IOException {
  BasicFileAttributes attributes=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
  if(!attributes.isRegularFile()||attributes.isSymbolicLink())throw new IOException("Unsafe speech cache entry");return attributes;
 }
 private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
 private static String content(Path file,int maximum)throws Exception {
  try(var input=Files.newInputStream(file)){return hash(NativeAudioProcess.readBounded(input,maximum));}
 }
 private static void metadata(JsonObject target,String name,Path file)throws IOException {
  BasicFileAttributes attributes=Files.readAttributes(file,BasicFileAttributes.class);
  target.addProperty(name,file.toRealPath()+":"+attributes.size()+":"+attributes.lastModifiedTime());
 }
 private String key(JsonObject config,String text,String emotion,String voice)throws Exception {
  JsonObject fingerprint=new JsonObject();String provider=ProviderHttp.text(config,"provider","google");
  if(!Set.of("google","drama3","silero").contains(provider))throw new IOException("Uncacheable speech provider");
  fingerprint.addProperty("version",1);fingerprint.addProperty("provider",provider);fingerprint.addProperty("text",text);fingerprint.addProperty("emotion",emotion);fingerprint.addProperty("voice",voice);
  for(String name:List.of("tts_language","google_voice","google_tts_models","google_tts_model","google_model","russian_model","russian_speaker","english_model","english_speaker","silero_speaker","silero_model","silero_sample_rate","silero_rate","silero_language"))if(config.has(name))fingerprint.add(name,config.get(name));
  if(provider.equals("drama3")){
   Path directory=root.resolve(ProviderHttp.text(config,"drama3_directory","../verity-tts")).toAbsolutePath().normalize();
   fingerprint.addProperty("drama_directory",directory.toRealPath().toString());
   Path settings=directory.resolve("server.json");fingerprint.addProperty("drama_settings",content(settings,262_144));
   try(var input=Files.newInputStream(settings)){
    JsonObject prepared=ProviderHttp.object(new String(NativeAudioProcess.readBounded(input,262_144),StandardCharsets.UTF_8));
    for(JsonElement model:ProviderHttp.array(prepared,"models")){JsonObject entry=model.getAsJsonObject();if("verity-drama3".equals(ProviderHttp.text(entry,"id","")))metadata(fingerprint,"model",directory.resolve(ProviderHttp.text(entry,"path","")));}
   }
   fingerprint.addProperty("drama_reference_text",content(directory.resolve("voices/reference-text.json"),65_536));
   for(String language:List.of("ru","en"))metadata(fingerprint,"reference_"+language,directory.resolve("voices/verity-"+language+".wav"));
   metadata(fingerprint,"runtime",root.resolve(ProviderHttp.text(config,"drama3_executable",directory.resolve("runtime/audiocpp_server.exe").toString())));
  }
  return "v1-"+hash(fingerprint.toString().getBytes(StandardCharsets.UTF_8))+".wav";
 }
 private static String request(JsonObject config,String text,String emotion,String voice)throws Exception {
  JsonArray identity=new JsonArray();identity.add(config);identity.add(text);identity.add(emotion);identity.add(voice);return hash(identity.toString().getBytes(StandardCharsets.UTF_8));
 }
 synchronized byte[] get(JsonObject config,String text,String emotion,String voice){
  Path file=null;
  try{
   Path directory=directory();String key=key(config,text,emotion,voice);observed.put(request(config,text,emotion,voice),key);while(observed.size()>MAX_FILES)observed.remove(observed.keySet().iterator().next());
   file=directory.resolve(key);BasicFileAttributes attributes=regular(file);
   if(attributes.size()>MAX_WAV)throw new IOException("Oversized speech cache entry");
   byte[] audio;try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){audio=NativeAudioProcess.readBounded(input,MAX_WAV);}
   NativeGoogle.validateWav(audio);Files.getFileAttributeView(file,BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).setTimes(FileTime.fromMillis(System.currentTimeMillis()),null,null);return audio;
  }catch(Exception invalid){
   if(file!=null)try{regular(file);Files.deleteIfExists(file);}catch(IOException ignored){}
   return null;
  }
 }
 synchronized void put(JsonObject config,String text,String emotion,String voice,byte[] audio){
  Path temporary=null;
  try{
   if(audio==null||audio.length>MAX_WAV)return;NativeGoogle.validateWav(audio);
   Path directory=directory();String key=key(config,text,emotion,voice),previous=observed.get(request(config,text,emotion,voice));
   if(previous!=null&&!previous.equals(key))return; // References changed while a miss was being synthesized.
   Path target=directory.resolve(key);
   if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))regular(target);
   prune(directory,audio.length);
   temporary=directory.resolve(".verity-"+UUID.randomUUID()+".tmp");
   try(FileChannel output=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
    ByteBuffer bytes=ByteBuffer.wrap(audio);while(bytes.hasRemaining())output.write(bytes);output.force(false);
   }
   // Recheck the directory before publishing; never follow a cache-entry symlink.
   if(!directory().equals(directory))throw new IOException("Speech cache directory changed");
   if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))regular(target);
   Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);temporary=null;
  }catch(Exception ignored){/* A cache failure never prevents the original completed speech. */}
  finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
 }
 private static void prune(Path directory,int incoming)throws IOException {
  record Entry(Path path,long size,long used){}
  List<Entry> entries=new ArrayList<>();long total=0;
  try(var stream=Files.newDirectoryStream(directory,"v1-*.wav")){
   for(Path file:stream){if(!file.getFileName().toString().matches("v1-[0-9a-f]{64}\\.wav"))continue;
    try{var attributes=regular(file);entries.add(new Entry(file,attributes.size(),attributes.lastModifiedTime().toMillis()));total+=attributes.size();}catch(IOException unsafe){}
   }
  }
  entries.sort(Comparator.comparingLong(Entry::used));int count=entries.size();
  for(Entry entry:entries){
   if(total+incoming<=MAX_BYTES&&count+1<=MAX_FILES)break;
   regular(entry.path());Files.delete(entry.path());total-=entry.size();count--;
  }
 }
}
