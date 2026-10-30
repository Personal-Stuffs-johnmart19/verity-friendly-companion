package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Authored, bounded human-request grammar. Never pass model output, tool arguments or web text here. */
final class HumanIntentLexicon {
 private record Rule(String source,String target,Pattern pattern) {}
 private static final List<Rule> WORDS=new ArrayList<>(),BUILD_WORDS=new ArrayList<>(),PREFIXES=new ArrayList<>(),SUFFIXES=new ArrayList<>();
 private static final Set<String> AFFIRMATIONS=new HashSet<>();
 private static final List<Pattern> BLOCKERS=new ArrayList<>();
 private static final Map<String,String> WORD_TARGETS=new HashMap<>();
 private static final Pattern WORD_PATTERN;
 private static final Set<String> ACTIONS=Set.of("give","apply","build","follow me","stop following","undo build","enchant","remove","make it prettier");
 static {
  // A missing or malformed authored resource fails closed for added languages.
  try(InputStream in=HumanIntentLexicon.class.getResourceAsStream("/assets/verity_companion/intents/human.json")){
   if(in!=null){
    JsonObject languages=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
    for(var language:languages.entrySet())for(var entry:language.getValue().getAsJsonObject().entrySet())for(JsonElement item:entry.getValue().getAsJsonArray()){
     String source=base(item.getAsString()),target=entry.getKey();
     if(target.equals("yes")){AFFIRMATIONS.add(source);continue;}
     if(target.equals("negative")||target.equals("information")){BLOCKERS.add(phrase(source));continue;}
     if(language.getKey().equals("en")||language.getKey().equals("ru"))continue;
     Rule rule=new Rule(source,target,phrase(source));
     if(target.equals("from")||target.equals("here")){BUILD_WORDS.add(rule);continue;}
     if(ACTIONS.contains(target)){
      PREFIXES.add(rule);
      // These authored grammars also support the imperative after its object.
      if(Set.of("ja","ko","tr","hi").contains(language.getKey()))SUFFIXES.add(rule);
     }else WORDS.add(rule);
    }
   }
  }catch(IOException|RuntimeException invalid){WORDS.clear();BUILD_WORDS.clear();PREFIXES.clear();SUFFIXES.clear();AFFIRMATIONS.clear();BLOCKERS.clear();}
  Comparator<Rule> longest=Comparator.comparingInt((Rule r)->r.source.length()).reversed();
  WORDS.sort(longest);BUILD_WORDS.sort(longest);PREFIXES.sort(longest);SUFFIXES.sort(longest);
  for(Rule rule:WORDS)WORD_TARGETS.putIfAbsent(rule.source,rule.target);
  WORD_PATTERN=Pattern.compile(WORDS.isEmpty()?"(?!)":String.join("|",WORDS.stream().map(r->r.pattern.pattern()).toList()));
 }
 private HumanIntentLexicon(){}
 private static String base(String s){return s==null?"":s.toLowerCase(Locale.ROOT).replace('\u0451','\u0435').replace('\u2019','\'').strip().replaceAll("\\s+"," ");}
 private static boolean unspaced(String s){return s.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN||Character.UnicodeScript.of(c)==Character.UnicodeScript.HIRAGANA||Character.UnicodeScript.of(c)==Character.UnicodeScript.KATAKANA||Character.UnicodeScript.of(c)==Character.UnicodeScript.HANGUL);}
 private static Pattern phrase(String s){return Pattern.compile((unspaced(s)?"":"(?<![\\p{L}\\p{N}_])")+Pattern.quote(s)+(unspaced(s)?"":"(?![\\p{L}\\p{N}_])"));}
 static boolean affirmation(String human){return human!=null&&human.length()<=64&&AFFIRMATIONS.contains(base(human).replaceFirst("[.!\u3002\uff01 ]+$",""));}
 /** Original text stays in messages and pending-request identity; this view is for human policy checks only. */
 static String policyText(String human){
  if(human!=null&&human.length()>1024)return "not";
  String s=base(human);
  if(human!=null&&(human.indexOf('\n')>=0||human.indexOf('\r')>=0))return "not "+s;
  for(Rule word:WORDS)if(word.target.equals("please"))s=word.pattern.matcher(s).replaceAll("please");
  // Block reported, conditional, negative and informational language before recognizing an action.
  int actionStart=-1,actionEnd=-1;
  String commandStart=s.replaceFirst("^(?:verity[,! ]+)?(?:please[,! ]*)?","");int offset=s.length()-commandStart.length();
  for(Rule rule:PREFIXES){Matcher m=rule.pattern.matcher(commandStart);if(m.find()&&m.start()==0){actionStart=offset+m.start();actionEnd=offset+m.end();break;}}
  if(actionStart<0)for(Rule rule:SUFFIXES){Matcher m=rule.pattern.matcher(s);if(m.find()&&s.substring(m.end()).matches("[.!?\u3002\uff01\uff1f ]*")){actionStart=m.start();actionEnd=m.end();break;}}
  for(Pattern blocker:BLOCKERS){Matcher m=blocker.matcher(s);while(m.find())if(actionStart<0||m.start()<actionStart||m.end()>actionEnd)return "not "+s;}
  String start=s.replaceFirst("^(?:verity[,! ]+)?(?:please[,! ]*)?","");
  for(Rule rule:PREFIXES){Matcher m=rule.pattern.matcher(start);if(m.find()&&m.start()==0){s=(rule.target.equals("stop following")?"stop following me":rule.target)+" "+start.substring(m.end()).strip();break;}}
  if(s.equals(base(human))||s.startsWith("please "))for(Rule rule:SUFFIXES){Matcher m=rule.pattern.matcher(start);if(m.find()&&start.substring(m.end()).matches("[.!?\u3002\uff01\uff1f ]*")){s=(rule.target.equals("stop following")?"stop following me":rule.target)+" "+start.substring(0,m.start()).strip();break;}}
  // Each source span is replaced once, so one language cannot recursively manufacture another command.
  Matcher words=WORD_PATTERN.matcher(s);StringBuilder result=new StringBuilder();
  while(words.find())words.appendReplacement(result,Matcher.quoteReplacement(" "+WORD_TARGETS.get(words.group())+" "));
  words.appendTail(result);
  return result.toString().replaceAll("\\s+"," ").strip();
 }
 /** Material/location aliases are construction-only; they must not replace ownership pronouns in other policies. */
 static String buildText(String human){
  String result=policyText(human);
  for(Rule rule:BUILD_WORDS)result=rule.pattern.matcher(result).replaceAll(Matcher.quoteReplacement(" "+rule.target+" "));
  return result.replaceAll("\\s+"," ").strip();
 }
 static String request(String human){
  if(human==null)return "";
  String canonical=policyText(human);
  return canonical.matches("(?:give|apply|build|follow|stop|undo|enchant|remove)(?: .*|$)")?canonical:human;
 }
}
