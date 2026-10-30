package dev.johnmart19.veritycompanion;

import com.google.gson.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.enchantment.*;

/** Reads actual components, including book enchantments and levels above vanilla limits. */
final class EnchantmentTools {
 static JsonObject inspect(ItemStack stack){
  JsonObject out=new JsonObject();
  out.add("applied",entries(stack.getOrDefault(DataComponents.ENCHANTMENTS,ItemEnchantments.EMPTY)));
  out.add("stored",entries(stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS,ItemEnchantments.EMPTY)));
  out.addProperty("unbreakable",stack.has(DataComponents.UNBREAKABLE));
  out.addProperty("coverage","Actual enchantment components; custom mod upgrades are not inferred to be enchantments.");
  return out;
 }
 private static JsonArray entries(ItemEnchantments enchantments){
  JsonArray rows=new JsonArray();for(var e:enchantments.entrySet()){
   JsonObject row=new JsonObject();row.addProperty("id",e.getKey().unwrapKey().map(k->k.location().toString()).orElse("unregistered"));
   row.addProperty("level",e.getIntValue());row.addProperty("display",Enchantment.getFullname(e.getKey(),e.getIntValue()).getString());rows.add(row);
  }return rows;
 }
 static String clear(ServerPlayer p,String human){
  if(!CompanionBrain.settings.gifts)throw new IllegalStateException("Gifts disabled");
  if(!EnchantmentIntent.clearAll(human))throw new IllegalArgumentException("Ask to remove all enchantments from your held item");
  ItemStack stack=p.getMainHandItem();if(stack.isEmpty())throw new IllegalArgumentException(ModTranslations.text(Relations.get(p).language,"verity_companion.enchantment_tools.hold_the_item_in_your_main_hand"));
  JsonObject before=inspect(stack);int count=before.getAsJsonArray("applied").size()+before.getAsJsonArray("stored").size();
  // Preserve durability, unbreakability, name and every unrelated component.
  clearRequestedComponents(stack,human);p.containerMenu.broadcastChanges();
  return count==0?ModTranslations.text(Relations.get(p).language,"verity_companion.enchantment_tools.your_held_item_has_no_applied_or"):
   ModTranslations.text(Relations.get(p).language,"verity_companion.enchantment_tools.removed_enchantments_from_your_held_item")+count+ModTranslations.text(Relations.get(p).language,"verity_companion.enchantment_tools.unbreakability_and_other_properties_were_preserved");
 }
 static void clearRequestedComponents(ItemStack stack,String human){
  if(!EnchantmentIntent.clearAll(human))throw new IllegalArgumentException("Ask to remove all enchantments from your held item");
  requireRequestedItem(stack,human);
  clearComponents(stack);
 }
 static void requireRequestedItem(ItemStack stack,String human){
  String text=ToolIntent.normalized(human);
  var targets=java.util.EnumSet.noneOf(Target.class);
  for(Target target:Target.values())if(target.words.matcher(text).find())targets.add(target);
  if(targets.stream().anyMatch(t->t.armorType!=null))targets.remove(Target.ARMOR);
  if(targets.size()>1)throw new IllegalArgumentException("Name one held item kind to change; multiple item kinds are ambiguous");
  if(stack.isEmpty()||!targets.isEmpty()&&!targets.iterator().next().matches.test(stack))throw new IllegalArgumentException("Hold the item kind you asked to change in your main hand");
 }
 private enum Target {
  PICKAXE("pickaxes?|кирк[а-я]*",s->s.is(ItemTags.PICKAXES)||s.getItem() instanceof PickaxeItem),
  SWORD("swords?|меч[а-я]*",s->s.is(ItemTags.SWORDS)||s.getItem() instanceof SwordItem),
  AXE("axes?|топор[а-я]*",s->s.is(ItemTags.AXES)||s.getItem() instanceof AxeItem),
  SHOVEL("shovels?|лопат[а-я]*",s->s.is(ItemTags.SHOVELS)||s.getItem() instanceof ShovelItem),
  HOE("hoes?|мотыг[а-я]*",s->s.is(ItemTags.HOES)||s.getItem() instanceof HoeItem),
  BOOK("books?|книг[а-я]*",s->s.is(Items.BOOK)||s.is(Items.ENCHANTED_BOOK)),
  BOW("bows?|лук[а-я]*",s->s.getItem() instanceof BowItem),
  CROSSBOW("crossbows?|арбалет[а-я]*",s->s.getItem() instanceof CrossbowItem),
  TRIDENT("tridents?|трезуб[а-я]*",s->s.getItem() instanceof TridentItem),
  SHIELD("shields?|щит[а-я]*",s->s.getItem() instanceof ShieldItem),
  HELMET("helmets?|шлем[а-я]*",ArmorItem.Type.HELMET),
  CHESTPLATE("chestplates?|нагрудник[а-я]*|кирас[а-я]*",ArmorItem.Type.CHESTPLATE),
  LEGGINGS("leggings|понож[а-я]*|штаны|штанов",ArmorItem.Type.LEGGINGS),
  BOOTS("boots?|ботин[а-я]*|сапог[а-я]*",ArmorItem.Type.BOOTS),
  ARMOR("armo[u]?r|брон(?:я|ю|и|е|ей|ёй)",s->s.getItem() instanceof ArmorItem);
  final java.util.regex.Pattern words;final java.util.function.Predicate<ItemStack> matches;final ArmorItem.Type armorType;
  Target(String words,java.util.function.Predicate<ItemStack> matches){this(words,matches,null);}
  Target(String words,ArmorItem.Type type){this(words,s->s.getItem() instanceof ArmorItem armor&&armor.getType()==type,type);}
  Target(String words,java.util.function.Predicate<ItemStack> matches,ArmorItem.Type type){this.words=java.util.regex.Pattern.compile("(?<![\\p{L}])(?:"+words+")(?![\\p{L}])");this.matches=matches;this.armorType=type;}
 }
 static void clearComponents(ItemStack stack){stack.remove(DataComponents.ENCHANTMENTS);stack.remove(DataComponents.STORED_ENCHANTMENTS);}
}
