package dev.johnmart19.veritycompanion;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Creates host-local cloud settings without a launcher, installer or Python. */
final class NativeBootstrap {
 private NativeBootstrap() {}

 /** Set before our first HttpClient: Windows JDK selector pipes use short socket paths. */
 static synchronized void preparePlatform(){
  if(!System.getProperty("os.name","").startsWith("Windows")||System.getProperty("jdk.net.unixdomain.tmpdir")!=null)return;
  try{
   Path sockets=Path.of(System.getProperty("user.home"),".vfc-jtmp").toAbsolutePath().normalize();
   // Leave room for the JDK's socket filename inside the native AF_UNIX path limit.
   if(sockets.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>80)return;
   privateDirectory(sockets);
   System.setProperty("jdk.net.unixdomain.tmpdir",sockets.toString());
  }catch(IOException|SecurityException unavailable){/* Retain the host's JDK defaults if this account cannot prepare a short path. */}
 }

 static void prepare(Path root)throws IOException {
  Files.createDirectories(root);
  privateDirectory(root.resolve(".secrets"));
 }
 private static void privateDirectory(Path secrets)throws IOException {
  if(Files.exists(secrets,LinkOption.NOFOLLOW_LINKS)){
   if(!Files.isDirectory(secrets))throw new IOException("Server secret directory is unavailable");
   return; // Preserve existing credentials and host-selected account permissions.
  }
  try{
   FileStore store=Files.getFileStore(secrets.toAbsolutePath().getParent());
   if(store.supportsFileAttributeView("posix")){
    Files.createDirectory(secrets,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
   }else if(store.supportsFileAttributeView("acl")){
    UserPrincipal account=secrets.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
    AclEntry owner=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(account)
     .setPermissions(EnumSet.allOf(AclEntryPermission.class))
     .setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.DIRECTORY_INHERIT).build();
    // Windows applies this descriptor during creation. Never publish an inherited,
    // broadly writable directory and then attempt to tighten its permissions.
    FileAttribute<List<AclEntry>> initial=new FileAttribute<>(){
     public String name(){return "acl:acl";}
     public List<AclEntry> value(){return List.of(owner);}
    };
    Files.createDirectory(secrets,initial);
   }else Files.createDirectory(secrets); // Filesystems without permission views retain their host policy.
  }catch(FileAlreadyExistsException concurrent){
   if(!Files.isDirectory(secrets))throw new IOException("Server secret directory is unavailable");
  }
 }
}
