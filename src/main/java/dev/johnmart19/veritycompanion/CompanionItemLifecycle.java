package dev.johnmart19.veritycompanion;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Thin native-event replacements: no commands, global sound changes or conversation writes. */
public final class CompanionItemLifecycle {
 private static final ResourceLocation ITEM=ResourceLocation.parse("verity:verity_item"),BODY=ResourceLocation.parse("verity:verity");
 private static final String ID="verity_friendly_body",ALIAS="verity_friendly_alias",VARIANT="VerityVariant";
 private CompanionItemLifecycle(){}
 private static boolean portable(ItemStack stack){return !stack.isEmpty()&&ITEM.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));}
 private static CompoundTag data(ItemStack stack){return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();}
 private static UUID owner(CompoundTag tag){return tag.hasUUID(CompanionPresence.OWNER)?tag.getUUID(CompanionPresence.OWNER):null;}
 private static void message(ServerPlayer player,String key){player.sendSystemMessage(Component.literal(ModTranslations.text(Relations.get(player).language,key)));}
 /** Returns true only for the friendly body; native box-opening and other interactions pass through. */
 public static boolean interact(PlayerInteractEvent.EntityInteract event){
  Entity body=event.getTarget();if(ResourceLocation.parse("verity:box").equals(BuiltInRegistries.ENTITY_TYPE.getKey(body.getType())))return boxInteraction(event);if(!BODY.equals(BuiltInRegistries.ENTITY_TYPE.getKey(body.getType())))return false;
  if(event.isCanceled())return true;
  if(event.getHand()!=InteractionHand.MAIN_HAND||!event.getEntity().getMainHandItem().isEmpty())return true;
  event.setCanceled(true);event.setCancellationResult(InteractionResult.FAIL);
  if(!(event.getEntity() instanceof ServerPlayer player))return true;
  if(!player.isAlive()||player.isSpectator()||!body.isAlive()||body.level()!=player.level()||body.distanceToSqr(player)>36)return true;
  if(!CompanionItemPolicy.mayPickUp(CompanionPresence.owner(body),player.getUUID())){
   message(player,"verity_companion.item_lifecycle.claim_your_companion_with_companion_come_first");return true;
  }
  try{
   if(Boolean.TRUE.equals(body.getClass().getMethod("isTalking").invoke(body))){message(player,"verity_companion.item_lifecycle.wait_until_the_companion_finishes_speaking");return true;}
   var item=BuiltInRegistries.ITEM.get(ITEM);if(!ITEM.equals(BuiltInRegistries.ITEM.getKey(item)))return true;
   ItemStack packed=new ItemStack(item);CompoundTag tag=new CompoundTag();
   tag.putUUID(CompanionPresence.OWNER,player.getUUID());tag.putUUID(ID,body.getUUID());
   tag.putString(ALIAS,CompanionItemPolicy.alias(body.getCustomName()!=null?body.getCustomName().getString():Relations.name(player)));
   tag.putString(VARIANT,CompanionItemPolicy.variant((String)body.getClass().getMethod("getVariant").invoke(body)));
   packed.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
   // Empty main hand is a guaranteed destination; create the complete item before removing the body.
   CompanionFollow.stop(player);player.setItemInHand(InteractionHand.MAIN_HAND,packed);body.discard();
   player.inventoryMenu.broadcastChanges();event.setCancellationResult(InteractionResult.SUCCESS);
  }catch(ReflectiveOperationException failure){message(player,"verity_companion.item_lifecycle.could_not_preserve_the_companion_it_remains");}
  return true;
 }
 private static boolean boxInteraction(PlayerInteractEvent.EntityInteract event){
  if(event.isCanceled())return true;if(!(event.getEntity() instanceof ServerPlayer player))return false;
  UUID owner=CompanionPresence.owner(event.getTarget());
  if(owner!=null&&!owner.equals(player.getUUID())){event.setCanceled(true);event.setCancellationResult(InteractionResult.FAIL);message(player,"verity_companion.item_lifecycle.this_box_belongs_to_another_player");return true;}
  if(owner==null)CompanionPresence.tag(player,event.getTarget());return false;
 }
 /** Runs after native positioning: preserve fractional surfaces and the exact opener. */
 public static void claimBoxBody(Entity body,net.minecraft.world.entity.player.Player opener,Entity box){
  if(body==null||!(opener instanceof ServerPlayer player)||box==null||body.level()!=player.level()||box.level()!=player.level())return;
  if(!BODY.equals(BuiltInRegistries.ENTITY_TYPE.getKey(body.getType()))||!player.getUUID().equals(CompanionPresence.owner(box)))return;
  // blockPosition() rounds a resting path/slab box down into its supporting block.
  // Keep the original spawn/jump sequence, using the box's actual feet position.
  body.moveTo(box.getX(),box.getY(),box.getZ(),body.getYRot(),body.getXRot());
  CompanionPresence.tag(player,body);
 }
 /** Intercepts only the portable companion item, preserving unrelated item and block behavior. */
 public static boolean place(PlayerInteractEvent.RightClickBlock event){
  ItemStack stack=event.getEntity().getItemInHand(event.getHand());if(!portable(stack))return false;
  if(event.isCanceled())return true;event.setCanceled(true);event.setCancellationResult(InteractionResult.FAIL);
  if(!(event.getEntity() instanceof ServerPlayer player))return true;
  if(!player.isAlive()||player.isSpectator()||event.getLevel()!=player.level())return true;
  CompoundTag saved=data(stack);if(!CompanionItemPolicy.mayPlace(owner(saved),player.getUUID())){message(player,"verity_companion.item_lifecycle.this_companion_belongs_to_another_player");return true;}
  ServerLevel level=player.serverLevel();BlockPos pos=event.getFace()==null?event.getPos().above():event.getPos().relative(event.getFace());
  if(pos.distSqr(player.blockPosition())>64||!level.hasChunkAt(pos)||!level.isInWorldBounds(pos)||!level.getWorldBorder().isWithinBounds(pos)||!player.mayUseItemAt(pos,event.getFace()==null?net.minecraft.core.Direction.UP:event.getFace(),stack))return true;
  if(saved.hasUUID(ID))for(ServerLevel loaded:player.getServer().getAllLevels())if(loaded.getEntity(saved.getUUID(ID))!=null){message(player,"verity_companion.item_lifecycle.this_companion_already_exists_in_the_loaded");return true;}
  var type=BuiltInRegistries.ENTITY_TYPE.get(BODY);if(!BODY.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type)))return true;
  Entity body=type.create(level);if(body==null)return true;
  body.moveTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5,player.getYRot(),0);
  if(!level.noCollision(body,body.getBoundingBox())||!level.getWorldBorder().isWithinBounds(body.getBoundingBox())||!level.getFluidState(pos).isEmpty()||CompanionPresence.hazard(level,pos)||CompanionPresence.hazard(level,pos.below()))return true;
  if(saved.hasUUID(ID))body.setUUID(saved.getUUID(ID));CompanionPresence.tag(player,body);
  String alias=CompanionItemPolicy.alias(saved.getString(ALIAS));if(!alias.isBlank())body.setCustomName(Component.literal(alias));
  try{body.getClass().getMethod("setVariant",String.class).invoke(body,CompanionItemPolicy.variant(saved.getString(VARIANT)));}
  catch(ReflectiveOperationException failure){message(player,"verity_companion.item_lifecycle.could_not_restore_the_companion_the_item");return true;}
  if(!level.addFreshEntity(body))return true;
  // Consume even in creative: this item represents one existing identity, rather than a spawn egg.
  stack.shrink(1);player.inventoryMenu.broadcastChanges();event.setCancellationResult(InteractionResult.SUCCESS);return true;
 }
 /** Exact copied stack, owner-only. Failed/full/offline/legacy recovery extends life instead of losing data. */
 public static void expired(ItemExpireEvent event){
  var entity=event.getEntity();ItemStack stack=entity.getItem();if(!portable(stack)||!(entity.level() instanceof ServerLevel level))return;
  event.setExtraLife(Math.max(event.getExtraLife(),1200));
  if(entity.isRemoved())return;UUID owner=owner(data(stack));if(owner==null)return;
  ServerPlayer player=level.getServer().getPlayerList().getPlayer(owner);if(player==null||!player.isAlive()||player.isSpectator())return;
  int[] capacities=new int[player.getInventory().items.size()];
  for(int i=0;i<capacities.length;i++){ItemStack slot=player.getInventory().items.get(i);capacities[i]=slot.isEmpty()?Math.min(stack.getMaxStackSize(),player.getInventory().getMaxStackSize()):ItemStack.isSameItemSameComponents(slot,stack)?Math.max(0,Math.min(slot.getMaxStackSize(),player.getInventory().getMaxStackSize())-slot.getCount()):0;}
  if(!CompanionItemPolicy.fits(stack.getCount(),capacities))return;
  ItemStack remaining=stack.copy();player.getInventory().add(remaining);
  if(remaining.isEmpty())entity.discard();else entity.setItem(remaining);
  player.inventoryMenu.broadcastChanges();
 }
}
