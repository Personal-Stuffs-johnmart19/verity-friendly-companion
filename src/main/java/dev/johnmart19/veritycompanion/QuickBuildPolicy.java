package dev.johnmart19.veritycompanion;
/** Only completely understood simple new-house requests take the immediate template path. */
final class QuickBuildPolicy {
 static boolean simple(String text){String s=BuildLanguage.normalized(text).replaceFirst("^(?:пожалуйста|please)[, ]+","");
  return s.matches("(?:построй|постройте|создай|сделай) (?:мне )?(?:любой |новый |небольшой |красивый |уютный )?(?:дом|домик|мастерскую)(?: (?:из|в стиле) create(?: мода)?)?(?: (?:рядом|неподалеку)(?: (?:со мной|с тобой|с собой|со мной))?)?[.! ]*")
   ||s.matches("(?:build|construct|make) (?:me )?(?:a |any |new |small |nice |cozy |beautiful )*(?:house|home|cottage|workshop)(?: (?:in create style|with create))?(?: (?:near me|near you|nearby))?[.! ]*");
 }
}
