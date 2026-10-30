package dev.johnmart19.veritycompanion;

/** Ordered stream markers inside the authenticated voice payload; protocol version 2. */
public final class VoiceStreamState {
 public static final int START=-3,CONTINUE=-1,END=-2,MAX_BYTES=6_000_000;
 private boolean open;
 private int bytes,parts;
 public void clear(){open=false;bytes=0;parts=0;}
 public void accept(int marker,byte[] data){
  if(marker==START){clear();open=true;}
  else if(!open||(marker!=CONTINUE&&marker!=END))throw new IllegalArgumentException("Invalid voice sequence");
  if(marker==END){if(data.length!=1||data[0]!=0)throw new IllegalArgumentException("Invalid voice end");open=false;return;}
  if(data.length<44||data.length>AudioFrames.MAX_BYTES||data.length>MAX_BYTES-bytes||++parts>2048){clear();throw new IllegalArgumentException("Voice stream budget exceeded");}
  bytes+=data.length;
 }
}
