package dev.johnmart19.veritycompanion;

import java.util.Locale;
import java.util.regex.Pattern;

/** Deliberately conservative: neutral conversation and uncertain cases never count as abuse. */
public final class MoodPolicy {
 public static final class State {
  public int mood=100,strikes; public long lastInsult; public boolean reconciliation,apologized;
 }
 public record Result(boolean insult,boolean apology,boolean changed) {}
 private static final Pattern INSULT=Pattern.compile("(?iu)(?<![\\p{L}])(идиот(?:ка|ом)?|дебил(?:ка|ом)?|тупой|тупая|урод|ублюдок|мудак|сука|пидор|idiot|moron|asshole|fuck you|stupid)(?![\\p{L}])");
 private static final Pattern APOLOGY=Pattern.compile("(?iu)(извини|прости|прошу прощения|sorry|apologiz|apologis)");
 public static Result apply(State s,String input,String alias,long now){
  String t=input.toLowerCase(Locale.ROOT).strip();
  boolean quoted=t.matches("(?s).*[\"«»“”].*")||t.matches("(?s).*(он сказал|она сказала|he said|she said|переведи|translate|что значит|what does|не называ|don't call|do not call).*" );
  boolean addressed=Pattern.compile("(?iu)(?<![\\p{L}])(ты|you|verity|верити|"+Pattern.quote(alias)+")(?![\\p{L}])").matcher(t).find();
  boolean directed=false;var words=INSULT.matcher(t);while(words.find()){
   String before=t.substring(0,words.start());
   if(before.matches("(?s).*(?<![\\p{L}])(?:ты|you|you are|you're|verity|верити|"+Pattern.quote(alias.toLowerCase(Locale.ROOT))+")[\\s,:!\\-]*(?:an?\\s+|полный\\s+|просто\\s+)?$")||words.group().equals("fuck you"))directed=true;
  }
  boolean insult=!quoted&&addressed&&directed;
  boolean apology=!insult&&!quoted&&APOLOGY.matcher(t).find();
  int before=s.mood;
  if(s.lastInsult!=0&&now-s.lastInsult>600000)s.strikes=0;
  if(insult){
   // First two clear insults are warnings. One penalty at most per 10 seconds.
   if(s.lastInsult==0||now-s.lastInsult>=10000){s.strikes++;s.lastInsult=now;if(s.strikes>2)s.mood=Math.max(0,s.mood-10);if(s.mood==0){s.reconciliation=true;s.apologized=false;}}
  }else if(apology){s.mood=Math.min(100,s.mood+30);s.strikes=0;s.apologized=true;if(s.mood>=60)s.reconciliation=false;}
  else if(s.reconciliation&&s.apologized&&t.matches("(?s).*(спасибо|хорошо провод|приятно|нравится с тобой|thank|enjoy|good time|appreciate).*")){s.mood=Math.min(100,s.mood+10);if(s.mood>=60)s.reconciliation=false;}
  else if(!s.reconciliation){s.mood=Math.min(100,s.mood+2);}
  return new Result(insult,apology,before!=s.mood);
 }
 public static boolean helps(State s){return s.mood>=40&&!s.reconciliation;}
 public static void defeated(State s){s.mood=25;s.reconciliation=true;s.apologized=false;s.strikes=0;}
}
