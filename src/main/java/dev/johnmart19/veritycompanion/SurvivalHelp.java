package dev.johnmart19.veritycompanion;
import com.google.gson.*;import java.util.Locale;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
/** Narrow, opt-in gameplay rescue. Never kill, teleport or clear unrelated effects. */
final class SurvivalHelp {
 static boolean requested(String text){if(!EffectPolicy.safe(text))return false;String s=text.toLowerCase(Locale.ROOT);
  if(s.matches("(?s).*(не помогай|не лечи|не корми|не спасай|don't|do not|как|how|объясни|расскажи|explain|translate|переведи|/kill|убей меня|kill me).*")||s.matches("(?s).*[\"«»`\\n\\r].*"))return false;
  // General requests for help with recipes, quests or navigation do not authorize rescue.
  if(s.matches("(?s).*(спаси|умираю|исцели|вылечи|накорми|голодаю|save me|heal me|feed me|i.m dying|starving).*"))return true;
  return s.matches("(?s).*(помоги|help me).*")&&s.matches("(?s).*(я голод(?:ен|на)|мало здоровь|низкое здоровь|ранен|тону|задыхаюсь|горю|i(?:'m| am) hungry|(?:low|little) health|wounded|drowning|suffocating|i(?:'m| am) on fire).*");
 }
 static String help(ServerPlayer p,String text){if(!CompanionBrain.settings.gifts||!requested(text))throw new IllegalArgumentException("Ask for in-game rescue, feeding or healing");if(!p.isAlive())return "Player is already dead; cannot revive through this tool. Use the normal respawn screen, never /kill.";
  JsonObject out=new JsonObject();out.addProperty("health_before",p.getHealth());out.addProperty("hunger_before",p.getFoodData().getFoodLevel());boolean hunger=p.hasEffect(MobEffects.HUNGER);out.addProperty("hunger_effect_removed",hunger&&p.removeEffect(MobEffects.HUNGER));
  if(p.getFoodData().getFoodLevel()<18||hunger){p.getFoodData().setFoodLevel(20);p.getFoodData().setSaturation(Math.max(5,p.getFoodData().getSaturationLevel()));}
  if(p.getHealth()<Math.min(8,p.getMaxHealth()))p.heal(Math.min(8,p.getMaxHealth()-p.getHealth()));
  out.addProperty("health_after",p.getHealth());out.addProperty("hunger_after",p.getFoodData().getFoodLevel());out.addProperty("other_effects_preserved",true);return out.toString();
 }
}
