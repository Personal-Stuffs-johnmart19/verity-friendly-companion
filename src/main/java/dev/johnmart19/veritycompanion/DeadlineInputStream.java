package dev.johnmart19.veritycompanion;

import java.io.*;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;

/** A wall-clock body deadline; HTTP header timeouts do not cover streaming body reads. */
public final class DeadlineInputStream extends FilterInputStream {
 private static final ScheduledThreadPoolExecutor TIMER=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"Verity stream deadline");t.setDaemon(true);return t;});
 static {TIMER.setRemoveOnCancelPolicy(true);}
 private final ScheduledFuture<?> deadline;
 private volatile boolean expired;
 public DeadlineInputStream(InputStream in,long timeoutMillis){super(in);if(timeoutMillis<1)throw new IllegalArgumentException("Positive stream deadline required");deadline=TIMER.schedule(()->{expired=true;try{in.close();}catch(IOException ignored){}},timeoutMillis,TimeUnit.MILLISECONDS);}
 private void check()throws SocketTimeoutException {if(expired)throw new SocketTimeoutException("Voice stream deadline exceeded");}
 @Override public int read()throws IOException {check();int result;try{result=in.read();}catch(IOException e){check();throw e;}check();return result;}
 @Override public int read(byte[] bytes,int offset,int length)throws IOException {check();int result;try{result=in.read(bytes,offset,length);}catch(IOException e){check();throw e;}check();return result;}
 @Override public void close()throws IOException {deadline.cancel(false);in.close();}
}
