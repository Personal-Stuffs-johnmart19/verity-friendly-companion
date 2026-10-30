package dev.johnmart19.veritycompanion;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.Unbreakable;
/** Server gift mode: mutate only the authenticated requester's current main-hand item. */
public final class HeldItemTools {
 public static String setUnbreakable(ServerPlayer p,JsonObject args,String original){
  if(!CompanionBrain.settings.gifts)throw new IllegalStateException("Gifts disabled");
  boolean requested=HeldItemPolicy.requestedUnbreakable(original).orElseThrow(()->new IllegalArgumentException("Ask to change your held item's durability"));
  boolean enabled=args.has("enabled")?args.get("enabled").getAsBoolean():true;
  if(enabled!=requested)throw new IllegalArgumentException("Durability change does not match your request");
  ItemStack stack=p.getMainHandItem();Integer max=stack.get(DataComponents.MAX_DAMAGE);
  if(stack.isEmpty()||max==null||max<=0)throw new IllegalArgumentException(ModTranslations.text(Relations.get(p).language,"verity_companion.held_item_tools.hold_a_damageable_tool_in_your_main"));
  boolean matches=switch(HeldItemPolicy.requestedKind(original)){
   case "pickaxe"->stack.is(ItemTags.PICKAXES)||stack.getItem() instanceof PickaxeItem;
   case "sword"->stack.is(ItemTags.SWORDS)||stack.getItem() instanceof SwordItem;
   case "axe"->stack.is(ItemTags.AXES)||stack.getItem() instanceof AxeItem;
   case "shovel"->stack.is(ItemTags.SHOVELS)||stack.getItem() instanceof ShovelItem;
   case "hoe"->stack.is(ItemTags.HOES)||stack.getItem() instanceof HoeItem;
   case "held"->true;default->false;
  };
  if(!matches)throw new IllegalArgumentException(ModTranslations.text(Relations.get(p).language,"verity_companion.held_item_tools.hold_the_tool_you_want_to_change"));
  if(enabled)stack.set(DataComponents.UNBREAKABLE,new Unbreakable(true));else stack.remove(DataComponents.UNBREAKABLE);
  p.containerMenu.broadcastChanges();
  return enabled?ModTranslations.text(Relations.get(p).language,"verity_companion.held_item_tools.done_your_held_item_no_longer_loses"):ModTranslations.text(Relations.get(p).language,"verity_companion.held_item_tools.restored_normal_durability_loss_for_your_held");
 }
}
