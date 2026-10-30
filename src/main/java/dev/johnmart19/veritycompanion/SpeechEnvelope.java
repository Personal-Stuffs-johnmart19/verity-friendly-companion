package dev.johnmart19.veritycompanion;

import javax.sound.sampled.AudioFormat;

/** Bounded PCM16 speech energy indexed by played sample frames, independent of render rate. */
public final class SpeechEnvelope {
 private final int window,maxFrames;
 private final boolean bigEndian;
 private final float[] levels;
 private int frames,windowFrames;
 private double squares,previous;
 public SpeechEnvelope(int sampleRate,int maxFrames,boolean bigEndian){
  if(sampleRate<8000||sampleRate>48000||maxFrames<1||maxFrames>VoiceStreamState.MAX_BYTES/2)throw new IllegalArgumentException("Invalid speech envelope bounds");
  this.window=Math.max(1,sampleRate/100);this.maxFrames=maxFrames;this.bigEndian=bigEndian;
  levels=new float[(maxFrames+window-1)/window];
 }
 public static void validate(AudioFormat f){
  float rate=f.getSampleRate();
  if(!AudioFormat.Encoding.PCM_SIGNED.equals(f.getEncoding())||f.getChannels()!=1||f.getSampleSizeInBits()!=16||f.getFrameSize()!=2||!Float.isFinite(rate)||rate<8000||rate>48000||rate!=(int)rate||f.getFrameRate()!=rate)throw new IllegalArgumentException("Expected PCM16 mono at 8-48 kHz");
 }
 /** Appending before queueing keeps the currently audible window available to the renderer. */
 public synchronized void append(byte[] pcm,int offset,int length){
  if(offset<0||length<0||offset>pcm.length-length||(length&1)!=0||length/2>maxFrames-frames)throw new IllegalArgumentException("Invalid or excessive PCM frames");
  for(int i=offset;i<offset+length;i+=2){
   int lo=pcm[i+(bigEndian?1:0)]&255,hi=pcm[i+(bigEndian?0:1)];
   double sample=(short)((hi<<8)|lo)/32768.0;squares+=sample*sample;windowFrames++;frames++;
   if(windowFrames==window){previous=level();levels[(frames-1)/window]=(float)previous;windowFrames=0;squares=0;}
  }
  if(windowFrames>0)levels[(frames-1)/window]=(float)level();
 }
 private double level(){
  double rms=Math.sqrt(squares/windowFrames);
  if(rms<=0.008)return 0; // Noise/silence must never hold a talking mouth open.
  double target=Math.min(1,Math.sqrt((rms-0.008)/0.22));
  return previous+(target-previous)*(target>=previous?0.85:0.35);
 }
 public synchronized double at(long playedFrames){
  if(playedFrames<0||playedFrames>=frames)return 0;
  return levels[(int)(playedFrames/window)];
 }
 public synchronized int frames(){return frames;}
}
