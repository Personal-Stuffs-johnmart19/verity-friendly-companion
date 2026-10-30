package dev.johnmart19.veritycompanion;
/** Bounded presentation state; independent of friendship, combat and horror logic. */
final class ExpressionPresentation {
 private String emotion="neutral";
 private long until;
 private boolean speaking;
 void preview(String value,long now){emotion=EmotionPolicy.normalize(value);speaking=false;until=now+12_000_000_000L;}
 void begin(String value,long now){emotion=EmotionPolicy.normalize(value);speaking=true;until=now+300_000_000_000L;}
 void end(long now){speaking=false;if(visible(now))until=now+5_000_000_000L;}
 void clear(){emotion="neutral";speaking=false;until=0;}
 boolean visible(long now){return until!=0&&now-until<0;}
 boolean speaking(long now){return speaking&&visible(now);}
 boolean acceptsPreview(boolean observed,long now){return !observed||!speaking(now);}
 String emotion(){return emotion;}
 int mouth(double openness,long now){
  if(!speaking||!visible(now)||!Double.isFinite(openness)||openness<=0.015)return 0;
  if(openness<0.23)return 1;
  if(openness<0.48)return 2;
  if(openness<0.73)return 3;
  return 4;
 }
}
