package dev.johnmart19.veritycompanion;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Ephemeral consent from human input only; never infer permission from an assistant reply. */
final class ConversationIntent {
 static final long WINDOW_MILLIS=180_000L;
 static final int MAX_PLAYERS=256;
 private record Pending(long opened,String current,boolean allowed,boolean followup,boolean ground,boolean established,boolean fulfilled) {}
 private static final Map<UUID,Pending> GIFTS=new LinkedHashMap<>();

 private static boolean live(Pending p,long now){return p!=null&&now>=p.opened()&&now-p.opened()<WINDOW_MILLIS;}
 private static void sweep(long now){GIFTS.entrySet().removeIf(e->!live(e.getValue(),now));}

 /** Call once at the human-message boundary, before starting model/tool processing. */
 static synchronized boolean begin(UUID player,String request,long now){
  sweep(now);if(player==null)return false;
  String current=ToolIntent.normalized(request);
  boolean direct=ToolIntent.gifting(request);
  Pending prior=GIFTS.get(player);
  if(live(prior,now)&&prior.established()&&ToolIntent.giftDetail(request)&&ToolIntent.hasQuantity(request))direct=false;
  boolean followup=!direct&&live(prior,now)&&!ToolIntent.excludedGiftText(request)
   &&((ToolIntent.giftAffirmation(request)&&!prior.fulfilled())||(prior.established()&&ToolIntent.giftDetail(request)));
  if(direct){
   if(!GIFTS.containsKey(player)&&GIFTS.size()>=MAX_PLAYERS)GIFTS.remove(GIFTS.keySet().iterator().next());
   GIFTS.put(player,new Pending(now,current,true,false,ToolIntent.ground(request),true,false));
  }else if(prior==null&&ToolIntent.giftDetail(request)){
   if(GIFTS.size()>=MAX_PLAYERS)GIFTS.remove(GIFTS.keySet().iterator().next());
   GIFTS.put(player,new Pending(now,current,false,false,false,false,false));
  }else if(prior!=null){
   // A declined request closes consent. Other chat cannot authorize a tool call.
   if(ToolIntent.excludedGiftText(request))GIFTS.remove(player);
   else GIFTS.put(player,new Pending(prior.opened(),current,followup,followup,prior.ground(),prior.established()||followup,followup?false:prior.fulfilled()));
  }
  return direct||followup;
 }

 /** Recheck the exact human request being processed. This method never opens a window. */
 static synchronized boolean allowsGift(UUID player,String request,long now){
  sweep(now);Pending p=GIFTS.get(player);
  return live(p,now)&&p.allowed()&&p.current().equals(ToolIntent.normalized(request));
 }

 static synchronized String context(UUID player,String request,long now){
  return context(player,request,now,"en");
 }
 static synchronized String context(UUID player,String request,long now,String language){
  if(!allowsGift(player,request,now))return "";
  Pending p=GIFTS.get(player);
  if(CompanionLanguage.isRussian(language))return (p.followup()?"Контекст сервера: игрок недавно попросил предмет/еду. Это сообщение человека продолжает просьбу; естественно уточни предмет/количество или учти подтверждение. Кодовое слово не нужно; соблюдай лимиты выдачи. ":"Контекст сервера: человек просит выдать предмет, в том числе обычной фразой с количеством. Кодовое слово не нужно. ")+(p.ground()?"Игрок попросил выдачу на землю.":"Выдай в инвентарь: на землю игрок не просил.");
  return (p.followup()?"Server context: The player recently requested an item or food gift. This human message continues that request; resolve the item/count or affirmation naturally. No special gift keyword is required. Follow existing gift limits. ":"Server context: The current human message is an ordinary item delivery request, including item-and-count wording. No special gift keyword is required. ")+(p.ground()?"The player requested delivery on the ground.":"Use inventory delivery; the player has not requested ground delivery.");
 }

 static synchronized boolean allowsGround(UUID player,String request,long now){
  return allowsGift(player,request,now)&&GIFTS.get(player).ground();
 }

 /** Delivery closes bare-yes consent for later turns, while allowing this turn's multi-item batch. */
 static synchronized void fulfilled(UUID player){Pending p=GIFTS.get(player);if(p!=null&&p.allowed())GIFTS.put(player,new Pending(p.opened(),p.current(),p.allowed(),p.followup(),p.ground(),p.established(),true));}
 static synchronized void clear(UUID player){GIFTS.remove(player);}
 static synchronized void clearAll(){GIFTS.clear();}
}
