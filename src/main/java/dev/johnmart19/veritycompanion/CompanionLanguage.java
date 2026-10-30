package dev.johnmart19.veritycompanion;

/** Language selection is per request/player; tool identifiers never change language. */
final class CompanionLanguage {
 private CompanionLanguage(){}
 static boolean isRussian(String language){try{return Languages.normalize(language).equals("ru");}catch(IllegalArgumentException invalid){return false;}}
}
