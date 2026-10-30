package dev.johnmart19.veritycompanion;

import java.util.*;

/** Shipped UI languages; provider/model capabilities are checked separately. */
final class Languages {
 private record Language(String code,String locale,String name) {}
 private static final List<Language> VALUES=List.of(
  new Language("en","en_us","English"),new Language("ru","ru_ru","Russian"),
  new Language("de","de_de","German"),new Language("fr","fr_fr","French"),new Language("es","es_es","Spanish"),new Language("it","it_it","Italian"),
  new Language("pt","pt_br","Portuguese"),new Language("nl","nl_nl","Dutch"),new Language("pl","pl_pl","Polish"),new Language("cs","cs_cz","Czech"),
  new Language("sk","sk_sk","Slovak"),new Language("ro","ro_ro","Romanian"),new Language("hu","hu_hu","Hungarian"),new Language("da","da_dk","Danish"),
  new Language("fi","fi_fi","Finnish"),new Language("sv","sv_se","Swedish"),new Language("nb","no_no","Norwegian"),new Language("el","el_gr","Greek"),
  new Language("tr","tr_tr","Turkish"),new Language("ar","ar_sa","Arabic"),new Language("he","he_il","Hebrew"),new Language("hi","hi_in","Hindi"),
  new Language("id","id_id","Indonesian"),new Language("ms","ms_my","Malay"),new Language("vi","vi_vn","Vietnamese"),new Language("zh","zh_cn","Chinese (Simplified)"),
  new Language("zh_tw","zh_tw","Chinese (Traditional)"),new Language("ja","ja_jp","Japanese"),new Language("ko","ko_kr","Korean"),
  new Language("bg","bg_bg","Bulgarian"),new Language("hr","hr_hr","Croatian"),new Language("sr","sr_sp","Serbian"));
 private static final Map<String,Language> BY_CODE=new LinkedHashMap<>();
 static {for(Language language:VALUES)BY_CODE.put(language.code,language);}
 private Languages() {}
 static List<String> codes(){return VALUES.stream().map(Language::code).toList();}
 static String normalize(String value){return normalize(value,false);}
 static String normalize(String value,boolean allowAuto){
  if(value==null||value.length()>35)throw new IllegalArgumentException("Unsupported language");
  String code=value.strip().toLowerCase(Locale.ROOT).replace('-','_');
  if(allowAuto&&code.equals("auto"))return code;
  if(code.equals("no")||code.startsWith("no_")||code.equals("nb_no"))return "nb";
  if(code.equals("iw")||code.startsWith("iw_"))return "he";
  if(code.equals("in")||code.startsWith("in_"))return "id";
  if(Set.of("zh_tw","zh_hk","zh_hant","zh_hant_tw","zh_hant_hk").contains(code))return "zh_tw";
  if(BY_CODE.containsKey(code))return code;
  for(Language language:VALUES)if(language.locale.equals(code))return language.code;
  if(code.matches("[a-z]{2,3}_[a-z0-9]{2,8}")){String base=code.substring(0,code.indexOf('_'));if(BY_CODE.containsKey(base))return base;}
  throw new IllegalArgumentException("Unsupported language");
 }
 static String mode(String value,boolean inherit){String mode=value==null?(inherit?"inherit":"auto"):value.strip().toLowerCase(Locale.ROOT);return inherit&&mode.equals("inherit")?mode:normalize(mode,true);}
 static String locale(String value){try{return BY_CODE.get(normalize(value)).locale;}catch(IllegalArgumentException invalid){return "en_us";}}
 static String englishName(String value){return BY_CODE.get(normalize(value)).name;}
 static String name(String value){return englishName(value);}
 static String fromClientLocale(String locale){try{return normalize(locale);}catch(IllegalArgumentException invalid){return "en";}}
 static boolean knownClientLocale(String locale){try{normalize(locale);return true;}catch(IllegalArgumentException invalid){return false;}}
}
