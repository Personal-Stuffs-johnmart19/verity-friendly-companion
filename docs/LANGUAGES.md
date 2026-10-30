# Languages and translation

Beta.21 development uses English for source comments, system instructions and
project documentation. Player-facing messages live in standard Minecraft JSON
language files under `assets/verity_companion/lang/`; English (`en_us`) is the
fallback. Translations do not replace registered item IDs, command literals,
model names, voice names or configuration keys.

## Independent selections

- `/companion language <code|auto>` selects the companion/interface language.
- `/llm language <code|auto|inherit>` selects the reply language independently.
- `/tts language <code|auto|inherit>` selects speech pronunciation independently.
- `/voice language <code|auto|inherit>` selects microphone recognition independently.

These preferences belong to the requesting player. `inherit` uses the companion
reply language for TTS, and the host's recognition settings for microphone input.
`auto` keeps recognition automatic and lets speech follow the text. Explicit
reply-language selection remains sticky when messages contain foreign mod names.
Automatic companion/reply selection follows a supported Minecraft client locale;
without one, script hints and the previous language provide a bounded fallback.
The command suggestions list shipped language codes. Language changes do not
enable microphone uploads, change the server key, switch providers or grant
operator permissions. Provider/model and global server controls remain separate.

TTS reads the reply text: choosing a pronunciation language does not translate
that text. Select the LLM reply language to obtain answers in another language.
There is no extra translation API request per reply.

## Included interface translations

English, Russian, German, French, Spanish, Italian, Portuguese (Brazil), Dutch,
Polish, Czech, Slovak, Romanian, Hungarian, Danish, Finnish, Swedish, Norwegian
Bokmal, Greek, Turkish, Arabic, Hebrew, Hindi, Indonesian, Malay, Vietnamese,
Simplified Chinese, Traditional Chinese, Japanese, Korean, Bulgarian, Croatian
and Serbian.

## Speech engine capabilities

Google's [Gemini TTS language table](https://ai.google.dev/gemini-api/docs/speech-generation#supported-languages)
was checked on 2026-10-30. It lists over 130 languages for Gemini 3.8 Flash TTS
and over 100 for Flash-Lite. All included interface languages appear in the Flash
TTS table; coverage differs for fallback models. In particular, Finnish and
Swedish are not listed for 3.8 Flash-Lite. Older preview models also have smaller
language sets. Unsupported fallback models are skipped rather than changing the
requested language. Current access and quotas still depend on the Google project.

The translated interface is a subset of Google's broader language coverage.
Languages outside the shipped catalog are not advertised as translated.
Language coverage does not establish a voice's accent or pronunciation quality;
those require listening tests with the selected model and voice.

Optional local engines retain their actual language limits. Silero's prepared
profiles support Russian and English; Qwen ASR and Whisper have different sets.
The server reports unsupported selections instead of silently using Russian or
English. Local TTS auto mode can distinguish supported scripts but cannot reliably
identify the language of Latin-script text; use an explicit language when in doubt.
Local engine support does not install missing models automatically.

## Contributing a translation

Use `en_us.json` as the key/template reference. Keep all keys and format
placeholders (`%s`, `%1$s`, `%d`, `%%`) intact and preserve command/configuration
literals. Save UTF-8 JSON without duplicate keys. Minecraft resource packs can
override client labels normally. Dedicated-server reply formatting loads the
bundled language files; replacing those requires a rebuilt matching addon JAR.

Interface strings are separate from the English LLM protocol and from the
human-intent vocabulary. Translating a button does not authorize actions. All
mutations still require an actual player request and the normal server checks.
