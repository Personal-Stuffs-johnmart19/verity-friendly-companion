import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Standard-Java installer metadata helper; no models, network, keys or third-party libraries. */
class LinuxSetup {
 static final int LIMIT=1_048_576;
 static void require(boolean condition,String message)throws IOException{if(!condition)throw new IOException(message);}
 static Path path(String value)throws IOException {
  require(!value.isBlank()&&value.chars().noneMatch(c->c<32||c==127),"Invalid path");
  Path result=Path.of(value).toAbsolutePath().normalize(),current=result.getRoot();
  for(Path part:result){current=current.resolve(part);require(!Files.isSymbolicLink(current),"Symbolic-link path component refused: "+current);}
  return result;
 }
 static Path directory(String value,boolean create)throws IOException {
  Path result=path(value);if(create)Files.createDirectories(result);
  require(Files.isDirectory(result,LinkOption.NOFOLLOW_LINKS),"Directory missing: "+result);
  require(result.toRealPath().equals(result),"Redirected directory refused: "+result);return result;
 }
 static String bounded(Path file,int maximum)throws IOException {
  require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"Regular file missing: "+file);
  try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=in.readNBytes(maximum+1);require(bytes.length<=maximum,"Metadata exceeds size limit");return new String(bytes,StandardCharsets.UTF_8).replace("\ufeff","");}
 }
 static Map<String,Object> object(Object value)throws IOException {require(value instanceof Map,"Expected JSON object");@SuppressWarnings("unchecked")var result=(Map<String,Object>)value;return result;}
 static Map<String,Object> read(Path file)throws IOException{return object(new Json(bounded(file,LIMIT)).parse());}
 static String string(Map<String,Object> map,String key,String fallback){Object value=map.get(key);return value instanceof String s?s:fallback;}
 static List<?> array(Map<String,Object> map,String key){return map.get(key) instanceof List<?> values?values:List.of();}
 static String hash(Path file)throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=new byte[65536];int count;while((count=in.read(bytes))!=-1)digest.update(bytes,0,count);}return HexFormat.of().formatHex(digest.digest());}
 static void atomic(Path file,String text,boolean replace)throws IOException {
  Path parent=directory(file.getParent().toString(),true);path(file.toString());
  require(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)||Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"Unsafe metadata destination");
  if(!replace&&Files.exists(file))return;
  Path temporary=Files.createTempFile(parent,".verity-setup-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
  try{Files.writeString(temporary,text,StandardCharsets.UTF_8);directory(parent.toString(),false);path(file.toString());
   if(replace)Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);else try{publishNew(temporary,file);}catch(FileAlreadyExistsException concurrent){/* A concurrently created host config is preserved. */}
  }finally{Files.deleteIfExists(temporary);}
 }
 static void json(Path file,Map<String,Object> value,boolean replace)throws IOException{atomic(file,Json.write(value)+"\n",replace);}
 static boolean loader(String value){if(!value.matches("21\\.1\\.[0-9]{1,6}"))return false;return Integer.parseInt(value.substring(5))>=250;}
 static String clientVersion(Path root)throws IOException {
  for(String name:List.of("minecraftinstance.json","manifest.json")){
   Path file=root.resolve(name);if(!Files.exists(file))continue;Map<String,Object> data=read(file);String mc="",version="";
   if(name.equals("minecraftinstance.json")&&data.get("baseModLoader") instanceof Map){var base=object(data.get("baseModLoader"));mc=string(base,"minecraftVersion","");version=string(base,"name","");}
   if(name.equals("manifest.json")&&data.get("minecraft") instanceof Map){var minecraft=object(data.get("minecraft"));mc=string(minecraft,"version","");for(Object item:array(minecraft,"modLoaders")){var entry=object(item);if(Boolean.TRUE.equals(entry.get("primary"))){require(version.isEmpty(),"Multiple primary loaders in modpack metadata");version=string(entry,"id","");}}}
   if(!version.isEmpty()){require(mc.equals("1.21.1")&&version.startsWith("neoforge-")&&loader(version.substring(9)),"Modpack requires another Minecraft/loader version; metadata preserved");return version.substring(9);}
  }
  Path versions=root.resolve("versions");List<String> found=new ArrayList<>();
  if(Files.isDirectory(versions,LinkOption.NOFOLLOW_LINKS))try(var entries=Files.newDirectoryStream(versions,"neoforge-21.1.*")){for(Path item:entries){String version=item.getFileName().toString().substring(9);if(loader(version)&&Files.isRegularFile(item.resolve("neoforge-"+version+".json"),LinkOption.NOFOLLOW_LINKS))found.add(version);require(found.size()<64,"Too many loader profiles");}}
  require(found.size()==1,"No unambiguous compatible client metadata; pass the initialized Minecraft launcher root");return found.getFirst();
 }
 static boolean clientLibraries(Path root,String version,Set<String> visited)throws IOException {
  require(version.matches("[A-Za-z0-9._-]{1,100}")&&visited.add(version)&&visited.size()<=5,"Invalid client profile inheritance");
  Map<String,Object> data=read(root.resolve("versions/"+version+"/"+version+".json"));
  String parent=string(data,"inheritsFrom","");if(!parent.isEmpty())require(clientLibraries(root,parent,visited),"Incomplete inherited client profile");
  if(version.startsWith("neoforge-"))require(parent.equals("1.21.1"),"NeoForge profile must inherit Minecraft 1.21.1");
  if(data.get("downloads") instanceof Map&&object(data.get("downloads")).containsKey("client"))require(Files.isRegularFile(root.resolve("versions/"+version+"/"+version+".jar"),LinkOption.NOFOLLOW_LINKS),"Minecraft client JAR missing");
  require(data.get("libraries") instanceof List,"Client library list missing");
  for(Object item:array(data,"libraries")){
   var library=object(item);boolean allowed=!library.containsKey("rules");
   for(Object value:array(library,"rules")){var rule=object(value);if(rule.get("features") instanceof Map&&!object(rule.get("features")).isEmpty())continue;
    if(rule.get("os") instanceof Map){var os=object(rule.get("os"));if(!string(os,"name","linux").equals("linux"))continue;String arch=string(os,"arch","");if(!arch.isEmpty()&&!System.getProperty("os.arch").matches(arch))continue;String kernel=string(os,"version","");if(!kernel.isEmpty()&&!System.getProperty("os.version").matches(kernel))continue;}
    allowed=string(rule,"action","").equals("allow");
   }
   if(!allowed)continue;List<String> paths=new ArrayList<>();
   if(library.get("downloads") instanceof Map){var downloads=object(library.get("downloads"));if(downloads.get("artifact") instanceof Map)paths.add(string(object(downloads.get("artifact")),"path",""));
    if(library.get("natives") instanceof Map){String key=string(object(library.get("natives")),"linux","").replace("${arch}",System.getProperty("os.arch").contains("64")?"64":"32");if(!key.isEmpty()){require(downloads.get("classifiers") instanceof Map,"Client native classifiers missing");var classifiers=object(downloads.get("classifiers"));paths.add(string(object(classifiers.get(key)),"path",""));}}
   }else{String[] coordinates=string(library,"name","").split(":");require(coordinates.length>=3,"Invalid client library coordinate");String classifier=coordinates.length>3?"-"+coordinates[3]:"";paths.add(coordinates[0].replace('.','/')+"/"+coordinates[1]+"/"+coordinates[2]+"/"+coordinates[1]+"-"+coordinates[2]+classifier+".jar");}
   for(String relative:paths){Path file=root.resolve("libraries").resolve(relative).normalize();require(!relative.isEmpty()&&file.startsWith(root.resolve("libraries"))&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"Client library missing: "+relative);}
  }
  return true;
 }
 static String serverVersion(Path root,String hint)throws IOException {
  if(!hint.isEmpty()){require(loader(hint),"Unsupported NeoForge version");return hint;}
  Path run=root.resolve("run.sh");if(Files.isRegularFile(run,LinkOption.NOFOLLOW_LINKS)){Matcher active=Pattern.compile("libraries/net/neoforged/neoforge/(21\\.1\\.[0-9]+)/unix_args\\.txt").matcher(bounded(run,65536));if(active.find()&&loader(active.group(1)))return active.group(1);}
  Path directory=root.resolve("libraries/net/neoforged/neoforge");List<String> candidates=new ArrayList<>();
  if(Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))try(var entries=Files.newDirectoryStream(directory)){for(Path file:entries){String version=file.getFileName().toString();if(loader(version)&&Files.isRegularFile(file.resolve("unix_args.txt"),LinkOption.NOFOLLOW_LINKS))candidates.add(version);require(candidates.size()<64,"Too many NeoForge versions");}}
  require(candidates.size()==1,"No unambiguous NeoForge 21.1.250+ server; use --loader-version when needed");return candidates.getFirst();
 }
 static Path serverArgs(Path root,String hint)throws IOException {
  String version=serverVersion(root,hint);Path args=root.resolve("libraries/net/neoforged/neoforge/"+version+"/unix_args.txt");String contents=bounded(args,LIMIT);
  Matcher files=Pattern.compile("libraries/[^\\s;:\"\\r\\n]+?\\.jar").matcher(contents);boolean any=false,minecraft=false;
  while(files.find()){String relative=files.group();Path file=root.resolve(relative).normalize();require(file.startsWith(root.resolve("libraries"))&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)>0,"Missing server library: "+relative);any=true;if(relative.contains("/minecraft/")&&relative.contains("1.21.1"))minecraft=true;}
  require(any&&minecraft,"Server arguments do not identify complete Minecraft 1.21.1 libraries");return args;
 }
 record Mod(Path file,String id,String version){}
 static List<Mod> mods(Path root)throws IOException {
  Path folder=root.resolve("mods");if(!Files.exists(folder))return List.of();directory(folder.toString(),false);List<Mod> result=new ArrayList<>();int count=0;
  try(var files=Files.newDirectoryStream(folder,"*.jar")){for(Path file:files){require(++count<=4096,"Too many mods");require(!Files.isSymbolicLink(file),"Symbolic-link mod refused");
   try(ZipFile archive=new ZipFile(file.toFile())){ZipEntry entry=archive.getEntry("META-INF/neoforge.mods.toml");if(entry==null)continue;String text;try(InputStream input=archive.getInputStream(entry)){byte[] bytes=input.readNBytes(131073);require(bytes.length<=131072,"Mod metadata too large");text=new String(bytes,StandardCharsets.UTF_8);}
    String manifestVersion="";ZipEntry manifest=archive.getEntry("META-INF/MANIFEST.MF");if(manifest!=null)try(InputStream input=archive.getInputStream(manifest)){byte[] bytes=input.readNBytes(131073);require(bytes.length<=131072,"Mod manifest too large");var attributes=new java.util.jar.Manifest(new ByteArrayInputStream(bytes)).getMainAttributes();manifestVersion=attributes.getValue("Implementation-Version");}
    for(String block:text.split("\\[\\[mods]]")){Matcher id=Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(block),version=Pattern.compile("(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"").matcher(block);if(id.find()&&version.find()){String value=version.group(1);if(value.contains("${file.jarVersion}")&&manifestVersion!=null)value=manifestVersion;result.add(new Mod(file,id.group(1),value));}}
   }catch(ZipException invalid){/* Unrelated damaged mods are preserved; required IDs remain missing. */}
  }}return result;
 }
 static String addon(Path file)throws IOException {
  try(ZipFile archive=new ZipFile(file.toFile())){require(archive.getEntry("dev/johnmart19/veritycompanion/CompanionAddon.class")!=null,"Not a Verity Friendly Companion addon JAR");ZipEntry entry=archive.getEntry("META-INF/neoforge.mods.toml");require(entry!=null,"Addon metadata missing");String metadata;try(InputStream input=archive.getInputStream(entry)){byte[] bytes=input.readNBytes(131073);require(bytes.length<=131072,"Addon metadata too large");metadata=new String(bytes,StandardCharsets.UTF_8);}Matcher version=Pattern.compile("(?m)^version=\"([A-Za-z0-9._-]+)\"").matcher(metadata);require(version.find()&&metadata.contains("modId=\"verity_companion\""),"Invalid addon metadata");return version.group(1);}
 }
 static List<Mod> id(List<Mod> mods,String name){return mods.stream().filter(mod->mod.id.equals(name)).toList();}
 static boolean compatible(Mod mod,String expected){if(mod.id.equals("geckolib")){Matcher version=Pattern.compile("4\\.([0-9]{1,6})\\.([0-9]{1,6})").matcher(mod.version);return version.matches()&&(Integer.parseInt(version.group(1))>9||Integer.parseInt(version.group(1))==9&&Integer.parseInt(version.group(2))>=3);}return mod.version.equals(expected);}
 static String modState(Path root,String name,String expected)throws Exception {var found=id(mods(root),name);require(found.size()<=1,"Multiple "+name+" mods found; preserve and resolve them before setup");if(found.isEmpty())return "missing";Mod mod=found.getFirst();require(compatible(mod,expected),"Incompatible "+name+" mod: "+mod.file);if(name.equals("verity"))require(hash(mod.file).equals("b32223e720affda0e617efc694c3fb0bda4cb7de5941890c5e9cc828b700a93b"),"Verity 3.4.1 differs from the supported official original JAR; move patched copies aside explicitly. Existing file preserved: "+mod.file);return "present";}
 static void copy(Path source,Path target)throws Exception {
  require(Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS),"Source JAR missing");Path parent=directory(target.getParent().toString(),true);path(target.toString());require(!Files.exists(target,LinkOption.NOFOLLOW_LINKS),"Destination exists; preserved: "+target);
  Path temporary=Files.createTempFile(parent,".verity-copy-",".tmp");try{Files.copy(source,temporary,StandardCopyOption.REPLACE_EXISTING);require(hash(source).equals(hash(temporary)),"JAR copy checksum mismatch");path(target.toString());publishNew(temporary,target);}finally{Files.deleteIfExists(temporary);}
 }
 static void publishNew(Path temporary,Path target)throws IOException {
  // A same-filesystem hardlink is atomically no-clobber. ATOMIC_MOVE without
  // REPLACE_EXISTING may still replace targets on Unix, so it cannot serve here.
  try{Files.createLink(target,temporary);}catch(UnsupportedOperationException unsupported){throw new IOException("Destination filesystem lacks safe no-clobber publication",unsupported);}
 }
 static void installAddon(Path root,Path source)throws Exception {
  String version=addon(source);var existing=id(mods(root),"verity_companion");require(existing.size()<=1,"Multiple addons found; resolve before installing");
  if(!existing.isEmpty()){
   Path old=existing.getFirst().file;if(hash(old).equals(hash(source)))return;
   Path backup=directory(root.resolve("verity-install-backups/"+UUID.randomUUID()).toString(),true).resolve(old.getFileName());
   copy(old,backup);String previous=hash(backup),incoming=hash(source);require(hash(old).equals(previous),"Addon changed during backup; original preserved");
   Path target=root.resolve("mods/verity-friendly-companion-"+version+".jar"),stage=Files.createTempFile(directory(root.resolve("mods").toString(),true),".verity-new-",".tmp");boolean published=false,removed=false;
   try{
    Files.copy(source,stage,StandardCopyOption.REPLACE_EXISTING);require(hash(stage).equals(incoming)&&hash(old).equals(previous),"Addon changed while preparing replacement");path(target.toString());
    if(target.equals(old))Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);else publishNew(stage,target);published=true;
    require(hash(target).equals(incoming),"Installed addon integrity failure");if(!target.equals(old)){Files.delete(old);removed=true;}
   }catch(Exception failure){
    if(published){if(target.equals(old)){Path restore=Files.createTempFile(old.getParent(),".verity-restore-",".tmp");try{Files.copy(backup,restore,StandardCopyOption.REPLACE_EXISTING);Files.move(restore,old,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(restore);}}
     else{if(removed&&!Files.exists(old))copy(backup,old);if(Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)&&hash(target).equals(incoming))Files.delete(target);}}
    throw failure;
   }finally{Files.deleteIfExists(stage);}
   System.err.println("Previous addon preserved: "+backup);return;
  }
  copy(source,root.resolve("mods/verity-friendly-companion-"+version+".jar"));
 }
 static void bootstrap(Path root,String profile,String javaHome,String backendList,String python,String target)throws IOException {
  require(Set.of("basic","advanced").contains(profile)&&Set.of("server","client").contains(target),"Invalid installation profile");
  Path config=directory(root.resolve("config").toString(),true),manifest=config.resolve("verity-installation.json");Map<String,Object> record=Files.exists(manifest)?read(manifest):new LinkedHashMap<>();
  record.put("schema",1);record.put("profile",profile);record.put("platform","linux-"+System.getProperty("os.arch"));record.put("java_home",javaHome);record.put("target",target);record.put("advisory",true);
  Set<String> backends=new LinkedHashSet<>();for(Object value:array(record,"installed_backends"))if(value instanceof String s)backends.add(s);if(!backendList.isBlank())backends.addAll(List.of(backendList.split(",")));record.put("installed_backends",new ArrayList<>(backends));
  if(target.equals("server")&&!python.isBlank())record.put("python_executable",python);if(target.equals("client")){record.remove("python_executable");record.remove("venv_root");}
  json(manifest,record,true);
  if(target.equals("server")){
   Path service=directory(root.resolve("verity-service").toString(),true);Path secrets=service.resolve(".secrets");path(secrets.toString());if(!Files.exists(secrets))Files.createDirectory(secrets,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));else require(Files.isDirectory(secrets,LinkOption.NOFOLLOW_LINKS),"Existing secret path is not a directory; preserved");
   json(service.resolve("voice-config.json"),new LinkedHashMap<>(Map.of("provider","google","stt_engine","disabled","google_free_project_confirmed",false,"google_audio_transcription_confirmed",false,"allow_external_requests",true,"playback_mode","complete")),false);
   json(config.resolve("verity-companion-server.json"),new LinkedHashMap<>(Map.of("runtime","native","endpoint","http://127.0.0.1:8766","tokenFile","verity-service/.secrets/bridge-token")),false);
   if(profile.equals("advanced")){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);atomic(secrets.resolve("bridge-token"),Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)+"\n",false);}
  }
 }
 static void modelMetadata(Path file,String name,String revision)throws IOException {
  var metadata=object(new Json(bounded(file,8_388_608)).parse());require(string(metadata,"sha","").equals(revision),"Model revision mismatch");Map<String,Object> selected=null;
  for(Object value:array(metadata,"siblings")){var entry=object(value);if(string(entry,"rfilename","").equals(name)){require(selected==null,"Duplicate model file metadata");selected=entry;}}
  require(selected!=null&&selected.get("lfs") instanceof Map,"Model lacks LFS integrity metadata");var lfs=object(selected.get("lfs"));String sha=string(lfs,"sha256","");Object size=selected.get("size");require(sha.matches("[0-9a-f]{64}")&&size instanceof java.math.BigDecimal,"Invalid model digest/size");long bytes=((java.math.BigDecimal)size).longValueExact();require(bytes>0&&bytes<=40L*1024*1024*1024,"Model size exceeds supported bound");System.out.println(sha+" "+bytes);
 }
 static Path modelPath(Path root,String name)throws IOException {Path file=root.resolve(name).normalize();require(file.startsWith(root)&&!file.equals(root)&&!Path.of(name).isAbsolute(),"Model filename escapes prepared directory");path(file.toString());return file;}
 static void modelRecord(Path root,String repo,String revision,String name,String sha,long bytes)throws IOException {
  require(Set.of("audio-cpp/audio.cpp-gguf","Jmica/Drama-3-GGUF").contains(repo)&&revision.matches("[0-9a-f]{40}")&&sha.matches("[0-9a-f]{64}")&&bytes>0,"Invalid pinned model identity");Path file=modelPath(root,name);require(Files.size(file)==bytes,"Model size mismatch");
  Path manifest=root.resolve("verity-assets.json");Map<String,Object> data=Files.exists(manifest)?read(manifest):new LinkedHashMap<>();require(!data.containsKey("repo")||string(data,"repo","").equals(repo)&&string(data,"revision","").equals(revision),"Existing model manifest belongs to another pinned identity; preserved");List<Object> entries=new ArrayList<>();for(Object value:array(data,"files"))if(!string(object(value),"name","").equals(name))entries.add(value);entries.add(new LinkedHashMap<>(Map.of("name",name,"sha256",sha,"bytes",bytes)));data.put("repo",repo);data.put("revision",revision);data.put("files",entries);json(manifest,data,true);
 }
 static void modelCheck(Path root,String repo,String revision,String name)throws Exception {
  var manifest=read(root.resolve("verity-assets.json"));require(string(manifest,"repo","").equals(repo)&&string(manifest,"revision","").equals(revision),"Prepared model identity mismatch");Map<String,Object> selected=null;for(Object value:array(manifest,"files")){var entry=object(value);if(string(entry,"name","").equals(name)){require(selected==null,"Duplicate model manifest entry");selected=entry;}}
  require(selected!=null,"Prepared model digest missing");Path file=modelPath(root,name);Object bytes=selected.get("bytes");require(bytes instanceof java.math.BigDecimal&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)==((java.math.BigDecimal)bytes).longValueExact()&&hash(file).equals(string(selected,"sha256","")),"Prepared model integrity failure; existing files preserved");System.out.println(file);
 }
 static void referenceWave(Path file)throws IOException {
  require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)>=44&&Files.size(file)<=64L*1024*1024,"Use a bounded PCM16 reference WAV, maximum 64 MiB");
  try(var input=new DataInputStream(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS))){require(input.readInt()==0x52494646,"Reference is not RIFF WAV");input.readInt();require(input.readInt()==0x57415645,"Reference is not WAV");long remaining=Files.size(file)-12,data=0;int channels=0,rate=0,align=0,bits=0,format=0;
   while(remaining>=8){int tag=input.readInt();long size=Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));remaining-=8;require(size+(size&1)<=remaining,"Truncated reference WAV");if(tag==0x666d7420){require(size>=16,"Invalid reference format");format=Short.toUnsignedInt(Short.reverseBytes(input.readShort()));channels=Short.toUnsignedInt(Short.reverseBytes(input.readShort()));rate=Integer.reverseBytes(input.readInt());long byteRate=Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));align=Short.toUnsignedInt(Short.reverseBytes(input.readShort()));bits=Short.toUnsignedInt(Short.reverseBytes(input.readShort()));require(byteRate==(long)rate*align,"Invalid reference byte rate");input.skipNBytes(size-16);}else{if(tag==0x64617461)data+=size;input.skipNBytes(size);}if((size&1)!=0)input.skipNBytes(1);remaining-=size+(size&1);}
   require(format==1&&(channels==1||channels==2)&&rate>=8000&&rate<=192000&&bits==16&&align==channels*2&&data%align==0&&data>=(long)rate*align&&data<=30L*rate*align,"Reference must be unmodified PCM16 mono/stereo WAV, 1-30 seconds, 8-192 kHz");
  }
 }
 static void references(Path root,String referenceConfig)throws Exception {
  Path voices=directory(root.resolve("voices").toString(),true),text=voices.resolve("reference-text.json");Map<String,Object> existing=Files.exists(text)?read(text):new LinkedHashMap<>(),requested=referenceConfig.isEmpty()?Map.of():read(path(referenceConfig));List<Path[]> copies=new ArrayList<>();
  for(String language:List.of("ru","en")){Path target=voices.resolve("verity-"+language+".wav");if(Files.exists(target)&&!string(existing,language,"").isBlank()){referenceWave(target);continue;}require(requested.get(language) instanceof Map,"Drama3 requires private RU/EN WAVs and exact transcripts; supply --reference-config");var reference=object(requested.get(language));String transcript=string(reference,"text","");require(!transcript.isBlank()&&transcript.length()<=8192,"Reference transcript missing/oversized");Path source=path(string(reference,"audio",""));referenceWave(source);if(Files.exists(target))require(hash(source).equals(hash(target)),"Existing reference differs; it is preserved");else copies.add(new Path[]{source,target});existing.put(language,transcript);}
  for(Path[] item:copies)copy(item[0],item[1]);json(text,existing,true);
 }
 static void drama(Path root,String executable,String model)throws Exception {
  Path settings=root.resolve("server.json");if(!Files.exists(settings)){var entry=new LinkedHashMap<String,Object>();entry.put("id","verity-drama3");entry.put("family","fish_drama3");entry.put("path",path(model).toString());entry.put("task","tts");entry.put("mode","streaming");entry.put("session_options",Map.of("fish_drama3.pin_experts",false));json(settings,new LinkedHashMap<>(Map.of("host","127.0.0.1","port",8773,"backend","vulkan","threads",4,"lazy_load",true,"max_loaded_models",1,"models",List.of(entry))),false);}
  var config=read(settings);require(string(config,"backend","").equals("vulkan"),"Existing Drama3 backend differs from the prepared Vulkan binary; existing config preserved");int found=0;for(Object value:array(config,"models")){var entry=object(value);if(!string(entry,"id","").equals("verity-drama3"))continue;found++;require(string(entry,"family","").equals("fish_drama3")&&Set.of("tts","clon").contains(string(entry,"task",""))&&string(entry,"mode","").equals("streaming"),"Existing Drama3 model/session is incompatible; preserved");Path file=root.resolve(string(entry,"path","")).normalize();require(Files.isRegularFile(file)&&Files.size(file)>0,"Prepared Drama3 GGUF missing");}require(found==1,"Expected one prepared Drama3 model");require(Files.isRegularFile(path(executable)),"Prepared Drama3 executable missing");
 }
 static void nativeRecord(Path root,String backend,String archive,Path executable)throws Exception {
  require(Set.of("cpu","vulkan").contains(backend)&&archive.matches("[0-9a-f]{64}")&&executable.startsWith(root)&&Files.isRegularFile(executable,LinkOption.NOFOLLOW_LINKS)&&Files.isExecutable(executable),"Invalid native binary identity");
  json(root.resolve("verity-native-runtime.json"),new LinkedHashMap<>(Map.of("schema",1,"platform","linux-x64","backend",backend,"archive_sha256",archive,"executable",executable.toString(),"executable_sha256",hash(executable))),false);
 }
 static void nativeCheck(Path root,String backend,String archive)throws Exception {
  var manifest=read(root.resolve("verity-native-runtime.json"));Path executable=path(string(manifest,"executable",""));require(string(manifest,"platform","").equals("linux-x64")&&string(manifest,"backend","").equals(backend)&&string(manifest,"archive_sha256","").equals(archive)&&executable.startsWith(root)&&Files.isRegularFile(executable,LinkOption.NOFOLLOW_LINKS)&&Files.isExecutable(executable)&&hash(executable).equals(string(manifest,"executable_sha256","")),"Prepared native runtime identity mismatch; existing files preserved");System.out.println(executable);
 }
 public static void main(String[] args){try{require(args.length>0,"Installer helper command required");String command=args[0];switch(command){
  case "client-version"->System.out.println(clientVersion(directory(args[1],false)));
  case "client-check"->{Path root=directory(args[1],false);String version=args.length>2?args[2]:clientVersion(root);require(loader(version),"Unsupported client loader");require(clientLibraries(root,"neoforge-"+version,new HashSet<>()),"Client libraries incomplete");System.out.println(version);}
  case "server-version"->System.out.println(serverVersion(directory(args[1],false),args.length>2?args[2]:""));
  case "server-args"->System.out.println(serverArgs(directory(args[1],false),args.length>2?args[2]:""));
  case "addon-version"->System.out.println(addon(path(args[1])));
  case "mod-state"->System.out.println(modState(directory(args[1],false),args[2],args[3]));
  case "copy-mod"->{Path root=directory(args[1],true),source=path(args[2]);String name=args[3];require(name.matches("[A-Za-z0-9._-]+\\.jar"),"Invalid mod filename");copy(source,root.resolve("mods/"+name));}
  case "export"->{Path source=path(args[1]),target=path(args[2]);addon(source);if(Files.exists(target)){require(Files.isRegularFile(target)&&Files.mismatch(source,target)==-1,"Export target differs; preserved");}else copy(source,target);}
  case "bridge-info"->{Path root=directory(args[1],false);var manifest=read(root.resolve("config/verity-installation.json"));if(!string(manifest,"profile","").equals("advanced"))return;var voice=read(root.resolve("verity-service/voice-config.json"));String python=string(voice,"python_executable","");if(python.isBlank())return;require(python.chars().noneMatch(c->c<32||c==127)&&Path.of(python).isAbsolute()&&Files.isRegularFile(Path.of(python))&&Files.isExecutable(Path.of(python)),"Prepared Python executable missing");var config=read(root.resolve("config/verity-companion-server.json"));var endpoint=java.net.URI.create(string(config,"endpoint","http://127.0.0.1:8766"));require(endpoint.getScheme().equals("http")&&Set.of("127.0.0.1","localhost").contains(endpoint.getHost())&&endpoint.getUserInfo()==null&&endpoint.getRawQuery()==null&&endpoint.getFragment()==null&&(endpoint.getRawPath().isEmpty()||endpoint.getRawPath().equals("/")),"Optional adapter endpoint must be local HTTP");int port=endpoint.getPort();require(port>=1024&&port<=65535,"Invalid adapter port");Object configured=voice.get("port");require(configured==null||configured instanceof java.math.BigDecimal number&&number.intValueExact()==port,"Adapter endpoint and voice-config port differ");require(configured!=null||port==8766,"Set the matching port in voice-config before launching");Path token=path(root.resolve(string(config,"tokenFile","verity-service/.secrets/bridge-token")).toString());require(token.startsWith(root.resolve("verity-service/.secrets")),"Adapter token must remain in the selected service secrets directory");String secret=bounded(token,2048).strip();require(secret.matches("[A-Za-z0-9_+/=-]{20,1024}"),"Invalid private adapter token");System.out.println(python+"\n"+port+"\n"+token);}
  case "bridge-status"->{var data=read(path(args[1]));require(Boolean.TRUE.equals(data.get("ready"))&&string(data,"runtime","").equals("python-audio-only"),"Unexpected listener on optional adapter endpoint");}
  case "install-addon"->installAddon(directory(args[1],true),path(args[2]));
  case "bootstrap"->bootstrap(directory(args[1],true),args[2],args[3],args[4],args[5],args[6]);
  case "java-home"->{Path root=directory(args[1],true);atomic(root.resolve("verity-service/java-home.txt"),path(args[2])+"\n",true);}
  case "model-meta"->modelMetadata(path(args[1]),args[2],args[3]);
  case "model-record"->modelRecord(directory(args[1],true),args[2],args[3],args[4],args[5],Long.parseLong(args[6]));
  case "model-check"->modelCheck(directory(args[1],false),args[2],args[3],args[4]);
  case "references"->references(directory(args[1],true),args.length>2?args[2]:"");
  case "drama"->drama(directory(args[1],true),args[2],args[3]);
  case "native-record"->nativeRecord(directory(args[1],false),args[2],args[3],path(args[4]));
  case "native-check"->nativeCheck(directory(args[1],false),args[2],args[3]);
  case "set-bool"->{Path file=path(args[1]);require(args[2].equals("stt_cpu_fallback")&&Set.of("true","false").contains(args[3]),"Unsupported boolean setting");var data=read(file);data.put(args[2],Boolean.parseBoolean(args[3]));json(file,data,true);}
  case "get"->{Object value=read(path(args[1])).get(args[2]);if(value instanceof String s)System.out.println(s);else if(value instanceof Boolean b)System.out.println(b);}
  case "set"->{Path file=path(args[1]);Map<String,Object> value=Files.exists(file)?read(file):new LinkedHashMap<>();require(args.length%2==0,"Setting pairs required");for(int i=2;i<args.length;i+=2){require(Set.of("provider","stt_engine","stt_native_backend","stt_native_primary_backend","stt_native_executable","stt_native_cpu_executable","stt_native_model","drama3_directory","drama3_executable","python_executable","stt_rocm_python","stt_rocm_model").contains(args[i]),"Unsupported setup field");value.put(args[i],args[i+1]);}json(file,value,true);}
  default->throw new IOException("Unknown installer helper command");
 }}catch(Exception failure){System.err.println("ERROR: "+failure.getMessage());System.exit(1);}}
 /** Strict bounded JSON parser: duplicate keys and trailing input are rejected. */
 static final class Json {
  final String input;int offset;Json(String input){this.input=input;}
  Object parse()throws IOException{Object value=value(0);space();require(offset==input.length(),"Trailing JSON data");return value;}
  void space(){while(offset<input.length()&&" \t\r\n".indexOf(input.charAt(offset))>=0)offset++;}
  char take()throws IOException{require(offset<input.length(),"Incomplete JSON");return input.charAt(offset++);}
  Object value(int depth)throws IOException{require(depth<=48,"JSON nesting exceeds limit");space();char c=take();if(c=='\"')return text();if(c=='{'){Map<String,Object> values=new LinkedHashMap<>();space();if(offset<input.length()&&input.charAt(offset)=='}'){offset++;return values;}for(;;){space();require(take()=='\"',"JSON object key required");String key=text();space();require(take()==':',"JSON colon required");require(!values.containsKey(key),"Duplicate JSON key");values.put(key,value(depth+1));space();c=take();if(c=='}')return values;require(c==',',"JSON comma required");}}
   if(c=='['){List<Object> values=new ArrayList<>();space();if(offset<input.length()&&input.charAt(offset)==']'){offset++;return values;}for(;;){values.add(value(depth+1));space();c=take();if(c==']')return values;require(c==',',"JSON comma required");}}
   offset--;for(String literal:List.of("true","false","null"))if(input.startsWith(literal,offset)){offset+=literal.length();return literal.equals("null")?null:literal.equals("true");}
   Matcher number=Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").matcher(input);number.region(offset,input.length());require(number.lookingAt(),"Invalid JSON value");offset=number.end();try{return new java.math.BigDecimal(number.group());}catch(NumberFormatException bad){throw new IOException("Invalid JSON number");}
  }
  String text()throws IOException{StringBuilder result=new StringBuilder();for(;;){char c=take();if(c=='\"')return result.toString();require(c>=32,"Control character in JSON string");if(c=='\\'){c=take();switch(c){case '\"','\\','/'->result.append(c);case 'b'->result.append('\b');case 'f'->result.append('\f');case 'n'->result.append('\n');case 'r'->result.append('\r');case 't'->result.append('\t');case 'u'->{require(offset+4<=input.length(),"Incomplete Unicode escape");try{result.append((char)Integer.parseInt(input.substring(offset,offset+4),16));}catch(NumberFormatException bad){throw new IOException("Invalid Unicode escape");}offset+=4;}default->throw new IOException("Invalid JSON escape");}}else result.append(c);}}
  static String write(Object value){if(value==null)return "null";if(value instanceof String s){StringBuilder out=new StringBuilder("\"");for(char c:s.toCharArray()){switch(c){case '\"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}}}return out.append('\"').toString();}if(value instanceof Map<?,?> map){List<String> values=new ArrayList<>();map.forEach((k,v)->values.add(write(k.toString())+":"+write(v)));return "{"+String.join(",",values)+"}";}if(value instanceof List<?> list)return "["+String.join(",",list.stream().map(Json::write).toList())+"]";return value.toString();}
 }
}
