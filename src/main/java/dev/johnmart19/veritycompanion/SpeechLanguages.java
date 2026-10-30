package dev.johnmart19.veritycompanion;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.*;

/** Request language policy; provider capabilities are narrower than the UI catalog. */
final class SpeechLanguages {
 private SpeechLanguages() {}
 static final class Unavailable extends IOException {
  final String provider,language;
  Unavailable(String provider,String language){super("Selected "+provider+" does not support "+language);this.provider=provider;this.language=language;}
 }
 private static final Set<String> LEGACY_TTS=Set.of("ar","en","fr","de","hi","id","it","ja","ko","nl","pl","pt","ro","ru","es","tr","vi");
 private static final Set<String> QWEN_ASR=Set.of("zh","zh_tw","en","de","fr","es","pt","id","it","ko","ru","vi","ja","tr","hi","ms","nl","sv","da","fi","pl","cs","el","ro","hu","ar");
 // REST SpeechConfig.languageCode has a smaller allowlist than auto-detected TTS.
 private static final Map<String,String> LEGACY_CODES=Map.ofEntries(Map.entry("ar","ar-XA"),Map.entry("de","de-DE"),Map.entry("en","en-US"),Map.entry("es","es-ES"),Map.entry("fr","fr-FR"),Map.entry("hi","hi-IN"),Map.entry("id","id-ID"),Map.entry("it","it-IT"),Map.entry("ja","ja-JP"),Map.entry("ko","ko-KR"),Map.entry("nl","nl-NL"),Map.entry("pl","pl-PL"),Map.entry("pt","pt-BR"),Map.entry("ru","ru-RU"),Map.entry("tr","tr-TR"),Map.entry("vi","vi-VN"),Map.entry("zh","cmn-CN"));
 static String selected(JsonObject config,String field,String override)throws IOException {
  String value=override==null||override.isBlank()||override.equals("inherit")?ProviderHttp.text(config,field,"auto"):override;
  try{return Languages.normalize(value,true);}catch(IllegalArgumentException invalid){throw new IOException("Unsupported "+field+" selection",invalid);}
 }
 static JsonObject request(JsonObject config,String field,String override)throws IOException {JsonObject copy=config.deepCopy();copy.addProperty(field,selected(config,field,override));return copy;}
 static boolean googleTts(String model,String language){
  // Auto has no reliable language label to test against a narrower fallback.
  if(language.equals("auto"))return model.equals("gemini-3.8-flash-tts");
  // Sources: Gemini API speech-generation 3.8 language matrix; Firebase AI Logic
  // speech guide's distinct 2.x/3.x lists. Do not fall back to a different language.
  return switch(model){case "gemini-3.8-flash-tts","gemini-3.1-flash-tts-preview"->Languages.codes().contains(language);case "gemini-3.8-flash-lite-tts"->Languages.codes().contains(language)&&!Set.of("fi","sv").contains(language);case "gemini-2.5-flash-preview-tts"->LEGACY_TTS.contains(language);default->false;};
 }
 static String legacyCode(String language){return LEGACY_CODES.get(language);}
 static String name(String language){return language.equals("auto")?"the language spoken in the recording":Languages.englishName(language);}
 static String nativeAsr(String language)throws IOException {if(!language.equals("auto")&&!QWEN_ASR.contains(language))throw new Unavailable("Qwen3 ASR",language);return language.equals("zh_tw")?"zh":language;}
 static String localTts(String language,String text)throws IOException {
  if(language.equals("auto")){
   // This prepared profile has English/Russian voices only. A Latin script is
   // not language detection, but other scripts must never silently use English.
   if(text.codePoints().anyMatch(c->Character.isLetter(c)&&Character.UnicodeScript.of(c)!=Character.UnicodeScript.LATIN&&!russianLetter(c)))throw new Unavailable("local TTS","auto");
   return text.codePoints().anyMatch(SpeechLanguages::russianLetter)?"ru":"en";
  }
  if(!Set.of("ru","en").contains(language))throw new Unavailable("local TTS",language);return language;
 }
 private static boolean russianLetter(int c){return c>=0x410&&c<=0x44f||c==0x401||c==0x451;}
 static String whisper(String language){return language.equals("zh_tw")?"zh":language.equals("nb")?"no":language;}
}
