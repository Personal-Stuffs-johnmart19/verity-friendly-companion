package dev.johnmart19.veritycompanion;

import java.io.ByteArrayOutputStream;
import java.util.*;

/** Pure bounded protocol state; one ordered stream per peer, no identity supplied by audio bytes. */
public final class AudioFrames {
 public static final int CHUNK=16000,MAX_BYTES=2_000_000,MAX_CHUNKS=125;
 private final Map<UUID,Pending> pending=new HashMap<>();
 private final int peers;private final long timeout;
 private record Pending(UUID stream,int count,int entity,int epoch,long started,ByteArrayOutputStream bytes,int next){}
 public AudioFrames(int peers,long timeout){this.peers=peers;this.timeout=timeout;}
 public synchronized byte[] accept(UUID peer,UUID stream,int index,int count,int entity,int epoch,byte[] data,long now){
  expire(now);
  if(count<1||count>MAX_CHUNKS||index<0||index>=count||data.length<1||data.length>CHUNK)throw new IllegalArgumentException("Invalid audio frame bounds");
  Pending p=pending.get(peer);
  if(index==0){if(p!=null)throw new IllegalArgumentException("Stream already active");if(pending.size()>=peers)throw new IllegalArgumentException("Audio receive capacity reached");p=new Pending(stream,count,entity,epoch,now,new ByteArrayOutputStream(),0);pending.put(peer,p);}
  if(p==null||!p.stream.equals(stream)||p.count!=count||p.entity!=entity||p.epoch!=epoch||p.next!=index){pending.remove(peer);throw new IllegalArgumentException("Out-of-order or mismatched audio stream");}
  if(p.bytes.size()+data.length>MAX_BYTES){pending.remove(peer);throw new IllegalArgumentException("Audio too large");}
  p.bytes.writeBytes(data);
  if(index+1==count){pending.remove(peer);return p.bytes.toByteArray();}
  pending.put(peer,new Pending(stream,count,entity,epoch,p.started,p.bytes,index+1));return null;
 }
 public synchronized void remove(UUID peer){pending.remove(peer);}
 public synchronized void clear(){pending.clear();}
 public synchronized int size(){return pending.size();}
 private void expire(long now){pending.values().removeIf(p->now-p.started>timeout);}
}
