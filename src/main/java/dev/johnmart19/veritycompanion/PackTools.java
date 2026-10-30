package dev.johnmart19.veritycompanion;

import com.google.gson.JsonObject;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Generic registry and public capability adapters, without dumping private block entity NBT. */
public final class PackTools {
 static String item(ServerPlayer p,JsonObject a){
  int slot=CompanionBrain.number(a,"slot",p.getInventory().selected);if(slot<0||slot>=p.getInventory().getContainerSize())throw new IllegalArgumentException("Invalid inventory slot");
  ItemStack stack=p.getInventory().getItem(slot);if(stack.isEmpty())return "Empty slot";
  StringBuilder out=new StringBuilder(BuiltInRegistries.ITEM.getKey(stack.getItem())+" x"+stack.getCount()+" name="+stack.getHoverName().getString()+" damage="+stack.getDamageValue()+"/"+stack.getMaxDamage()+"\nComponent types:");
  for(var type:stack.getComponents().keySet())out.append(' ').append(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type));
  out.append("\nActual enchantments: ").append(EnchantmentTools.inspect(stack));
  out.append("\nTags:");stack.getTags().limit(100).forEach(t->out.append(' ').append(t.location()));return out+"\nOnly public gameplay fields are decoded. Custom component values require a dedicated adapter; security codes and arbitrary custom NBT are not exposed.";
 }
 static String entity(ServerPlayer p,JsonObject a){
  var e=p.serverLevel().getEntity(CompanionBrain.number(a,"id",-1));if(e==null||e.distanceToSqr(p)>64*64)throw new IllegalArgumentException("Entity must be loaded and within 64 blocks; get IDs from inspect_world");
  StringBuilder out=new StringBuilder(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())+" #"+e.getId()+" name="+e.getName().getString()+" at "+e.blockPosition());
  if(e instanceof net.minecraft.world.entity.LivingEntity living){out.append("\nHealth=").append(living.getHealth()).append('/').append(living.getMaxHealth()).append(" armor=").append(living.getArmorValue());out.append("\nEffects:");for(var effect:living.getActiveEffects())out.append(' ').append(BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value())).append(" level=").append(effect.getAmplifier()+1).append(" ticks=").append(effect.getDuration());out.append("\nAttributes:");for(var entry:BuiltInRegistries.ATTRIBUTE.entrySet()){var holder=BuiltInRegistries.ATTRIBUTE.getHolder(entry.getKey()).orElseThrow();var attribute=living.getAttribute(holder);if(attribute!=null)out.append(' ').append(entry.getKey().location()).append('=').append(attribute.getValue());}}
  return out+"\nCustom boss phases, scripts and immunities are not inferred from attributes; verify mod documentation.";
 }
 static String interact(ServerPlayer p,JsonObject a,String request){
  if(!request.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(нажми|нажать|открой|активируй|переключи|click|press|open|activate|toggle).*"))throw new IllegalArgumentException("Player must explicitly request interaction");
  BlockPos pos=p.blockPosition().offset(CompanionBrain.number(a,"x",0),CompanionBrain.number(a,"y",0),CompanionBrain.number(a,"z",0));var l=p.serverLevel();
  if(!l.hasChunkAt(pos)||p.blockPosition().distSqr(pos)>36||!l.mayInteract(p,pos))throw new IllegalArgumentException("Choose a permitted loaded block within six blocks");
  var result=p.gameMode.useItemOn(p,l,p.getMainHandItem(),net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),Direction.UP,pos,false));
  return "Interaction through the actual player's normal game mode (claims/security events apply): "+result+". Any opened custom GUI remains under player control; GUI automation is not supported.";
 }
 static String registry(ServerPlayer p,JsonObject a){
  String kind=CompanionBrain.string(a,"kind","items"),query=CompanionBrain.string(a,"query","").toLowerCase(java.util.Locale.ROOT);
  Registry<?> registry=switch(kind){case "blocks"->BuiltInRegistries.BLOCK;case "entities"->BuiltInRegistries.ENTITY_TYPE;case "effects"->BuiltInRegistries.MOB_EFFECT;case "fluids"->BuiltInRegistries.FLUID;case "recipe_types"->BuiltInRegistries.RECIPE_TYPE;case "components"->BuiltInRegistries.DATA_COMPONENT_TYPE;case "enchantments"->p.registryAccess().registryOrThrow(Registries.ENCHANTMENT);case "biomes"->p.registryAccess().registryOrThrow(Registries.BIOME);case "structures"->p.registryAccess().registryOrThrow(Registries.STRUCTURE);case "items"->BuiltInRegistries.ITEM;default->throw new IllegalArgumentException("Unknown registry kind");};
  return RegistryLexicon.forRegistry(kind,registry).page(query,CompanionBrain.number(a,"offset",0),CompanionBrain.number(a,"limit",6));
 }
 static String inspect(ServerPlayer p,JsonObject a){
  BlockPos pos=p.blockPosition().offset(CompanionBrain.number(a,"x",0),CompanionBrain.number(a,"y",0),CompanionBrain.number(a,"z",0));var l=p.serverLevel();
  if(!l.hasChunkAt(pos)||p.blockPosition().distSqr(pos)>64*64||!l.mayInteract(p,pos))throw new IllegalArgumentException("Choose a permitted loaded block within 64 blocks");
  var access=new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(p,net.minecraft.world.InteractionHand.MAIN_HAND,pos,new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),Direction.UP,pos,false));
  net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(access);if(access.isCanceled()||access.getUseBlock()==net.neoforged.neoforge.common.util.TriState.FALSE)throw new IllegalArgumentException("Block access denied by protection/security event");
  PackAdapters.requireAccess(p,pos);
  var state=l.getBlockState(pos);if(state.isAir())return "minecraft:air at "+pos+"; empty cell, no machine capabilities. For a build footprint use survey_build_site, not individual air inspections.";StringBuilder out=new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock())+" at "+pos+"\nState: "+state+"\nTags: ");state.getTags().limit(100).forEach(t->out.append(t.location()).append(' '));
  java.util.List<Direction> sides=new java.util.ArrayList<>(java.util.Arrays.asList(Direction.values()));if(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().startsWith("mekanism"))sides.add(0,null);
  for(Direction side:sides){
   out.append("\nSide ").append(side).append(':');
   var items=((net.neoforged.neoforge.common.extensions.ILevelExtension)(Object)l).getCapability(Capabilities.ItemHandler.BLOCK,pos,side);if(items!=null){out.append(" item slots=").append(items.getSlots());for(int i=0;i<Math.min(128,items.getSlots());i++){ItemStack stack=items.getStackInSlot(i);if(!stack.isEmpty())out.append(" [").append(i).append(' ').append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append(" x").append(stack.getCount()).append(']');}}
   var energy=((net.neoforged.neoforge.common.extensions.ILevelExtension)(Object)l).getCapability(Capabilities.EnergyStorage.BLOCK,pos,side);if(energy!=null)out.append(" energy=").append(energy.getEnergyStored()).append('/').append(energy.getMaxEnergyStored());
   var fluids=((net.neoforged.neoforge.common.extensions.ILevelExtension)(Object)l).getCapability(Capabilities.FluidHandler.BLOCK,pos,side);if(fluids!=null)for(int i=0;i<Math.min(32,fluids.getTanks());i++){var f=fluids.getFluidInTank(i);out.append(" tank=").append(i).append(' ').append(BuiltInRegistries.FLUID.getKey(f.getFluid())).append(' ').append(f.getAmount()).append('/').append(fluids.getTankCapacity(i));}
  }
  out.append(PackAdapters.read(l,pos));return out+"\nOnly exposed public capabilities are readable. No capabilities does not mean an empty machine. Proprietary networks, locks, security codes and custom GUIs are not bypassed.";
 }
 static String status(ServerPlayer p){ItemStack held=p.getMainHandItem();int occupied=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(!p.getInventory().getItem(i).isEmpty())occupied++;return "Player="+p.getGameProfile().getName()+" game_mode="+p.gameMode.getGameModeForPlayer().getName()+" occupied_inventory_slots="+occupied+"/"+p.getInventory().getContainerSize()+". Held item below is NOT a complete inventory listing. Position="+p.blockPosition()+" dimension="+p.serverLevel().dimension().location()+" health="+p.getHealth()+"/"+p.getMaxHealth()+" hunger="+p.getFoodData().getFoodLevel()+"/20 saturation="+p.getFoodData().getSaturationLevel()+" held="+(held.isEmpty()?"empty":BuiltInRegistries.ITEM.getKey(held.getItem())+" x"+held.getCount()+" stack_size="+held.getMaxStackSize());}
 static String search(String query,int limit){
  var matches=ServerItemCatalog.search(query,limit);StringBuilder out=new StringBuilder(ServerItemCatalog.status()+"\n");
  for(var match:matches){var id=ResourceLocation.parse(match.id());out.append(match.id()).append(" | ru=").append(match.russian()).append(" | en=").append(match.english()).append(" | stack_size=").append(BuiltInRegistries.ITEM.get(id).getDefaultMaxStackSize()).append('\n');}
  if(matches.isEmpty())out.append("No localized registered match. Keep modifiers; do not replace an unknown named mod item with a generic vanilla item.");return out.toString();
 }
 static String resolve(JsonObject a){String raw=CompanionBrain.string(a,"query","");if(raw.isBlank())throw new IllegalArgumentException("Specify the item or kind of supplies");
  String query=ItemHints.normalizeQuery(raw);var id=ResourceLocation.tryParse(query);
  if(id!=null&&BuiltInRegistries.ITEM.containsKey(id)&&BuiltInRegistries.ITEM.get(id)!=Items.AIR)return id+" | stack_size="+BuiltInRegistries.ITEM.get(id).getDefaultMaxStackSize();
  String result=search(raw,12);if(!ServerItemCatalog.search(raw,1).isEmpty())return result;
  StringBuilder choices=new StringBuilder(result);for(String hint:ItemHints.suggestions(raw))if(BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(hint)))choices.append('\n').append(hint).append(" | suggestion only, not delivered");return choices.toString();
 }
 static String give(ServerPlayer p,JsonObject a,String request){
  if(!CompanionBrain.settings.gifts)throw new IllegalStateException("Gifts disabled");
  String delivery=CompanionBrain.string(a,"delivery","inventory");
  long now=System.currentTimeMillis();String natural=ToolIntent.unaddress(request,Relations.name(p));
  boolean ground=ConversationIntent.allowsGround(p.getUUID(),natural,now)||ToolIntent.ground(natural);
  if(!ConversationIntent.allowsGift(p.getUUID(),natural,now)&&!ToolIntent.gifting(natural))throw new IllegalArgumentException("No item-delivery request in this message. If unclear ask naturally whether the player wants it; do not require a special gift keyword or command.");
  if(!delivery.equals("inventory")&&!delivery.equals("ground"))throw new IllegalArgumentException("delivery: inventory or ground");
  if(delivery.equals("ground")&&!ground)throw new IllegalArgumentException("Player must explicitly ask for ground delivery");
  var humanChoice=ItemRequestPolicy.plainNoun(natural).flatMap(ServerItemCatalog::resolveUnique);
  String supplied=CompanionBrain.string(a,"id",CompanionBrain.string(a,"query",""));
  String chosen=humanChoice.map(ItemCatalog.Match::id).orElseGet(()->ServerItemCatalog.resolveUnique(supplied).map(ItemCatalog.Match::id).orElse(ItemHints.normalizeQuery(supplied)));
  ResourceLocation id=ResourceLocation.parse(chosen);
  if(!BuiltInRegistries.ITEM.containsKey(id))throw new IllegalArgumentException("Unknown item");
  Item item=BuiltInRegistries.ITEM.get(id);if(item==Items.AIR)throw new IllegalArgumentException("Cannot give air");
  int count=ItemHints.stacksCount(ItemRequestPolicy.count(natural,CompanionBrain.number(a,"count",1),item.getDefaultMaxStackSize()),humanChoice.isPresent()&&!ToolIntent.hasQuantity(natural)?null:a.has("stacks")?CompanionBrain.number(a,"stacks",1):null,item.getDefaultMaxStackSize());
  var body=delivery.equals("ground")?CompanionPresence.select(p):null;
  int given=0;
  while(given<count){
   ItemStack stack=new ItemStack(item,Math.min(count-given,item.getDefaultMaxStackSize()));int amount=stack.getCount();
   if(delivery.equals("inventory"))p.getInventory().add(stack);
   if(!stack.isEmpty()){
    var dropped=p.drop(stack,false);if(dropped==null)throw new IllegalStateException("Item drop failed; already delivered "+given);
    if(delivery.equals("ground")){
     if(body!=null&&body.level()==p.level()&&body.distanceToSqr(p)<=64*64&&BuiltInRegistries.ENTITY_TYPE.getKey(body.getType()).getPath().equals("verity")){
      var direction=p.position().subtract(body.position()).normalize();
      dropped.setPos(body.getX(),body.getEyeY(),body.getZ());dropped.setDeltaMovement(direction.scale(.25).add(0,.12,0));
     }
     dropped.setPickUpDelay(40);
    }
   }
   given+=amount;
  }
  p.containerMenu.broadcastChanges();ConversationIntent.fulfilled(p.getUUID());return "Delivered "+id+" x"+given+" via "+delivery+" for requesting player (inventory overflow drops nearby)";
 }
 static String enchant(ServerPlayer p,JsonObject a,String request){
  if(!CompanionBrain.settings.gifts)throw new IllegalStateException("Gifts disabled");
  if(!ToolIntent.enchanting(ToolIntent.unaddress(request,Relations.name(p))))throw new IllegalArgumentException("Player must explicitly request enchanting");
  ItemStack stack=p.getMainHandItem();if(stack.isEmpty())throw new IllegalArgumentException("Hold the item to enchant in your main hand");
  EnchantmentTools.requireRequestedItem(stack,ToolIntent.unaddress(request,Relations.name(p)));
  var registry=p.registryAccess().registryOrThrow(Registries.ENCHANTMENT);String query=CompanionBrain.string(a,"id","");var lexicon=RegistryLexicon.forRegistry("enchantments",registry);String resolved=lexicon.resolveUnique(query).orElseThrow(()->new IllegalArgumentException("Enchantment name is unknown or ambiguous; choose an exact registered ID. "+lexicon.page(query,0,6)));var id=ResourceLocation.tryParse(resolved);if(id==null)throw new IllegalArgumentException("Invalid registered enchantment ID");var holder=registry.getHolder(id).orElseThrow(()->new IllegalArgumentException("Unknown enchantment"));int level=CompanionBrain.number(a,"level",holder.value().getMaxLevel());if(level<1||level>255)throw new IllegalArgumentException("Choose enchantment level 1..255");if(stack.is(Items.BOOK)){stack=stack.transmuteCopy(Items.ENCHANTED_BOOK);p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,stack);}net.minecraft.world.item.enchantment.EnchantmentHelper.updateEnchantments(stack,mutable->mutable.set(holder,level));p.containerMenu.broadcastChanges();return "Enchanted requesting player's held item with "+id+" level "+level+". Applicability/conflict checks intentionally relaxed by server gift mode.";
 }
}
