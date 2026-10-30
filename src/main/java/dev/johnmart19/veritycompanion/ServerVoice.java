package dev.johnmart19.veritycompanion;
import java.io.*;import javax.sound.sampled.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicBoolean;
import com.google.gson.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ServerVoice {
 private static final AudioFrames AUDIO=new AudioFrames(24,30000);
 private static final ConcurrentHashMap<UUID,State> STATES=new ConcurrentHashMap<>();
 private static final ThreadPoolExecutor WORK=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"Verity server assistant");t.setDaemon(true);return t;});
 private static class State {final AtomicBoolean busy=new AtomicBoolean();volatile int epoch;volatile long lastRequest;volatile boolean cancelled;}
 private static boolean current(ServerPlayer p,State s,int epoch){return !s.cancelled&&STATES.get(p.getUUID())==s&&s.epoch==epoch&&!p.hasDisconnected();}
 public static void interrupt(VoiceControl packet,IPayloadContext context){if(context.player() instanceof ServerPlayer p){State s=STATES.computeIfAbsent(p.getUUID(),k->new State());s.epoch=packet.epoch();AUDIO.remove(p.getUUID());}}
 public static void receive(AudioPacket packet,IPayloadContext context){if(!(context.player() instanceof ServerPlayer p)||packet.voice())return;try{State s=STATES.computeIfAbsent(p.getUUID(),k->new State());if(packet.epoch()!=s.epoch)throw new IllegalArgumentException("Stale microphone stream");byte[] wav=AUDIO.accept(p.getUUID(),packet.stream(),packet.index(),packet.count(),packet.entity(),packet.epoch(),packet.data(),System.currentTimeMillis());if(wav!=null){validateWav(wav);request(p,null,wav);}}catch(Exception e){AUDIO.remove(p.getUUID());tell(p,Relations.tr(p,"Голосовой пакет отклонён: неверный формат или размер.","Voice packet rejected: invalid format or size."));}}
 static void validateWav(byte[] bytes)throws Exception {try(AudioInputStream in=AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes))){AudioFormat f=in.getFormat();if(!AudioFormat.Encoding.PCM_SIGNED.equals(f.getEncoding())||f.getSampleSizeInBits()!=16||f.getChannels()!=1||f.getSampleRate()<8000||f.getSampleRate()>48000||in.getFrameLength()<1||in.getFrameLength()/f.getSampleRate()>30)throw new IllegalArgumentException("Expected up to 30s mono PCM16 WAV");}}
 private static void tell(ServerPlayer p,String text){p.getServer().execute(()->{if(!p.hasDisconnected()){Component message=Component.literal("<"+Relations.name(p)+" → "+p.getGameProfile().getName()+"> "+text).withStyle(ChatFormatting.AQUA);if(CompanionBrain.settings.publicChat)p.getServer().getPlayerList().broadcastSystemMessage(message,false);else p.sendSystemMessage(message);}});}
 private static void publish(ServerPlayer p,String text){p.getServer().execute(()->{Component chat=Component.literal("<"+p.getGameProfile().getName()+"> "+text);if(CompanionBrain.settings.publicChat)p.getServer().getPlayerList().broadcastSystemMessage(chat,false);else if(!p.hasDisconnected())p.sendSystemMessage(chat);});}
 public static void request(ServerPlayer p,String text,byte[] wav){CompanionBrain.init();if(text!=null)publish(p,text);State s=STATES.computeIfAbsent(p.getUUID(),k->new State());long now=System.currentTimeMillis();if(now-s.lastRequest<750||!s.busy.compareAndSet(false,true)){tell(p,Relations.tr(p,"Ещё заканчиваю предыдущий запрос. Повтори через мгновение.","Still finishing your previous request. Try again shortly."));return;}s.lastRequest=now;int epoch=s.epoch;
  try{WORK.execute(()->{try{if(!current(p,s,epoch))return;if(text==null)p.getServer().execute(()->{if(current(p,s,epoch))p.displayClientMessage(Component.literal(Relations.tr(p,"Распознаю голос… Первый запрос может занять дольше: загружается модель.","Recognizing speech… The first request can take longer while the model loads.")),true);});String input=text==null?ServerBridge.transcribe(wav):text;if(!current(p,s,epoch))return;if(input.isBlank()){if(text==null)tell(p,Relations.tr(p,"Не удалось разобрать речь. Проверь уровень микрофона и повтори фразу.","Could not recognize speech. Check the microphone level and try again."));return;}if(input.length()>2000)input=input.substring(0,2000);String prompt=input;String published=input;
   String warning=CompanionBrain.main(p.getServer(),()->{if(!current(p,s,epoch))throw new CancellationException();String result=Relations.receive(p,published);if(text==null)publish(p,published);return result;});if(!warning.isEmpty()&&current(p,s,epoch))tell(p,warning);
   if(!current(p,s,epoch))return;
   // The authenticated player is bound directly; identical messages cannot exchange owners.
   String result=CompanionBrain.ask(p,prompt);JsonObject response=JsonParser.parseString(result).getAsJsonObject();String reply=response.get("message").getAsString();String emotion=EmotionPolicy.normalize(response.has("emotion")?response.get("emotion").getAsString():"neutral");if(!current(p,s,epoch))return;tell(p,reply);showExpression(p,s,epoch,emotion,true);
   if(!Relations.get(p).tts)return;
   AtomicBoolean firstVoice=new AtomicBoolean(true);
   try{ServerBridge.speechStream(reply,emotion,Relations.get(p).ttsVoice,voice->{if(!current(p,s,epoch)||!Relations.get(p).tts)throw new CancellationException();int marker=firstVoice.getAndSet(false)?VoiceStreamState.START:VoiceStreamState.CONTINUE;if(marker==VoiceStreamState.START)showExpression(p,s,epoch,emotion,false);sendVoice(p,s,epoch,marker,voice);});}
   catch(CancellationException e){}catch(Exception e){if(current(p,s,epoch))tell(p,Relations.tr(p,"Озвучка сейчас недоступна. Текст ответа выше.","Voice is currently unavailable. The text reply is above."));System.err.println("[Verity Companion] server TTS unavailable: "+e.getClass().getSimpleName());}
   finally{if(!firstVoice.get())sendVoice(p,s,epoch,VoiceStreamState.END,new byte[]{0});}
  }catch(CancellationException e){}catch(Exception e){if(current(p,s,epoch))tell(p,Relations.tr(p,"Не удалось обработать запрос на сервере. Проверь голосовой сервис и квоту Gemini.","The server could not process the request. Check the voice service and Gemini quota."));System.err.println("[Verity Companion] server request failed: "+e.getClass().getSimpleName());}finally{s.busy.set(false);}});}catch(RejectedExecutionException e){s.busy.set(false);tell(p,Relations.tr(p,"Сервер обрабатывает другие запросы. Повтори чуть позже.","The server is processing other requests. Try again shortly."));}
 }
 /** Presentation only; model output never changes relationship or horror state. */
 private static void showExpression(ServerPlayer p,State state,int epoch,String emotion,boolean textOnly){
  p.getServer().execute(()->{
   if(!current(p,state,epoch))return;
   var body=CompanionPresence.select(p);
   if(body==null||body.level()!=p.level()||!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(body.getType()).toString().equals("verity:verity"))return;
   var packet=new VoiceExpression(body.getId(),textOnly?VoiceExpression.TEXT_REPLY:epoch,emotion,epoch);
   if(textOnly&&CompanionBrain.settings.publicChat){
    for(ServerPlayer viewer:p.getServer().getPlayerList().getPlayers())
     if(viewer.level()==body.level()&&viewer.distanceToSqr(body)<=128*128)PacketDistributor.sendToPlayer(viewer,viewer==p?packet:new VoiceExpression(body.getId(),VoiceExpression.OBSERVED_REPLY,emotion,epoch));
   }else PacketDistributor.sendToPlayer(p,packet);
  });
 }
 private static void sendVoice(ServerPlayer p,State s,int epoch,int marker,byte[] voice){p.getServer().execute(()->{if(current(p,s,epoch))for(AudioPacket packet:AudioPacket.split(true,voice,marker,epoch))PacketDistributor.sendToPlayer(p,packet);});}
 public static void disconnect(UUID id){State s=STATES.remove(id);if(s!=null)s.cancelled=true;AUDIO.remove(id);}
 public static void clear(){STATES.values().forEach(s->s.cancelled=true);STATES.clear();AUDIO.clear();WORK.getQueue().clear();}
}
