package dev.johnmart19.veritycompanion;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** Observes the original recorder; never opens a microphone or retains captured audio. */
public final class ClientMicrophoneHud {
 private enum State { HIDDEN, STARTING, RECORDING, SENDING, SENT, ERROR }
 private static State state=State.HIDDEN;
 private static Object recorder;
 private static Field recordingField,streamField;
 private static long started,changed,lastSample;
 private static int lastBytes;
 private static double level;
 private static boolean automaticStop;
 private ClientMicrophoneHud(){}
 public static void register(){net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClientMicrophoneHud::render);}
 static synchronized void begin(Object source){
  recorder=source;recordingField=null;streamField=null;started=changed=System.nanoTime();lastSample=0;lastBytes=0;level=0;automaticStop=false;state=State.STARTING;
  try{recordingField=source.getClass().getDeclaredField("isRecording");streamField=source.getClass().getDeclaredField("audioStream");recordingField.setAccessible(true);streamField.setAccessible(true);}catch(ReflectiveOperationException|RuntimeException ignored){/* A future recorder may not expose metering. */}
 }
 static synchronized void clear(){state=State.HIDDEN;recorder=null;recordingField=null;streamField=null;level=0;}
 static synchronized void deadline(){automaticStop=true;}
 static synchronized void sending(){state=State.SENDING;changed=System.nanoTime();recorder=null;}
 static synchronized void empty(){if(!automaticStop)failed();}
 static synchronized void sent(){state=State.SENT;changed=System.nanoTime();}
 static synchronized void failed(){state=State.ERROR;changed=System.nanoTime();recorder=null;level=0;}
 private static void sample(long now){
  if(recorder==null||recordingField==null)return;
  try{
   boolean active=recordingField.getBoolean(recorder);
   if(active){state=State.RECORDING;
    if(now-lastSample<50_000_000L)return;
    lastSample=now;Object value=streamField.get(recorder);
    if(value instanceof ByteArrayOutputStream stream){
     // Original 3.4.1 uses signed 16-bit little-endian mono at 16 kHz.
     // Hold the same monitor as its synchronized write; inspect only the latest 100 ms.
     synchronized(stream){
      int count=stream.size();
      if(count==lastBytes){level*=0.8;return;}lastBytes=count;
      stream.writeTo(new OutputStream(){
       @Override public void write(int value){}
       @Override public void write(byte[] data,int offset,int length){
        int end=offset+(length&~1),begin=Math.max(offset,end-3200);double squares=0;int samples=0;
        for(int i=begin;i+1<end;i+=2){int sample=(short)((data[i]&255)|(data[i+1]<<8));double normalized=sample/32768.0;squares+=normalized*normalized;samples++;}
        double rms=samples==0?0:Math.sqrt(squares/samples);
        // -48 dBFS to 0 dBFS, so normal speech remains visible without clipping.
        level=rms<=0?0:Math.max(0,Math.min(1,(20*Math.log10(rms)+48)/48));
       }
      });
     }
    }
   }else if(state==State.STARTING&&now-started>2_000_000_000L)failed();
   else if(state==State.RECORDING){state=State.SENDING;changed=now;recorder=null;}
  }catch(ReflectiveOperationException|java.io.IOException|RuntimeException ignored){recordingField=null;streamField=null;level=0;}
 }
 private static synchronized void render(RenderGuiEvent.Post event){
  Minecraft mc=Minecraft.getInstance();if(mc.player==null||mc.getConnection()==null){clear();return;}
  long now=System.nanoTime();if(state==State.STARTING||state==State.RECORDING)sample(now);
  if((state==State.SENT&&now-changed>2_000_000_000L)||(state==State.ERROR&&now-changed>4_000_000_000L)){clear();return;}
  if(state==State.SENDING&&now-changed>10_000_000_000L)failed();
  if(state==State.STARTING&&now-started>3_000_000_000L)failed();
  if(state==State.HIDDEN||mc.options.hideGui)return;
  boolean ru=mc.options.languageCode.startsWith("ru");
  String title=switch(state){
   case STARTING->ru?"Микрофон: подключение…":"Microphone: starting…";
   case RECORDING->(ru?"Запись ":"Recording ")+String.format(java.util.Locale.ROOT,"%.1f / 29 s",(now-started)/1_000_000_000.0);
   case SENDING->ru?"Голос: отправка…":"Voice: sending…";
   case SENT->automaticStop?(ru?"Лимит записи · отправлено":"Recording limit · sent"):(ru?"Голос отправлен":"Voice sent");
   case ERROR->ru?"Микрофон: запись не отправлена":"Microphone: recording not sent";
   default->"";
  };
  GuiGraphics g=event.getGuiGraphics();int width=Math.max(190,mc.font.width(title)+40),x=(g.guiWidth()-width)/2,y=g.guiHeight()-76;
  int color=state==State.ERROR?0xFFFF7777:state==State.RECORDING?0xFFFF5555:0xFF77DDCC;
  g.fill(x,y,x+width,y+48,0xD0181D27);g.fill(x,y,x+3,y+48,color);
  String device=ClientMicrophoneDevices.label();if(device.length()>38)device=device.substring(0,35)+"…";
  g.drawString(mc.font,device,x+12,y+35,0xFFBBC8DD);
  // Pixel microphone glyph avoids a font-dependent Unicode icon.
  g.fill(x+12,y+7,x+17,y+17,color);g.fill(x+9,y+13,x+11,y+20,color);g.fill(x+18,y+13,x+20,y+20,color);g.fill(x+11,y+20,x+18,y+22,color);g.fill(x+14,y+22,x+16,y+25,color);
  g.drawString(mc.font,title,x+28,y+7,0xFFFFFFFF);
  if(state==State.RECORDING){
   int meterX=x+28,meterWidth=width-40;g.fill(meterX,y+23,meterX+meterWidth,y+28,0xFF3B4352);
   g.fill(meterX,y+23,meterX+(int)(meterWidth*level),y+28,level>0.92?0xFFFFCC66:0xFF66DDAA);
  }
 }
}
