package dev.johnmart19.veritycompanion;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.function.*;

/** Keeps damaged records for recovery; only validated objects become backups. */
public final class SafeJsonStore {
 private static <T>T read(Path path,Gson gson,Class<T> type,Consumer<T> validate)throws IOException {
  if(Files.size(path)>1048576)throw new JsonParseException("Record exceeds size limit");
  String text=Files.readString(path);if(text.startsWith("\uFEFF"))text=text.substring(1);
  JsonElement json=JsonParser.parseString(text);if(type==JsonArray.class?!json.isJsonArray():!json.isJsonObject())throw new JsonParseException("Unexpected JSON record shape");
  T value=gson.fromJson(json,type);validate.accept(value);return value;
 }
 public static <T>T load(Path file,Gson gson,Class<T> type,Supplier<T> defaults,Consumer<T> validate)throws IOException {
  if(Files.exists(file))try{return read(file,gson,type,validate);}catch(RuntimeException|java.nio.charset.CharacterCodingException corrupt){
   Path preserved=file.resolveSibling(file.getFileName()+".corrupt-"+java.util.UUID.randomUUID());Files.copy(file,preserved);
   System.err.println("[Verity Companion] Damaged relationship preserved; recovering from backup or defaults");
  }
  Path backup=file.resolveSibling(file.getFileName()+".bak");
  if(Files.exists(backup))try{return read(backup,gson,type,validate);}catch(RuntimeException|java.nio.charset.CharacterCodingException corrupt){System.err.println("[Verity Companion] Relationship backup invalid; using defaults");}
  T value=defaults.get();validate.accept(value);return value;
 }
 public static <T>void save(Path file,Gson gson,Class<T> type,T value,Consumer<T> validate)throws IOException {
  validate.accept(value);Files.createDirectories(file.getParent());
  Path backup=file.resolveSibling(file.getFileName()+".bak");
  if(Files.exists(file))try{read(file,gson,type,validate);Files.copy(file,backup,StandardCopyOption.REPLACE_EXISTING);}catch(RuntimeException|java.nio.charset.CharacterCodingException corrupt){/* Never replace a valid backup with corrupt JSON. */}
  Path temporary=Files.createTempFile(file.getParent(),file.getFileName().toString(),".tmp");
  try {
   byte[] bytes=gson.toJson(value).getBytes(StandardCharsets.UTF_8);
   try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true);}
   try{Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
  }finally{Files.deleteIfExists(temporary);}
 }
}
