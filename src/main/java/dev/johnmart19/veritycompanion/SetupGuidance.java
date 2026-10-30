package dev.johnmart19.veritycompanion;

/** Fixed bilingual setup hints. Provider responses/configuration are never echoed to chat. */
final class SetupGuidance {
 static String message(String code,boolean ru){return message(code,ru?"ru":"en");}
 static String message(String code,String language){return switch(code){
  case "native_stt_missing" -> ModTranslations.text(language,"verity_companion.setup_guidance.native_recognition_files_are_incomplete_host_run");
  case "drama3_missing" -> ModTranslations.text(language,"verity_companion.setup_guidance.drama_3_runtime_model_or_voice_references");
  case "stt_disabled" -> ModTranslations.text(language,"verity_companion.setup_guidance.microphone_recognition_is_disabled_on_this_server");
  case "google_audio_confirmation" -> ModTranslations.text(language,"verity_companion.setup_guidance.the_host_has_not_enabled_uploading_microphone");
  case "external_disabled" -> ModTranslations.text(language,"verity_companion.setup_guidance.external_requests_are_disabled_on_the_server");
  case "google_key_missing","google_key_invalid" -> ModTranslations.text(language,"verity_companion.setup_guidance.the_server_google_key_is_missing_invalid");
  case "google_key_rejected" -> ModTranslations.text(language,"verity_companion.setup_guidance.google_rejected_access_host_check_the_server");
  case "google_free_confirmation" -> ModTranslations.text(language,"verity_companion.setup_guidance.cloud_calls_are_disabled_confirm_the_google");
  case "tts_disabled" -> ModTranslations.text(language,"verity_companion.setup_guidance.speech_is_disabled_on_the_server_use");
  case "bridge_token_missing" -> ModTranslations.text(language,"verity_companion.setup_guidance.the_server_bridge_token_is_missing_host");
  case "bridge_auth" -> ModTranslations.text(language,"verity_companion.setup_guidance.server_bridge_authentication_failed_host_check_tokenfile");
  case "bridge_offline" -> ModTranslations.text(language,"verity_companion.setup_guidance.the_server_bridge_is_unavailable_host_run");
  default -> null;
 };}
}
