package dev.johnmart19.veritycompanion;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

/** All world reads stay on the server thread, with one shared per-tick budget. */
final class BlockScan {
 static final int CHECKS_PER_TICK=4096,MAX_PENDING=16;
 static final ArrayDeque<Scan> PENDING=new ArrayDeque<>();
 static CompletableFuture<String> start(ServerLevel level,BlockPos center,String query,int radius){
  if(query.isBlank())throw new IllegalArgumentException("Specify a block ID or tag to search");
  if(PENDING.size()>=MAX_PENDING)throw new IllegalStateException("Block search queue is busy; try again shortly");
  Scan scan=new Scan(level,center,query,Math.min(48,Math.max(1,radius)));PENDING.add(scan);return scan.done;
 }
 static void clear(){while(!PENDING.isEmpty())PENDING.remove().done.completeExceptionally(new IllegalStateException("Server stopped; search cancelled"));}
 static void tick(net.minecraft.server.MinecraftServer server){ScanWorkBudget.run(CHECKS_PER_TICK,2_000_000L,()->{Scan scan=PENDING.peek();
   if(scan.done.isDone()){PENDING.remove();return;}
   if(scan.level.getServer()!=server){scan.done.completeExceptionally(new IllegalStateException("Search belongs to a stopped server"));PENDING.remove();return;}
   if(System.nanoTime()-scan.created>25_000_000_000L){scan.done.complete(scan.out+"Search time budget reached; narrow the radius. Checked="+scan.checked);PENDING.remove();return;}
   try{scan.step();if(scan.finished){scan.done.complete(scan.out+"Searched loaded blocks only; skipped unloaded columns: "+scan.unloaded+"; checked="+scan.checked);PENDING.remove();}}
   catch(Exception error){scan.done.completeExceptionally(error);PENDING.remove();}
  },()->!PENDING.isEmpty(),System::nanoTime);
 }
 static final class Scan {
  final ServerLevel level;final BlockPos center;final String query;final int radius,minY,maxY;final long created=System.nanoTime();
  final CompletableFuture<String> done=new CompletableFuture<>();final StringBuilder out=new StringBuilder();int x,z,y,count,unloaded,checked;boolean finished;
  Scan(ServerLevel l,BlockPos c,String q,int r){level=l;center=c.immutable();query=q;radius=r;x=-r;z=-r;minY=Math.max(l.getMinBuildHeight(),c.getY()-r);maxY=Math.min(l.getMaxBuildHeight(),c.getY()+r);y=minY;}
  void nextColumn(){y=minY;if(++z>radius){z=-radius;if(++x>radius)finished=true;}}
  void step(){BlockPos pos=new BlockPos(center.getX()+x,y,center.getZ()+z);
   if(y>=maxY||!level.hasChunkAt(pos)){unloaded++;nextColumn();return;}
   var state=level.getBlockState(pos);checked++;
   if(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().contains(query)||state.getTags().anyMatch(tag->tag.location().toString().contains(query))){out.append(BuiltInRegistries.BLOCK.getKey(state.getBlock())).append(" at ").append(pos).append('\n');if(++count>=40){out.append("Result list truncated; refine query.\n");finished=true;return;}}
   if(++y>=maxY)nextColumn();
  }
 }
}
