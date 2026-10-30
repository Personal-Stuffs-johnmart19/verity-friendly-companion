package dev.johnmart19.veritycompanion;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
/** Shared count and time bound, independently testable without a running world. */
final class ScanWorkBudget {
 static int run(int maximum,long nanos,Runnable step,BooleanSupplier pending,LongSupplier clock){
  long start=clock.getAsLong();int count=0;
  while(count<maximum&&pending.getAsBoolean()&&clock.getAsLong()-start<nanos){step.run();count++;}
  return count;
 }
}
