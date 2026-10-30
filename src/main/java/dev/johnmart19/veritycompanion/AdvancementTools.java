package dev.johnmart19.veritycompanion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.*;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Read-only gameplay advancement adapter. Call on the logical server thread. */
public final class AdvancementTools {
 private AdvancementTools() {}
 private static final int MAX_EVENTS = 32, MAX_PLAYERS = 128;
 private static final long RECENT_MILLIS = 30 * 60 * 1000L;
 private static final Map<UUID,Deque<Earned>> RECENT = new LinkedHashMap<>();
 record Earned(String id, long at) {}
 record Snapshot(String id, String title, String description, String parent, boolean parentDone,
                 boolean hidden, boolean done, Map<String,Instant> criteria, List<List<String>> requirements) {
  boolean visible() { return done || (parent == null ? !hidden : parentDone); }
  boolean available() { return !done && visible(); }
  Instant first() { return criteria.values().stream().filter(Objects::nonNull).min(Instant::compareTo).orElse(null); }
  Instant latest() { return criteria.values().stream().filter(Objects::nonNull).max(Instant::compareTo).orElse(null); }
  // Each requirement group is OR; the groups themselves are AND. Extra optional criteria
  // obtained after completion must not move the original completion timestamp forward.
  Instant completedAt() {
   if (!done || requirements.isEmpty()) return null;
   Instant latestGroup = null;
   for (var group : requirements) {
    Instant firstInGroup = group.stream().map(criteria::get).filter(Objects::nonNull).min(Instant::compareTo).orElse(null);
    if (firstInGroup == null) return null;
    if (latestGroup == null || firstInGroup.isAfter(latestGroup)) latestGroup = firstInGroup;
   }
   return latestGroup;
  }
 }

 public static void onEarn(AdvancementEvent.AdvancementEarnEvent event) {
  if (!(event.getEntity() instanceof ServerPlayer player) || event.getAdvancement().value().display().isEmpty()) return;
  remember(player.getUUID(), event.getAdvancement().id().toString(), System.currentTimeMillis());
 }
 public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
  RECENT.remove(event.getEntity().getUUID());
 }
 static void remember(UUID player, String id, long now) {
  RECENT.entrySet().removeIf(e -> e.getValue().isEmpty() || now - e.getValue().peekLast().at() > RECENT_MILLIS);
  if (!RECENT.containsKey(player) && RECENT.size() >= MAX_PLAYERS) RECENT.remove(RECENT.keySet().iterator().next());
  var events = RECENT.computeIfAbsent(player, ignored -> new ArrayDeque<>());
  events.addLast(new Earned(id,now));
  while (events.size() > MAX_EVENTS) events.removeFirst();
 }
 static Map<String,Long> recent(UUID player, long now) {
  Map<String,Long> out = new HashMap<>();
  var events = RECENT.get(player);
  if (events != null) for (var event : events) if (now - event.at() <= RECENT_MILLIS) out.put(event.id(),event.at());
  return out;
 }

 public static String view(ServerPlayer player, JsonObject args) {
  var manager = player.server.getAdvancements();
  var progress = player.getAdvancements();
  List<Snapshot> rows = new ArrayList<>();
  for (AdvancementHolder holder : manager.getAllAdvancements()) {
   var advancement = holder.value();
   // Recipe unlocks and internal trigger-only advancements have no player-facing display.
   if (advancement.display().isEmpty()) continue;
   var display = advancement.display().orElseThrow();
   var actual = progress.getOrStartProgress(holder);
   String parent = advancement.parent().map(Object::toString).orElse(null);
   var parentHolder = advancement.parent().map(manager::get).orElse(null);
   boolean parentDone = parentHolder != null && progress.getOrStartProgress(parentHolder).isDone();
   Map<String,Instant> criteria = new TreeMap<>();
   for (String name : advancement.criteria().keySet()) {
    var criterion = actual.getCriterion(name);
    criteria.put(name, criterion == null ? null : criterion.getObtained());
   }
   rows.add(new Snapshot(holder.id().toString(),display.getTitle().getString(),display.getDescription().getString(),
       parent,parentDone,display.isHidden(),actual.isDone(),criteria,advancement.requirements().requirements()));
  }
  return render(rows,args,recent(player.getUUID(),System.currentTimeMillis())).toString();
 }

 /** Pure snapshot policy/formatting seam, also used by the no-bootstrap regression tests. */
 static JsonObject render(List<Snapshot> snapshots, JsonObject args, Map<String,Long> earned) {
  String status = string(args,"status","completed").toLowerCase(Locale.ROOT);
  if (!Set.of("completed","available","all","recent").contains(status)) throw new IllegalArgumentException("status: completed, available, all or recent");
  String id = string(args,"id","");
  String query = string(args,"query","").toLowerCase(Locale.ROOT);
  if (id.length() > 256 || query.length() > 160) throw new IllegalArgumentException("Advancement id/query too long");
  int offset = bounded(args,"offset",0,0,100000), limit = bounded(args,"limit",12,1,20);
  int criterionOffset = bounded(args,"criterion_offset",0,0,100000);
  var filtered = snapshots.stream().filter(Snapshot::visible)
      .filter(s -> id.isEmpty() || s.id().equals(id))
      // An explicit id selects detail regardless of the list's default status.
      .filter(s -> !id.isEmpty() || switch(status) {case "completed" -> s.done(); case "available" -> s.available(); case "recent" -> s.done() && earned.containsKey(s.id()); default -> true;})
      .filter(s -> (s.id()+" "+s.title()+" "+s.description()).toLowerCase(Locale.ROOT).contains(query))
      .sorted(status.equals("recent") ? Comparator.<Snapshot>comparingLong(s -> earned.getOrDefault(s.id(),0L)).reversed().thenComparing(Snapshot::id)
          : Comparator.<Snapshot,Instant>comparing(Snapshot::completedAt,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Snapshot::id))
      .toList();
  JsonObject out = new JsonObject();
  out.addProperty("source","requesting player's server advancement progress");
  out.addProperty("status",status); out.addProperty("matched",filtered.size()); out.addProperty("offset",offset);
  int end = Math.min(filtered.size(),offset+limit);
  out.addProperty("next",end < filtered.size() ? end : -1);
  out.addProperty("availability_note","Available means an unfinished visible root or an advancement whose parent is completed; trigger conditions may still require other gameplay. Hidden locked entries and internal recipe unlocks are omitted. This is not the FTB Quests task book.");
  if (status.equals("recent")) out.addProperty("recent_note","Observed earn events from this online session, retained for 30 minutes (last 32); empty does not prove nothing was earned before observation. Completed lists use saved criterion dates.");
  JsonArray entries = new JsonArray();
  for (int i=offset;i<end;i++) entries.add(format(filtered.get(i),!id.isEmpty(),criterionOffset,earned));
  out.add("advancements",entries);
  return out;
 }
 private static JsonObject format(Snapshot s, boolean detail, int criterionOffset, Map<String,Long> earned) {
  JsonObject row = new JsonObject();
  row.addProperty("id",s.id()); row.addProperty("title",clip(s.title(),160)); row.addProperty("description",clip(s.description(),480));
  row.addProperty("completed",s.done()); row.addProperty("available",s.available()); row.addProperty("parent",s.parent());
  row.addProperty("criteria_completed",s.criteria().values().stream().filter(Objects::nonNull).count()); row.addProperty("criteria_total",s.criteria().size());
  row.addProperty("requirement_groups_completed",s.requirements().stream().filter(group -> group.stream().anyMatch(k -> s.criteria().get(k)!=null)).count());
  row.addProperty("requirement_groups_total",s.requirements().size());
  row.addProperty("first_progress_at",iso(s.first())); row.addProperty("latest_criterion_at",iso(s.latest())); row.addProperty("completed_at",iso(s.completedAt()));
  if (earned.containsKey(s.id())) row.addProperty("observed_earned_at",Instant.ofEpochMilli(earned.get(s.id())).toString());
  if (detail) {
   JsonArray criteria = new JsonArray();
   var names = s.criteria().keySet().stream().sorted().toList();
   int end = Math.min(names.size(),criterionOffset+40);
   for(int i=criterionOffset;i<end;i++) {
    String name = names.get(i); JsonObject criterion = new JsonObject();
    criterion.addProperty("name",clip(name,200)); criterion.addProperty("completed",s.criteria().get(name)!=null);
    criterion.addProperty("obtained_at",iso(s.criteria().get(name))); criteria.add(criterion);
   }
   row.add("criteria",criteria); row.addProperty("criterion_offset",criterionOffset); row.addProperty("criteria_next",end < names.size() ? end : -1);
   row.addProperty("requirements_note","All requirement groups must be satisfied; each group accepts any one listed criterion. Criteria counts alone are not completion percentages.");
   // Page requirement groups separately with the same bounded offset, preventing huge modded payloads.
   JsonArray groups = new JsonArray(); int groupsEnd = Math.min(s.requirements().size(),criterionOffset+40), optionBudget = 40;
   for(int i=criterionOffset;i<groupsEnd;i++) {
    JsonObject group = new JsonObject(); group.addProperty("index",i);
    var members = s.requirements().get(i); JsonArray options = new JsonArray();
    int shown = Math.min(optionBudget,members.size());
    members.stream().limit(shown).forEach(name -> options.add(clip(name,200))); optionBudget -= shown;
    group.add("any_of",options); group.addProperty("options_total",members.size()); group.addProperty("options_truncated",members.size()>shown);
    group.addProperty("completed",members.stream().anyMatch(name -> s.criteria().get(name)!=null)); groups.add(group);
   }
   row.add("requirement_groups",groups); row.addProperty("requirement_groups_next",groupsEnd < s.requirements().size() ? groupsEnd : -1);
  }
  return row;
 }
 private static String iso(Instant value) { return value == null ? null : value.toString(); }
 private static String clip(String value,int length) { return value.length() <= length ? value : value.substring(0,length)+"…"; }
 private static String string(JsonObject args,String key,String fallback) {
  if (!args.has(key)) return fallback;
  if (!args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException(key+" must be a string");
  return args.get(key).getAsString();
 }
 private static int bounded(JsonObject args,String key,int fallback,int min,int max) {
  if (!args.has(key)) return fallback;
  try {
   var value=args.get(key);
   if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new NumberFormatException();
   int number=value.getAsBigDecimal().intValueExact();
   if(number<min || number>max) throw new NumberFormatException();
   return number;
  } catch (ArithmeticException | NumberFormatException bad) { throw new IllegalArgumentException(key+" must be an integer "+min+".."+max); }
 }
}
