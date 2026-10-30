package dev.johnmart19.veritycompanion;
import java.io.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicInteger;import javax.sound.sampled.*;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client transport and playback only: no HTTP, configuration files, credentials, STT or TTS models. */
public final class ClientVoice {
 private static volatile boolean ttsEnabled=true;
 static int currentEpoch(){return EPOCH.get();}
 public static void setTtsEnabled(boolean enabled){ttsEnabled=enabled;if(!enabled){AUDIO.clear();STREAM.clear();stop();}}
 private static final UUID PEER=new UUID(0,0);
 private static final AudioFrames AUDIO=new AudioFrames(1,30000);
 private static final AtomicInteger EPOCH=new AtomicInteger(),PLAYBACK=new AtomicInteger();
 private static final ThreadPoolExecutor SOUND=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),r->{Thread t=new Thread(r,"Verity received voice");t.setDaemon(true);return t;});
 private static final VoiceStreamState STREAM=new VoiceStreamState();
 private static final Object PLAYBACK_LOCK=new Object();
 private record Playback(SourceDataLine line,SpeechEnvelope envelope,int generation){}
 private static volatile Playback active;
 private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"Verity microphone deadline");t.setDaemon(true);return t;});
 private static volatile net.minecraft.network.Connection connection;
 private static final MicrophoneCapture CAPTURE=new MicrophoneCapture();
 private static final AtomicInteger RECORDING=new AtomicInteger();
 private static volatile ScheduledFuture<?> recordingDeadline;
 private static void cancelRecordingDeadline(){RECORDING.incrementAndGet();ScheduledFuture<?> task=recordingDeadline;recordingDeadline=null;if(task!=null)task.cancel(false);}
 public static void registerEvents(){ClientFaceTextures.register();ClientMicrophoneHud.register();net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event)->disconnect());}
 private static void disconnect(){cancelRecordingDeadline();CAPTURE.cancel();ClientMicrophoneHud.clear();connection=null;EPOCH.incrementAndGet();ClientExpression.clear();AUDIO.clear();STREAM.clear();stop();}
 static void checkConnection(){var c=Minecraft.getInstance().getConnection();var next=c==null?null:c.getConnection();if(next!=connection){cancelRecordingDeadline();CAPTURE.cancel();ClientMicrophoneHud.clear();connection=next;EPOCH.set(0);ClientExpression.clear();AUDIO.clear();STREAM.clear();stop();}}
 public static void interrupt(){ClientExpression.interrupt();Minecraft mc=Minecraft.getInstance();mc.execute(()->{checkConnection();stop();AUDIO.clear();STREAM.clear();int epoch=EPOCH.incrementAndGet();if(mc.getConnection()!=null)PacketDistributor.sendToServer(new VoiceControl(epoch));});}
 public static void beginRecording(Object recorder){interrupt();Minecraft.getInstance().execute(()->{cancelRecordingDeadline();CAPTURE.begin(recorder,connection,EPOCH.get());ClientMicrophoneHud.begin(recorder);int ticket=RECORDING.get();int epoch=EPOCH.get();recordingDeadline=TIMER.schedule(()->Minecraft.getInstance().execute(()->{if(ticket!=RECORDING.get()||epoch!=EPOCH.get())return;try{ClientMicrophoneHud.deadline();byte[] pcm=(byte[])recorder.getClass().getMethod("stopRecording").invoke(recorder);AudioFormat format=(AudioFormat)recorder.getClass().getMethod("getAudioFormat").invoke(recorder);upload(pcm,format);}catch(Exception e){ClientMicrophoneHud.failed();System.err.println("[Verity Companion] microphone deadline stopped");}}),29,TimeUnit.SECONDS);});}
 /** Render-safe: the device cursor counts consumed frames, never queued writes. */
 public static double mouthOpen(){
  Playback p=active;if(p==null||p.generation()!=PLAYBACK.get())return 0;
  try{if(!p.line().isOpen()||!p.line().isRunning())return 0;double value=p.envelope().at(p.line().getLongFramePosition());return active==p&&p.generation()==PLAYBACK.get()?value:0;}catch(RuntimeException e){return 0;}
 }
 private static void closeLine(SourceDataLine line){try{line.stop();}catch(RuntimeException ignored){}try{line.flush();}catch(RuntimeException ignored){}try{line.close();}catch(RuntimeException ignored){}}
 private static void stop(){Playback p;synchronized(PLAYBACK_LOCK){ClientExpression.end();PLAYBACK.incrementAndGet();SOUND.getQueue().clear();p=active;active=null;}if(p!=null)closeLine(p.line());}
 public static String upload(byte[] pcm,AudioFormat format){var capture=CAPTURE.take();cancelRecordingDeadline();if(pcm.length==0){ClientMicrophoneHud.empty();return "";}if(capture==null)return "";ClientMicrophoneHud.sending();try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(AudioInputStream in=new AudioInputStream(new ByteArrayInputStream(pcm),format,pcm.length/format.getFrameSize())){AudioSystem.write(in,AudioFileFormat.Type.WAVE,bytes);}byte[] wav=bytes.toByteArray();if(wav.length>AudioFrames.MAX_BYTES||pcm.length/format.getFrameSize()/format.getSampleRate()>30)throw new IllegalArgumentException("Hold R for at most 30 seconds");Minecraft.getInstance().execute(()->{try{checkConnection();if(!CAPTURE.current(capture,connection,EPOCH.get()))return;if(Minecraft.getInstance().getConnection()!=null){for(AudioPacket p:AudioPacket.split(false,wav,0,capture.epoch()))PacketDistributor.sendToServer(p);ClientMicrophoneHud.sent();}else ClientMicrophoneHud.failed();}catch(RuntimeException e){ClientMicrophoneHud.failed();System.err.println("[Verity Companion] microphone send failed");}});}catch(Exception e){ClientMicrophoneHud.failed();System.err.println("[Verity Companion] microphone upload failed: "+e.getMessage());}return "";}
 public static void receive(AudioPacket p,IPayloadContext context){checkConnection();if(!ttsEnabled||!p.voice()||p.epoch()!=EPOCH.get())return;try{byte[] wav=AUDIO.accept(PEER,p.stream(),p.index(),p.count(),p.entity(),p.epoch(),p.data(),System.currentTimeMillis());if(wav!=null){if(p.entity()>=0){stop();STREAM.clear();int generation=PLAYBACK.get();SOUND.execute(()->play(wav,generation,p.epoch()));return;}if(p.entity()==VoiceStreamState.START)stop();STREAM.accept(p.entity(),wav);int generation=PLAYBACK.get();SOUND.execute(()->playChunk(wav,generation,p.epoch(),p.entity()==VoiceStreamState.END));}}catch(Exception e){AUDIO.clear();STREAM.clear();stop();System.err.println("[Verity Companion] invalid voice stream");}}
 private static Playback open(AudioFormat format,int generation,int epoch,int maxFrames)throws Exception{
  SpeechEnvelope.validate(format);
  SourceDataLine line=AudioSystem.getSourceDataLine(format);
  try{
   line.open(format);
   Playback p=new Playback(line,new SpeechEnvelope((int)format.getSampleRate(),maxFrames,format.isBigEndian()),generation);
   synchronized(PLAYBACK_LOCK){if(generation!=PLAYBACK.get()){closeLine(line);return null;}active=p;line.start();ClientExpression.begin(epoch);}
   return p;
  }catch(Exception e){synchronized(PLAYBACK_LOCK){if(active!=null&&active.line()==line){active=null;if(generation==PLAYBACK.get())ClientExpression.end();}}closeLine(line);throw e;}
 }
 private static void retire(Playback p){
  if(p==null)return;
  synchronized(PLAYBACK_LOCK){if(active==p){active=null;if(p.generation()==PLAYBACK.get())ClientExpression.end();}}
  closeLine(p.line());
 }
 private static void write(AudioInputStream in,Playback p,int generation)throws IOException{
  byte[] b=new byte[4096];
  for(int n;(n=in.read(b))!=-1&&generation==PLAYBACK.get();){
   p.envelope().append(b,0,n);
   for(int offset=0;offset<n&&generation==PLAYBACK.get();){int written=p.line().write(b,offset,n-offset);if(written<=0)throw new IOException("Audio output stopped");offset+=written;}
  }
 }
 private static void playChunk(byte[] wav,int generation,int epoch,boolean end){Playback owned=null;try{
  if(generation!=PLAYBACK.get())return;
  owned=active;
  if(end){owned=active;if(owned!=null&&owned.generation()==generation){owned.line().drain();retire(owned);}return;}
  try(AudioInputStream in=AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))){
   AudioFormat f=in.getFormat();SpeechEnvelope.validate(f);owned=active;
   if(owned==null){owned=open(f,generation,epoch,VoiceStreamState.MAX_BYTES/2);if(owned==null)return;}
   else if(owned.generation()!=generation)return;
   else if(!owned.line().getFormat().matches(f))throw new IllegalArgumentException("Voice format changed mid-stream");
   write(in,owned,generation);
  }
 }catch(Exception e){retire(owned);System.err.println("[Verity Companion] stream playback stopped: "+e.getClass().getSimpleName());}}
 private static void play(byte[] wav,int generation,int epoch){Playback owned=null;
  try(AudioInputStream in=AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))){
   if(generation!=PLAYBACK.get())return;
   owned=open(in.getFormat(),generation,epoch,AudioFrames.MAX_BYTES/2);if(owned==null)return;
   write(in,owned,generation);if(generation==PLAYBACK.get())owned.line().drain();
  }catch(Exception e){System.err.println("[Verity Companion] playback stopped: "+e.getClass().getSimpleName());}
  finally{retire(owned);}
 }
}
