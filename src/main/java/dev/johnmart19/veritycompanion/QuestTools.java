package dev.johnmart19.veritycompanion;

import com.google.gson.JsonObject;
import java.lang.reflect.*;
import java.lang.invoke.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;

/** Optional read-only adapter verified against FTB Quests 2101.1.30 / Teams 2101.1.10.
 * Call on the server thread. No task submission, rewards, progress setters, disk writes or team creation.
 * Reflection keeps FTB optional and exposes explicitly named getters and in-memory configuration serialization.
 */
public final class QuestTools {
 private static final String Q="dev.ftb.mods.ftbquests.quest.";
 private static final java.util.concurrent.atomic.AtomicBoolean DIAGNOSTIC_LOGGED=new java.util.concurrent.atomic.AtomicBoolean();
 private static final ThreadLocal<String> LAST_API=ThreadLocal.withInitial(()->"session setup");
 private QuestTools() {}
 static String chapters(ServerPlayer player,JsonObject args){return run(player,args,"chapters");}
 static String query(ServerPlayer player,JsonObject args){return run(player,args,"query");}
 static String details(ServerPlayer player,JsonObject args){return run(player,args,"details");}
 static String next(ServerPlayer player,JsonObject args){return run(player,args,"next");}

 private static String run(ServerPlayer player,JsonObject args,String mode){
  try{
   Session s=Session.open(player,string(args,"locale",Relations.get(player).language));
   if(mode.equals("details"))return s.details(string(args,"id",""),string(args,"query",""),string(args,"section","all"),integer(args,"offset",0),integer(args,"limit",10));
   String query=string(args,"query",""),chapter=string(args,"chapter","");
   String status=mode.equals("next")?"available":string(args,"status","all");
   int offset=integer(args,"offset",0),limit=integer(args,"limit",mode.equals("next")?5:20);
   // Check filters before collecting the live view.
   QuestQuery.page(List.of(),query,chapter,status,offset,limit);
   List<QuestQuery.Row> rows=new ArrayList<>();
   Map<String,String> summaries=new HashMap<>();
   for(Object ch:list(call(s.file,"getAllChapters"))){
    if(!bool(call(ch,"isVisible",s.team)))continue;
    String chapterText=code(ch)+" "+s.title(ch);
    if(mode.equals("chapters")){
     rows.add(new QuestQuery.Row(code(ch),chapterText,s.title(ch),bool(call(s.team,"isCompleted",ch)),true,number(call(s.team,"getRelativeProgress",ch))));
     int visible=0,completed=0,available=0;
     for(Object quest:list(call(ch,"getQuests")))if(bool(call(quest,"isVisible",s.team))&&bool(call(quest,"isSearchable",s.team))) {
      visible++;if(bool(call(s.team,"isCompleted",quest)))completed++;else if(bool(call(s.team,"canStartTasks",quest)))available++;
     }
     summaries.put(code(ch)," | visible_quests="+visible+" completed_quests="+completed+" available_incomplete_quests="+available);
    }else for(Object quest:list(call(ch,"getQuests"))){
     if(!bool(call(quest,"isVisible",s.team))||!bool(call(quest,"isSearchable",s.team)))continue;
     rows.add(s.row(quest,chapterText));
    }
   }
   if(mode.equals("next"))rows.sort(Comparator.comparingInt(QuestQuery.Row::progress).reversed().thenComparing(QuestQuery.Row::chapter).thenComparing(QuestQuery.Row::id));
   QuestQuery.Page page=QuestQuery.page(rows,query,chapter,status,offset,limit);
   StringBuilder out=new StringBuilder("FTB Quests live server view; progress belongs to requesting player's FTB team. Locale="+s.locale+"; missing translations fall back to en_us, then the authored server label.\n");
   for(var row:page.rows()) {
    out.append(row.id()).append(" | ").append(QuestQuery.bounded(row.title(),240)).append(" | chapter=").append(QuestQuery.bounded(row.chapter(),240)).append(" | completed=").append(row.completed()).append(" available=").append(row.available()).append(" progress=").append(row.progress()).append('%').append(summaries.getOrDefault(row.id(),""));
    if(!mode.equals("chapters")) {
     Object quest=call(s.file,"getQuest",Long.parseUnsignedLong(row.id(),16));
     if(!bool(call(quest,"hideDetailsUntilStartable"))||row.available()) {
      out.append(" | tasks=").append(list(call(quest,"getTasks")).size()).append(" rewards=").append(list(call(quest,"getRewards")).size());
      if(mode.equals("next")) {
       int shown=0;
       for(Object task:list(call(quest,"getTasks")))if(!bool(call(s.team,"isCompleted",task))&&shown++<2)
        out.append(" | unfinished_task=").append(QuestQuery.bounded(s.title(task),180)).append(" ").append(call(s.team,"getProgress",task)).append('/').append(call(task,"getMaxProgress"));
      }
     }
    }
    out.append('\n');
   }
   out.append("Matched=").append(page.matched()).append(" offset=").append(page.offset()).append(" next=").append(page.next());
   if(mode.equals("next"))out.append("\nRecommendations are visible, incomplete quests allowed by FTB's actual canStartTasks rule. Flexible progression may allow starting before dependencies are complete. Use quest_details for required tasks, then recipe tools for crafting; no completion or reward is performed.");
   return out.toString();
  }catch(IllegalArgumentException e){throw e;}
  catch(Unavailable e){return "FTB Quests unavailable: "+e.getMessage();}
  catch(ReflectiveOperationException|LinkageError e){diagnostic(e);return "FTB Quests unavailable: installed adapter API could not be read; no progression has been inferred or changed.";}
 }

 private static void diagnostic(Throwable failure) {
  if(!DIAGNOSTIC_LOGGED.compareAndSet(false,true))return;
  // One bounded API-only diagnostic. Never log exception messages, NBT, quest text, players or arguments.
  Throwable cause=failure;for(int i=0;i<8&&cause.getCause()!=null;i++)cause=cause.getCause();
  System.err.println("[Verity quests] API read failed at "+LAST_API.get()+"; cause="+cause.getClass().getName());
  StackTraceElement[] frames=cause.getStackTrace();for(int i=0;i<Math.min(12,frames.length);i++)System.err.println("[Verity quests] at "+frames[i].getClassName()+"."+frames[i].getMethodName());
 }

 private record Session(Object file,Object team,ServerPlayer player,String locale,Object translations){
  static Session open(ServerPlayer player,String requestedLocale)throws ReflectiveOperationException{
   String locale=requestedLocale.toLowerCase(Locale.ROOT).replace('-','_');
   if(locale.equals("ru"))locale="ru_ru";if(locale.equals("en"))locale="en_us";if(locale.equals("uk"))locale="uk_ua";
   if(!locale.matches("[a-z]{2}_[a-z]{2}"))throw new IllegalArgumentException("locale must be a language code such as ru_ru or en_us");
   ClassLoader loader=QuestTools.class.getClassLoader();
   if(!QuestQuery.adapterPresent(loader))throw new Unavailable("FTB Quests mod is absent.");
   Object file=((Optional<?>)call(Class.forName(Q+"ServerQuestFile"),"getInstance")).orElseThrow(()->new Unavailable("server quest file is not loaded."));
   if(bool(call(file,"isLoading")))throw new Unavailable("server quest file is loading.");
   Object api=call(Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI"),"api");
   if(!bool(call(api,"isManagerLoaded")))throw new Unavailable("FTB Teams is not loaded.");
   Object manager=call(api,"getManager");
   Object team=((Optional<?>)call(manager,"getTeamForPlayerID",player.getUUID())).orElseThrow(()->new Unavailable("requesting player has no loaded FTB team."));
   // Explicit nullable lookup: never getOrCreateTeamData, and never accept a model-supplied player/team ID.
   Object data=call(file,"getNullableTeamData",call(team,"getId"));
   if(data==null)throw new Unavailable("requesting team's quest progress is not loaded.");
   if(!bool(call(file,"isPlayerOnTeam",player,data)))throw new Unavailable("requesting player's team could not be verified.");
   return new Session(file,data,player,locale,call(file,"getTranslationManager"));
  }
  Object translationKey(String name)throws ReflectiveOperationException{return Class.forName(Q+"translation.TranslationKey").getField(name).get(null);}
  String translated(Object object,String key)throws ReflectiveOperationException{
   Object kind=translationKey(key);
   Optional<?> value=(Optional<?>)call(translations,"getStringTranslation",object,locale,kind);
   if(value.isEmpty()&&!locale.equals("en_us"))value=(Optional<?>)call(translations,"getStringTranslation",object,"en_us",kind);
   return value.map(Object::toString).orElse("");
  }
  String title(Object object)throws ReflectiveOperationException{
   String translated=translated(object,"TITLE");return translated.isBlank()?text(call(object,"getTitle")):translated;
  }
  List<?> description(Object object)throws ReflectiveOperationException{
   Object key=translationKey("QUEST_DESC");
   Optional<?> value=(Optional<?>)call(translations,"getStringListTranslation",object,locale,key);
   if(value.isEmpty()&&!locale.equals("en_us"))value=(Optional<?>)call(translations,"getStringListTranslation",object,"en_us",key);
   // getDescription/getSubtitle are client-only and stripped on dedicated servers.
   return value.isPresent()?(List<?>)value.get():list(call(object,"getRawDescription"));
  }
  QuestQuery.Row row(Object quest,String chapter)throws ReflectiveOperationException{
   boolean completed=bool(call(team,"isCompleted",quest));
   // FTB itself handles dependencies, FLEXIBLE progression, exclusions and repeat cooldowns.
   boolean available=bool(call(team,"canStartTasks",quest));
   return new QuestQuery.Row(code(quest),chapter,title(quest),completed,available,number(call(team,"getRelativeProgress",quest)));
  }
  String details(String id,String query,String section,int offset,int limit)throws ReflectiveOperationException{
   QuestDetailPage.page(List.of(),section,offset,limit);
   Object quest;
   if(id.isBlank()) {
    if(query.isBlank()||query.length()>160)throw new IllegalArgumentException("Supply id from quest_query or a quest name in query (1..160 characters)");
    List<Object> matches=new ArrayList<>(),exact=new ArrayList<>();
    for(Object chapter:list(call(file,"getAllChapters")))if(bool(call(chapter,"isVisible",team)))for(Object candidate:list(call(chapter,"getQuests"))) {
     if(!bool(call(candidate,"isVisible",team))||!bool(call(candidate,"isSearchable",team)))continue;
     String name=title(candidate);
     if(name.equalsIgnoreCase(query))exact.add(candidate);
     if(name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))matches.add(candidate);
    }
    if(!exact.isEmpty())matches=exact;
    if(matches.isEmpty())return "No visible quest matches this name. Search quest_query by another title or chapter.";
    if(matches.size()!=1)return "Quest name is ambiguous ("+matches.size()+" matches). Use quest_query to select a title and its internal id.";
    quest=matches.getFirst();
   } else {
    if(!id.matches("[0-9a-fA-F]{1,16}"))throw new IllegalArgumentException("id must be an FTB hexadecimal quest ID from quest_query");
    quest=call(file,"getQuest",Long.parseUnsignedLong(id,16));
   }
   if(quest==null||!bool(call(quest,"isVisible",team))||!bool(call(call(quest,"getChapter"),"isVisible",team)))return "Quest not found or not visible for requesting player's team.";
   var row=row(quest,title(call(quest,"getChapter")));
   StringBuilder out=new StringBuilder("Quest "+row.id()+" | "+QuestQuery.bounded(row.title(),240)+" | chapter="+QuestQuery.bounded(row.chapter(),240)+"\ncompleted="+row.completed()+" available="+row.available()+" progress="+row.progress()+"% locale="+locale+" (en_us/authored fallback for missing translations)\n");
   if(bool(call(quest,"hideDetailsUntilStartable"))&&!bool(call(team,"canStartTasks",quest)))return out+"Details hidden by this quest's start rules.";
   List<QuestDetailPage.Line> sections=new ArrayList<>();
   sections.add(new QuestDetailPage.Line("text","Title: "+row.title()));
   sections.add(new QuestDetailPage.Line("text","Chapter: "+row.chapter()));
   boolean hideText=bool(call(call(quest,"getHideTextUntilComplete"),"get",bool(call(call(quest,"getChapter"),"isHideTextUntilComplete"))));
   if(hideText&&!row.completed())sections.add(new QuestDetailPage.Line("text","Description/subtitle hidden by quest settings until completion."));
   else{
    String subtitle=translated(quest,"QUEST_SUBTITLE");
    sections.add(new QuestDetailPage.Line("text","Subtitle: "+(subtitle.isBlank()?text(call(quest,"getRawSubtitle")):subtitle)));
    for(Object line:description(quest))sections.add(new QuestDetailPage.Line("text","Description: "+text(line)));
   }
   sections.add(new QuestDetailPage.Line("requirements","Requirements: minimum_dependencies="+call(quest,"getMinRequiredDependencies")+" sequential_tasks="+call(quest,"getRequireSequentialTasks")+" progression_mode="+call(quest,"getProgressionMode")+" dependencies_complete="+call(team,"areDependenciesComplete",quest)+" optional="+call(quest,"isOptional")+" repeat_cooldown_ticks="+call(quest,"getRepeatCooldown")));
   try(Stream<?> deps=(Stream<?>)call(quest,"streamDependencies")){
    for(Object dep:deps.toList())sections.add(new QuestDetailPage.Line("requirements",bool(call(dep,"isVisible",team))?"Dependency "+code(dep)+" | "+title(dep)+" | completed="+call(team,"isCompleted",dep):"Dependency hidden by quest visibility rules"));
   }
   for(Object task:list(call(quest,"getTasks"))){
    String line="Task "+code(task)+" | "+title(task)+" | type="+task.getClass().getSimpleName()+" | progress="+call(team,"getProgress",task)+"/"+call(task,"getMaxProgress")+" completed="+call(team,"isCompleted",task)+" consumes_resources="+call(task,"consumesResources");
    if(Class.forName(Q+"task.ItemTask").isInstance(task))line+=" | required_item="+item(call(task,"getItemStack"))+" only_from_crafting="+call(task,"isOnlyFromCrafting");
    sections.add(new QuestDetailPage.Line("tasks",line));
    definition(sections,"tasks","Task "+code(task),task);
   }
   for(Object reward:list(call(quest,"getRewards"))){
    String line="Reward "+code(reward)+" | "+title(reward)+" | type="+reward.getClass().getSimpleName()+" | claimed_by_requesting_player="+call(team,"isRewardClaimed",player.getUUID(),reward);
    if(Class.forName(Q+"reward.ItemReward").isInstance(reward))line+=" | item="+item(call(reward,"getItem"))+" count="+call(reward,"getCount");
    sections.add(new QuestDetailPage.Line("rewards",line+" | team_reward="+call(reward,"isTeamReward")+" auto_claim="+call(reward,"getAutoClaimType")));
    definition(sections,"rewards","Reward "+code(reward),reward);
    if(Class.forName(Q+"reward.RandomReward").isInstance(reward)||Class.forName(Q+"reward.ChoiceReward").isInstance(reward)||Class.forName(Q+"reward.AllTableReward").isInstance(reward)) {
     Object table=call(reward,"getTable");
     if(table!=null) {
      sections.add(new QuestDetailPage.Line("rewards","Reward table: "+title(table)+" | parent_type="+reward.getClass().getSimpleName()+"; interpret entries by this type: AllTableReward grants all, ChoiceReward offers a selection, RandomReward selects by weight. Nothing granted by this read."));
      for(Object weighted:list(call(table,"getWeightedRewards"))) {
       Object entry=call(weighted,"getReward");
       sections.add(new QuestDetailPage.Line("rewards","Table entry: "+title(entry)+" weight="+call(weighted,"getWeight")));
       definition(sections,"rewards","Table entry "+code(entry),entry);
      }
     }
    }
   }
   QuestDetailPage.Page page=QuestDetailPage.page(sections,section,offset,limit);
   for(String line:page.rows())out.append(line).append('\n');
   return out+"Detail_rows="+page.matched()+" section="+section+" offset="+page.offset()+" next="+page.next()+". Continue with the same id, locale, section and next offset until -1; long lines continue in the next chunk without text loss. Configuration fields are authored requirements/rewards, not actions. Read-only; nothing submitted, completed or claimed.";
  }
  void definition(List<QuestDetailPage.Line> sections,String section,String label,Object object)throws ReflectiveOperationException {
   // FTB's public serializer only populates this fresh in-memory tag. No file save or progress API is invoked.
   CompoundTag tag=new CompoundTag();
   call(object,"writeData",tag,player.registryAccess());
   appendTag(sections,section,label+" config",tag);
  }
 }

 private static void appendTag(List<QuestDetailPage.Line> rows,String section,String path,Tag tag) {
  if(tag instanceof CompoundTag compound&&!compound.isEmpty()) {
   for(String key:new TreeSet<>(compound.getAllKeys()))appendTag(rows,section,path+"."+key,compound.get(key));
  } else if(tag instanceof ListTag list&&!list.isEmpty()) {
   for(int i=0;i<list.size();i++)appendTag(rows,section,path+"["+i+"]",list.get(i));
  } else rows.add(new QuestDetailPage.Line(section,path+"="+tag));
 }

 private static String item(Object value){ItemStack stack=(ItemStack)value;return stack.isEmpty()?"empty":BuiltInRegistries.ITEM.getKey(stack.getItem())+" x"+stack.getCount()+" "+stack.getHoverName().getString();}
 private static String code(Object object)throws ReflectiveOperationException{return (String)call(object,"getCodeString");}
 private static String text(Object component){return component instanceof Component c?c.getString():String.valueOf(component);}
 private static List<?> list(Object value){return List.copyOf((Collection<?>)value);}
 private static int number(Object value){return ((Number)value).intValue();}
 private static boolean bool(Object value){return Boolean.TRUE.equals(value);}
 private static String string(JsonObject a,String name,String fallback){try{return a.has(name)?a.get(name).getAsString():fallback;}catch(RuntimeException e){throw new IllegalArgumentException("Invalid "+name);}}
 private static int integer(JsonObject a,String name,int fallback){try{return a.has(name)?a.get(name).getAsInt():fallback;}catch(RuntimeException e){throw new IllegalArgumentException("Invalid "+name);}}
 private record MethodKey(Class<?> owner,String name) {}
 private static final Map<MethodKey,MethodHandle> METHODS=new ConcurrentHashMap<>();
 /** Exact descriptors avoid enumerating FTB methods with client-only parameter types on a dedicated server.
  * Only source-owned literal names below are allowed; caller input never controls reflection.
  */
 private static Object call(Object target,String name,Object... args)throws ReflectiveOperationException{
  Class<?> type=target instanceof Class<?> c?c:target.getClass();
  LAST_API.set(type.getName()+"."+name);
  MethodKey key=new MethodKey(type,name);MethodHandle handle=METHODS.get(key);
  if(handle==null){
   String quest="Ldev/ftb/mods/ftbquests/quest/Quest;",object="Ldev/ftb/mods/ftbquests/quest/QuestObject;",base="Ldev/ftb/mods/ftbquests/quest/QuestObjectBase;",team="Ldev/ftb/mods/ftbquests/quest/TeamData;",task="Ldev/ftb/mods/ftbquests/quest/task/Task;",component="Lnet/minecraft/network/chat/Component;",translationKey="Ldev/ftb/mods/ftbquests/quest/translation/TranslationKey;";
   String descriptor=switch(name){
    case "getInstance"->"()Ljava/util/Optional;";
    case "api"->"()Ldev/ftb/mods/ftbteams/api/FTBTeamsAPI$API;";
    case "getManager"->"()Ldev/ftb/mods/ftbteams/api/TeamManager;";
    case "getTeamForPlayerID"->"(Ljava/util/UUID;)Ljava/util/Optional;";
    case "getId"->"()Ljava/util/UUID;";
    case "getNullableTeamData"->"(Ljava/util/UUID;)"+team;
    case "isPlayerOnTeam"->"(Lnet/minecraft/world/entity/player/Player;"+team+")Z";
    case "getQuest"->"(J)"+quest;
    case "getAllChapters","getQuests","getRawDescription"->"()Ljava/util/List;";
    case "getTasks","getRewards"->"()Ljava/util/Collection;";
    case "isVisible","isSearchable"->"("+team+")Z";
    case "isCompleted"->"("+object+")Z";
    case "areDependenciesComplete","canStartTasks"->"("+quest+")Z";
    case "getRelativeProgress"->"("+object+")I";
    case "getProgress"->"("+task+")J";
    case "getTitle"->"()"+component;
    case "getCodeString","getRawSubtitle"->"()Ljava/lang/String;";
    case "getChapter"->"()Ldev/ftb/mods/ftbquests/quest/Chapter;";
    case "getProgressionMode"->"()Ldev/ftb/mods/ftbquests/quest/ProgressionMode;";
    case "getHideTextUntilComplete"->"()Ldev/ftb/mods/ftblibrary/config/Tristate;";
    case "get"->"(Z)Z";
    case "getMinRequiredDependencies","getCount"->"()I";
    case "getMaxProgress"->"()J";
    case "writeData"->"(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V";
    case "getTable"->"()Ldev/ftb/mods/ftbquests/quest/loot/RewardTable;";
    case "getWeightedRewards"->"()Ljava/util/List;";
    case "getReward"->"()Ldev/ftb/mods/ftbquests/quest/reward/Reward;";
    case "getWeight"->"()F";
    case "getAutoClaimType"->"()Ldev/ftb/mods/ftbquests/quest/reward/RewardAutoClaim;";
    case "getRepeatCooldown"->"()I";
    case "isOptional","isTeamReward"->"()Z";
    case "streamDependencies"->"()Ljava/util/stream/Stream;";
    case "getItemStack","getItem"->"()Lnet/minecraft/world/item/ItemStack;";
    case "isRewardClaimed"->"(Ljava/util/UUID;Ldev/ftb/mods/ftbquests/quest/reward/Reward;)Z";
    case "getTranslationManager"->"()Ldev/ftb/mods/ftbquests/quest/translation/TranslationManager;";
    case "getStringTranslation","getStringListTranslation"->"("+base+"Ljava/lang/String;"+translationKey+")Ljava/util/Optional;";
    case "isLoading","isManagerLoaded","hideDetailsUntilStartable","isHideTextUntilComplete","getRequireSequentialTasks","consumesResources","isOnlyFromCrafting"->"()Z";
    default->throw new NoSuchMethodException("Unsupported quest getter");
   };
   MethodType signature=MethodType.fromMethodDescriptorString(descriptor,QuestTools.class.getClassLoader());
   handle=target instanceof Class<?>?MethodHandles.publicLookup().findStatic(type,name,signature):MethodHandles.publicLookup().findVirtual(type,name,signature);
   METHODS.put(key,handle);
  }
  List<Object> values=new ArrayList<>();if(!(target instanceof Class<?>))values.add(target);Collections.addAll(values,args);
  try{return handle.invokeWithArguments(values);}catch(Throwable e){throw new InvocationTargetException(e);}
 }
 private static final class Unavailable extends RuntimeException{Unavailable(String message){super(message);}}
}
