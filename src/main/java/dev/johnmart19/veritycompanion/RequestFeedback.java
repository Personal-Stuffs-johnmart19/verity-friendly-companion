package dev.johnmart19.veritycompanion;
import java.util.*;
/** Player-facing failure text contains real mutations, never raw observation dumps. */
public final class RequestFeedback {
 private final LinkedHashMap<String,String> effects=new LinkedHashMap<>();
 private final LinkedHashMap<String,BuildOutcome> builds=new LinkedHashMap<>();
 private final Set<String> unverifiedBuilds=new HashSet<>();
 private int observationRounds;private String previousObservations="";private boolean uncertain;
 void uncertain(){uncertain=true;}
 static boolean building(String request){return request.toLowerCase(Locale.ROOT).matches("(?s).*(?<![\\p{L}])(построй|построить|построим|строй|сооруди|возведи|build|construct)(?![\\p{L}]).*");}
 void effect(String key,String tool,String result){if(effects.containsKey(key))return;String value=result==null?"":result;effects.put(key,tool+": "+value.replaceAll("\\s+"," "));if(Set.of("build_structure","build_blocks","build_schematic").contains(tool)){Optional<BuildOutcome> outcome=BuildOutcome.parse(value);if(outcome.isPresent())builds.put(key,outcome.get());else unverifiedBuilds.add(key);}}
 Optional<String> buildReply(boolean ru){if(builds.isEmpty())return Optional.empty();StringJoiner reply=new StringJoiner("\n");for(BuildOutcome outcome:builds.values())reply.add(outcome.reply(ru));if(!unverifiedBuilds.isEmpty())reply.add(ru?"Дополнительные результаты строительства не подтверждены; проверь мир перед повтором.":"Additional build outcomes are unconfirmed; check the world before repeating.");return Optional.of(reply.toString());}
 boolean hasEffects(){return !effects.isEmpty();}
 boolean observationsOnly(boolean anyMutation,String observations){if(anyMutation){observationRounds=0;previousObservations="";return false;}if(!previousObservations.equals(observations)){previousObservations=observations;observationRounds=0;}return ++observationRounds>=3;}
 String failed(boolean ru,boolean build){
  Optional<String> verified=buildReply(ru);if(verified.isPresent())return verified.get()+(uncertain?(ru?"\nДополнительные действия не подтверждены; проверь мир перед повтором.":"\nAdditional actions are unconfirmed; check the world before repeating."):"");
  if(uncertain)return ru?"Не удалось подтвердить результат игрового действия. Оно могло выполниться частично; проверь мир перед повтором.":"Could not confirm the game action outcome. It may have partially completed; check the world before repeating.";
  if(effects.isEmpty())return ru?(build?"Не удалось закончить выбор места и план постройки. Строительство не запускалось; блоки не изменены.":"Не удалось завершить ответ. Полученные данные не означают, что действие выполнено."):(build?"Could not finish the site selection and building plan. Construction did not start; no blocks changed.":"Could not finish the reply. Observations do not mean an action was completed.");
  String prefix=ru?"Ответ прервался. Подтверждённые результаты (не повторяй действия вслепую):":"Reply interrupted. Confirmed results (do not blindly repeat actions):";
  StringBuilder text=new StringBuilder(prefix);for(var effect:effects.entrySet()){String value=unverifiedBuilds.contains(effect.getKey())?(ru?"Результат строительства не подтверждён; проверь мир перед повтором.":"The build outcome is unconfirmed; check the world before repeating."):effect.getValue();String line=value.length()>160?value.substring(0,160)+"…":value;if(text.length()+line.length()>650)break;text.append('\n').append(line);}return text.toString();
 }
}
