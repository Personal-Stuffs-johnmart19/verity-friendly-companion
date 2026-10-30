package dev.johnmart19.veritycompanion;

/** Language selection is per request/player; tool identifiers never change language. */
final class CompanionLanguage {
 private CompanionLanguage(){}
 static boolean isRussian(String language){return language!=null&&language.strip().equalsIgnoreCase("ru");}
}
