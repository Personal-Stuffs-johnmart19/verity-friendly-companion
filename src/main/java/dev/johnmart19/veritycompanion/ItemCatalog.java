package dev.johnmart19.veritycompanion;

import java.util.*;

/** Immutable names for actual registered items. No Minecraft/client classes or fuzzy guesses. */
public final class ItemCatalog {
 public record Entry(String id,String translationKey,String serverLabel){}
 public record Match(String id,String english,String russian,int score){}
 public record Details(String id,String english,String russian,String translationKey,String englishDescription,String russianDescription){}
 public record Page(List<Match> items,int total,int offset,int nextOffset){}
 private record Indexed(Entry entry,String english,String russian,List<String> phrases,String searchable,List<Set<String>> labelStems){}
 private static final List<String> RUSSIAN_ENDINGS=List.of("иями","ями","ами","ыми","ими","ого","его","ому","ему","ая","яя","ое","ее","ые","ие","ый","ий","ой","ую","юю","ых","их","ою","ею","ам","ям","ах","ях","ов","ев","ом","ем","а","я","ы","и","у","ю","е","о","ь");
 private final List<Indexed> items;
 private final Map<String,Details> details=new HashMap<>();
 private final Map<String,List<Indexed>> exact=new HashMap<>();private final Map<String,Set<Indexed>> starts=new HashMap<>();
 public ItemCatalog(Collection<Entry> registered,Map<String,String> english,Map<String,String> russian){
  List<Indexed> index=new ArrayList<>();Set<String> seen=new HashSet<>();
  for(Entry entry:registered){if(entry.id()==null||entry.id().equals("minecraft:air")||!seen.add(entry.id()))continue;
   String en=english.getOrDefault(entry.translationKey(),entry.serverLabel()),ru=russian.getOrDefault(entry.translationKey(),"");
   details.put(entry.id(),new Details(entry.id(),en,ru,entry.translationKey(),description(english,entry.translationKey()),description(russian,entry.translationKey())));
   LinkedHashSet<String> labels=new LinkedHashSet<>();add(labels,en);add(labels,ru);add(labels,entry.serverLabel());add(labels,entry.id());
   String path=entry.id().substring(entry.id().indexOf(':')+1).replace('_',' ').replace('/',' ');add(labels,path);
   // Colloquial infinite-food wording is an alias of this verified Artifacts item only.
   if(entry.id().equals("artifacts:eternal_steak"))for(String alias:List.of("бесконечный стейк","бесконечного стейка","вечного стейка","infinite steak","everlasting steak","endless steak"))add(labels,alias);
   String searchable=words(entry.id()+" "+entry.translationKey()+" "+en+" "+ru+" "+path);
   // Registered labels retain both stems for fleeting vowels: слиток/слитка, осколок/осколка.
   List<Set<String>> stems=new ArrayList<>();for(String label:Arrays.asList(en,ru,entry.serverLabel()))if(label!=null&&!label.isBlank()){Set<String> tokens=new HashSet<>();for(String token:words(label).split(" ")){String stem=russianStem(token);tokens.add(stem);if(stem.length()>4&&stem.matches("[а-я]+")&&(stem.endsWith("ок")||stem.endsWith("ец")))tokens.add(stem.substring(0,stem.length()-2)+stem.charAt(stem.length()-1));}stems.add(Set.copyOf(tokens));}
   index.add(new Indexed(entry,en,ru,List.copyOf(labels),searchable,List.copyOf(stems)));
  }items=List.copyOf(index);for(Indexed item:items)for(String label:item.phrases){exact.computeIfAbsent(label,k->new ArrayList<>()).add(item);String first=label.split(" ",2)[0];starts.computeIfAbsent(first,k->new LinkedHashSet<>()).add(item);}
 }
 private static String description(Map<String,String> locale,String key){StringJoiner out=new StringJoiner("\n");for(String suffix:List.of(".description",".desc",".tooltip")){String value=locale.get(key+suffix);if(value!=null&&!value.isBlank())out.add(value);}return out.toString();}
 public int size(){return items.size();}
 public Optional<Details> details(String id){return Optional.ofNullable(details.get(id));}
 public List<Details> details(){return details.values().stream().sorted(Comparator.comparing(Details::id)).toList();}
 /** Blank queries enumerate the real registry; nonblank pages retain the same exact-name ranking. */
 public List<Match> search(String query,int offset,int maximum){return page(query,offset,maximum).items();}
 public Page page(String query,int offset,int maximum){List<Match> matches=allMatches(query);int from=Math.clamp(offset,0,matches.size()),end=Math.min(matches.size(),from+limit(maximum));return new Page(List.copyOf(matches.subList(from,end)),matches.size(),from,end<matches.size()?end:-1);}
 public int count(String query){return allMatches(query).size();}
 public Set<String> matchingIds(String query){Set<String> result=new HashSet<>();for(Match match:allMatches(query))result.add(match.id());return Set.copyOf(result);}
 private static void add(Set<String> labels,String text){if(text!=null&&!text.isBlank()){String key=ItemHints.key(text);if(!key.isBlank())labels.add(key);}}
 private static int limit(int requested){return Math.max(1,Math.min(80,requested));}
 private static String words(String value){return ItemHints.key(value).replace('_',' ').replace(':',' ').replace('.',' ').replace('/',' ').replaceAll("\\s+"," ");}
 private static boolean phrase(String text,String label){return (" "+text+" ").contains(" "+label+" ");}
 /** Remove one known Russian inflection suffix; never delete an unknown descriptive word. */
 static String russianStem(String word){if(word.length()<4||!word.matches("[а-я]+"))return word;for(String suffix:RUSSIAN_ENDINGS)if(word.endsWith(suffix)&&word.length()-suffix.length()>=3)return word.substring(0,word.length()-suffix.length());return word;}
 private static boolean inflectedLabel(Indexed item,List<String> stems){for(Set<String> label:item.labelStems)if(label.containsAll(stems))return true;return false;}
 public List<Match> search(String query,int maximum){
  if(ItemHints.key(query).isBlank())return List.of();List<Match> matches=allMatches(query);return List.copyOf(matches.subList(0,Math.min(limit(maximum),matches.size())));
 }
 private List<Match> allMatches(String query){
  String key=ItemHints.key(query);if(key.isBlank())return items.stream().map(item->new Match(item.entry.id(),item.english,item.russian,0)).sorted(Comparator.comparing(Match::id)).toList();List<Match> found=new ArrayList<>();String[] tokens=words(key).split(" ");
  boolean russian=Arrays.stream(tokens).allMatch(token->token.matches("[а-я]+"));List<String> stems=russian?Arrays.stream(tokens).map(ItemCatalog::russianStem).toList():List.of();
  Collection<Indexed> candidates=exact.getOrDefault(key,items);
  for(Indexed item:candidates){int score=0;if(item.entry.id().equalsIgnoreCase(query.strip()))score=2000;
   else if(item.phrases.contains(key))score=1000;
   else {String haystack=item.searchable;boolean all=true;for(String token:tokens)if(!haystack.contains(token)){all=false;break;}if(all)score=100;if(russian&&inflectedLabel(item,stems))score=200;}
   if(score>0)found.add(new Match(item.entry.id(),item.english,item.russian,score));
  }found.sort(Comparator.comparingInt(Match::score).reversed().thenComparing(Match::id));return found;
 }
 /** A tied best score is ambiguous, even if a caller requests only a short display page. */
 public Optional<Match> resolveUnique(String query){List<Match> matches=search(query,2);return matches.isEmpty()||matches.size()>1&&matches.get(0).score()==matches.get(1).score()?Optional.empty():Optional.of(matches.getFirst());}
 /** Inspect original player wording; a full modified name wins over a contained generic noun. */
 public List<Match> mentioned(String original,int maximum){String text=ItemHints.key(original);if(text.isBlank())return List.of();List<Match> found=new ArrayList<>();Set<Indexed> candidates=new LinkedHashSet<>();for(String word:text.split(" "))candidates.addAll(starts.getOrDefault(word,Set.of()));
  for(Indexed item:candidates){int score=0;for(String label:item.phrases)if(phrase(text,label)){int specificity=label.split("\\s+").length*100+label.length();score=Math.max(score,specificity);}if(score>0)found.add(new Match(item.entry.id(),item.english,item.russian,score));}
  return ranked(found,maximum);
 }
 private static List<Match> ranked(List<Match> matches,int maximum){matches.sort(Comparator.comparingInt(Match::score).reversed().thenComparing(Match::id));return List.copyOf(matches.subList(0,Math.min(limit(maximum),matches.size())));}
}
