package dev.johnmart19.veritycompanion;

import java.nio.file.Path;
import java.util.*;
import javax.sound.sampled.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Device enumeration never opens an input. Empty preference means the OS default. */
public final class ClientMicrophoneDevices {
 public static final class Preference {public String device="";}
 private static final Path PATH=Path.of("config/verity-companion-microphone.json");
 private static final List<Mixer.Info> DEVICES=new ArrayList<>();
 private static String selected="";private static boolean loaded;
 private ClientMicrophoneDevices(){}
 static String identity(Mixer.Info info){return info.getName()+" | "+info.getVendor()+" | "+info.getDescription()+" | "+info.getVersion();}
 public static synchronized void scan(AudioFormat format){
  if(!loaded){loaded=true;try{selected=SafeJsonStore.load(PATH,new com.google.gson.Gson(),Preference.class,Preference::new,p->{if(p==null||p.device==null||p.device.length()>2048)throw new IllegalArgumentException("Invalid microphone preference");}).device;}catch(Exception failure){selected="";System.err.println("[Verity Companion] microphone preference unavailable; using OS default");}}
  DEVICES.clear();var line=new DataLine.Info(TargetDataLine.class,format);
  for(var info:AudioSystem.getMixerInfo())try{if(AudioSystem.getMixer(info).isLineSupported(line))DEVICES.add(info);}catch(RuntimeException unavailable){/* Disconnected devices need not prevent other inputs. */}
 }
 public static synchronized Mixer.Info selected(){
  return resolve(DEVICES,selected);
 }
 static Mixer.Info resolve(List<Mixer.Info> devices,String selection){return selection.isEmpty()?null:devices.stream().filter(info->identity(info).equals(selection)).findFirst().orElse(null);}
 public static synchronized String label(){var info=selected();return info==null?(selected.isEmpty()?"OS default":"Saved input unavailable; OS default"):info.getName();}
 public static synchronized void cycle(){
  int index=-1;for(int i=0;i<DEVICES.size();i++)if(identity(DEVICES.get(i)).equals(selected)){index=i;break;}
  selected=index+1<DEVICES.size()?identity(DEVICES.get(index+1)):"";
  try{var preference=new Preference();preference.device=selected;SafeJsonStore.save(PATH,new com.google.gson.Gson(),Preference.class,preference,p->{if(p.device==null||p.device.length()>2048)throw new IllegalArgumentException("Invalid microphone preference");});}catch(Exception failure){System.err.println("[Verity Companion] microphone selection could not be saved");}
  var mc=Minecraft.getInstance();if(mc.player!=null)mc.player.displayClientMessage(Component.literal((mc.options.languageCode.startsWith("ru")?"Микрофон: ":"Microphone: ")+label()),false);
 }
}
