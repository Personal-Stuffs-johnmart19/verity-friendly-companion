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
 Optional<String> buildReply(boolean ru){return buildReply(ru?"ru":"en");}
 Optional<String> buildReply(String language){if(builds.isEmpty())return Optional.empty();StringJoiner reply=new StringJoiner("\n");for(BuildOutcome outcome:builds.values())reply.add(outcome.reply(language));if(!unverifiedBuilds.isEmpty())reply.add(ModTranslations.text(language,"verity_companion.request_feedback.additional_build_outcomes_are_unconfirmed_check_the"));return Optional.of(reply.toString());}
 boolean hasEffects(){return !effects.isEmpty();}
 boolean observationsOnly(boolean anyMutation,String observations){if(anyMutation){observationRounds=0;previousObservations="";return false;}if(!previousObservations.equals(observations)){previousObservations=observations;observationRounds=0;}return ++observationRounds>=3;}
 String failed(boolean ru,boolean build){return failed(ru?"ru":"en",build);}
 String failed(String language,boolean build){
  Optional<String> verified=buildReply(language);if(verified.isPresent())return verified.get()+(uncertain?(ModTranslations.text(language,"verity_companion.request_feedback.additional_actions_are_unconfirmed_check_the_world")):"");
  if(uncertain)return ModTranslations.text(language,"verity_companion.request_feedback.could_not_confirm_the_game_action_outcome");
  if(effects.isEmpty())return build?ModTranslations.text(language,"verity_companion.request_feedback.could_not_finish_the_site_selection_and"):ModTranslations.text(language,"verity_companion.request_feedback.could_not_finish_the_reply_observations_do");
  String prefix=ModTranslations.text(language,"verity_companion.request_feedback.reply_interrupted_confirmed_results_do_not_blindly");
  StringBuilder text=new StringBuilder(prefix);for(var effect:effects.entrySet()){String value=unverifiedBuilds.contains(effect.getKey())?(ModTranslations.text(language,"verity_companion.request_feedback.the_build_outcome_is_unconfirmed_check_the")):effect.getValue();String line=value.length()>160?value.substring(0,160)+"…":value;if(text.length()+line.length()>650)break;text.append('\n').append(line);}return text.toString();
 }
}
