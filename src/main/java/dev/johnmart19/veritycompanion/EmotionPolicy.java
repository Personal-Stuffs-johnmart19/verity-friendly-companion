package dev.johnmart19.veritycompanion;
import java.util.Set;
import java.util.Optional;
import java.util.Locale;
/** Delivery is not relationship state. No mood, karma, damage or horror side effects. */
public final class EmotionPolicy {
 /** One ordered catalogue shared by model schema, prompt, commands and rendering. */
 public static final java.util.List<String> CATALOG=java.util.List.of("neutral","happy","excited","curious","reassuring","sad","serious","annoyed","amused","laughing","mischievous","smug","deadpan","sarcastic","embarrassed","surprised","confused","worried","afraid","disgusted","disappointed","tired","proud","affectionate","thoughtful","skeptical","angry");
 public static final Set<String> VALUES=Set.copyOf(CATALOG);
 public static final Set<String> FACES;
 static {var faces=new java.util.HashSet<>(VALUES);faces.add("auto");FACES=Set.copyOf(faces);}
 public static String choices(){return String.join(",",CATALOG);}
 /** Recognizes only direct human requests; descriptive chat is never an instruction. */
 public static Optional<String> requestedFace(String input){
  if(input==null||input.length()>500)return Optional.empty();
  String text=input.toLowerCase(Locale.ROOT).replace('ё','е').strip();
  if(text.matches("(?s).*[?\"«»“”`].*")||text.matches("(?s).*(?<![\\p{L}])(?:не|нет|нельзя|not|never|don't|dont|do not)(?![\\p{L}]).*"))return Optional.empty();
  // Personal display can change Verity's face, never the speaker's emotional state.
  if(text.matches("(?s).*(?<![\\p{L}])(?:мне|меня|мое|мою|мои|моим|мой|my|me|player)(?![\\p{L}]).*"))return Optional.empty();
  text=text.replaceFirst("^(?:verity|верити|верити,|verity,)\\s*[,!:.]?\\s*","")
      .replaceFirst("^(?:пожалуйста|please)\\s*[,!:.]?\\s*","")
      .replaceFirst("[,! .]*(?:пожалуйста|please)[! .]*$","").replaceFirst("[!.]+$","").strip();
  if(text.equals("улыбнись")||text.equals("smile"))return Optional.of("happy");
  if(text.equals("нахмурься")||text.equals("frown"))return Optional.of("sad");
  String target="(?:свое\\s+лицо|свою\\s+эмоцию|свое\\s+выражение(?:\\s+лица)?|выражение\\s+своего\\s+лица|лицо|эмоцию|выражение\\s+лица|себя)";
  String englishTarget="(?:your\\s+(?:face|emotion|expression|facial\\s+expression)|yourself)";
  if(!text.endsWith("себя")&&!text.endsWith("yourself")&&(text.matches("(?:поменяй|измени|смени)\\s+"+target)||text.matches("(?:change|switch)\\s+"+englishTarget)))return Optional.of("excited");
  String choice=null;
  java.util.regex.Matcher ru=java.util.regex.Pattern.compile("^(?:(?:сделай|поменяй|измени|смени|верни)\\s+"+target+"\\s+(?:(?:на|в)\\s+)?|(?:стань|будь)\\s+)([\\p{L}]+)$").matcher(text);
  java.util.regex.Matcher en=java.util.regex.Pattern.compile("^(?:(?:make|set|change|switch|return|reset)\\s+"+englishTarget+"\\s+(?:(?:to|back\\s+to)\\s+)?|(?:be|look|become)\\s+)([a-z]+)$").matcher(text);
  if(ru.matches())choice=ru.group(1);else if(en.matches())choice=en.group(1);else return Optional.empty();
  String face=switch(choice){
   case "happy","радостным","радостной","радостное","радостную","радостнее","веселым","веселой","веселое","веселую","веселее","счастливым","счастливой","счастливое"->"happy";
   case "neutral","нейтральным","нейтральной","нейтральное","нейтральную"->"neutral";
   case "sad","грустным","грустной","грустное","грустную","грустнее","печальным","печальной","печальное"->"sad";
   case "serious","серьезным","серьезной","серьезное","серьезную","серьезнее"->"serious";
   case "angry","злым","злой","злое","злую","сердитым","сердитой","сердитое","сердитую"->"angry";
   case "excited","восторженным","восторженной","восторженное","восторженную","возбужденным","возбужденное"->"excited";
   case "auto","авто","автоматическое","автоматическую"->"auto";
   case "amused","позабавленной","позабавленным"->"amused";
   case "laughing","смеющейся","смеющимся"->"laughing";
   case "mischievous","озорной","озорным","озорное","озорную"->"mischievous";
   case "smug","самодовольной","самодовольным","самодовольное"->"smug";
   case "deadpan","невозмутимой","невозмутимым"->"deadpan";
   case "sarcastic","саркастичной","саркастичным"->"sarcastic";
   case "embarrassed","смущенной","смущенным","смущенное"->"embarrassed";
   case "surprised","удивленной","удивленным","удивленное"->"surprised";
   case "confused","растерянной","растерянным","растерянное"->"confused";
   case "worried","обеспокоенной","обеспокоенным"->"worried";
   case "afraid","испуганной","испуганным","испуганное"->"afraid";
   case "disgusted","брезгливой","брезгливым"->"disgusted";
   case "disappointed","разочарованной","разочарованным"->"disappointed";
   case "tired","усталой","усталым","усталое"->"tired";
   case "proud","гордой","гордым","гордое"->"proud";
   case "affectionate","ласковой","ласковым","ласковое"->"affectionate";
   case "thoughtful","задумчивой","задумчивым","задумчивое"->"thoughtful";
   case "skeptical","скептичной","скептичным"->"skeptical";
   case "curious","любопытной","любопытным","любопытное"->"curious";
   case "reassuring","ободряющей","ободряющим"->"reassuring";
   case "annoyed","раздраженной","раздраженным"->"annoyed";
   default->null;
  };
  return Optional.ofNullable(face);
 }
 public static String faceVariant(String face){if(face==null||!VALUES.contains(face))throw new IllegalArgumentException("Unsupported face: "+face);return switch(face){case "neutral"->"neutral_0";case "sad"->"serious_1";case "excited"->"talking_0";default->variant(face);};}
 public static String wire(String value){if(value!=null&&value.startsWith("face:")&&FACES.contains(value.substring(5)))return value;return normalize(value);}
 public static String normalize(String value){return value!=null&&VALUES.contains(value)?value:"neutral";}
 /** Native asset fallback only; expressive rendering uses the full emotion name. */
 public static String variant(String emotion){return switch(normalize(emotion)){case "curious","confused","thoughtful","skeptical","surprised"->"serious_1";case "sad","disappointed","tired","deadpan","embarrassed"->"neutral_0";case "serious","worried","afraid"->"serious_2";case "annoyed","angry","disgusted"->"serious_angry";default->"default";};}
 public static String safeVariant(String value){return value!=null&&Set.of("default","neutral_0","serious_1","serious_2","serious_angry").contains(value)?value:"default";}
}
