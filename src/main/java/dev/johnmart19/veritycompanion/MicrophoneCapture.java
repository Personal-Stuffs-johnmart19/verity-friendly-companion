package dev.johnmart19.veritycompanion;

/** Owns a recorder and binds its pending upload to one connection and recording. */
final class MicrophoneCapture {
 record Ticket(Object connection,int epoch,long generation) {}
 private Object recorder,connection;
 private int epoch;
 private long generation;
 synchronized void begin(Object source,Object owner,int voiceEpoch){
  cancel();recorder=source;connection=owner;epoch=voiceEpoch;
 }
 synchronized Ticket take(){
  if(recorder==null)return null;
  recorder=null;return new Ticket(connection,epoch,generation);
 }
 synchronized boolean current(Ticket ticket,Object owner,int voiceEpoch){
  return ticket!=null&&owner!=null&&ticket.connection()==owner&&ticket.epoch()==voiceEpoch&&ticket.generation()==generation;
 }
 synchronized void cancel(){
  Object source=recorder;recorder=null;connection=null;generation++;
  if(source!=null)try{source.getClass().getMethod("stopRecording").invoke(source);}
  catch(ReflectiveOperationException|RuntimeException ignored){System.err.println("[Verity Companion] microphone cleanup failed");}
 }
}
