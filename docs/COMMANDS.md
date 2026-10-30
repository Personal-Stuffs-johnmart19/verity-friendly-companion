# Verity Friendly Companion command reference

Complete addon reference for **1.2.0-beta.21**. Beta.20 users should
use its [matching guide](https://github.com/Personal-Stuffs-johnmart19/verity-friendly-companion/blob/v1.2.0-beta.20/docs/COMMANDS.md).

Enter commands in Minecraft chat; use **Tab** for completion. `<value>` is required,
`[value]` is optional, and `a|b` means choose one. Do not type the brackets.
Command names, identifiers and enum values stay in English in every language.
**OP** means permission level 2 or higher. Personal settings affect you; shared
model and horror controls affect the server.

## Companion and presence

| Command | Access | Description |
| --- | --- | --- |
| `/companion` | Player | Show mood, assistance availability and persona settings. |
| `/companion help` | Player | Show the short command overview. |
| `/companion setup` | OP | Check server configuration and dependencies without exposing credentials or generating a reply/voice sample. |
| `/companion find` | Player | List eligible loaded companion bodies/boxes with dimensions and coordinates. Does not search unloaded chunks. |
| `/companion come` | Player | Bring an existing eligible loaded body or box safely near you and claim it for you. Supports dimension transfer; does not create one if none is found. Useful if Verity is stuck or hidden. |
| `/companion summon` | Player | Bring an existing companion nearby, or create a body if none is found. |
| `/companion box` | Player | Create an owned introduction box if no eligible companion/box is found. Otherwise report the existing location. Opening runs the native delayed emergence and jump. |
| `/companion stay` | Player | Stop following and stop the selected companion's navigation/AI. |
| `/companion follow` | Player | Start following; same as `follow on`. Requires your owned body nearby. |
| `/companion follow on` | Player | Follow through loaded paths within 24 blocks. Does not teleport to catch up. |
| `/companion follow off` | Player | End your following session. |
| `/companion schematics` | Player | List approved schematic resources/local files. Does not build or import an internet file. |
| `/companion blueprints` | Player | Show building templates and attributed references. |
| `/companion blueprints <query>` | Player | Filter the catalog, for example `/companion blueprints workshop`. |

Following ends on disconnect, death or dimension change; enable it again when
needed. Use `come` first if the body is too far away. **Summon/box can create an
extra companion if an older one is unloaded.** Try `find` and `come` first. Other
players' owned companions are not claimed.

## Persona, faces and mood

| Command | Access | Description |
| --- | --- | --- |
| `/companion rename <name>` | Player | Set your companion name: 1–24 letters, digits, spaces, hyphens or underscores. Multiword names need no quotes. |
| `/companion gender` | Player | Show persona gender. |
| `/companion gender male\|female\|neutral` | Player | Set gender/pronouns. Does not change voice or visual model. |
| `/companion girl` | Player | Shortcut for `gender female`. |
| `/companion boy` | Player | Shortcut for `gender male`. |
| `/companion role orb\|protector\|anime` | Player | Select smiley orb, village protector or anime persona. Does not install another mesh or combat AI. |
| `/companion face <emotion>` | Player | Override the nearby body's face on your client. Does not change mood or enable horror. |
| `/companion face auto` | Player | Restore automatic expressions. Manual overrides also clear on disconnect. |
| `/companion mood` | Player | Show status; same as `/companion`. |
| `/companion mood auto` | Player | Show the bar below mood 100 while relationships are enabled. |
| `/companion mood on` | Player | Always show the bar while relationships are enabled. |
| `/companion mood off` | Player | Hide the bar without changing mood or disabling relationships. |
| `/companion mode true\|false` | OP | Enable/disable the server's relationship and mood-based assistance system. Disabling also clears rivals. Not a game-mode or building toggle. |
| `/companion reset` | OP | Reset **your** mood/relationship state and remove your rival. Keeps name, languages, persona and voice preferences. |
| `/companion retaliation true\|false` | OP | Compatibility boss control: `true` selects mood bosses and enables mood horror if horror was off; `false` switches bosses off. Prefer explicit horror controls for finer settings. |

Accepted face/emotion values:

```text
neutral happy excited curious reassuring sad serious annoyed amused laughing
mischievous smug deadpan sarcastic embarrassed surprised confused worried afraid
disgusted disappointed tired proud affectionate thoughtful skeptical angry
```

The model can choose expressions from context. Emotion changes presentation,
not relationship scores or permissions.

## Languages

All language selections are personal. They do not switch providers, enable
microphone uploads or grant OP rights.

| Command | Access | Description |
| --- | --- | --- |
| `/companion language` | Player | Show companion/interface language and mode. |
| `/companion language <code\|auto>` | Player | Select companion/interface language. `auto` follows a supported Minecraft locale, with script/previous-language fallback otherwise. |
| `/llm language` | Player | Show reply language. Does not require OP. |
| `/llm language <code\|auto\|inherit>` | Player | Select reply language independently. `inherit` follows companion language; `auto` uses automatic client-locale selection. |
| `/tts language` | Player | Show speech-output language preference. |
| `/tts language <code\|auto\|inherit>` | Player | Select pronunciation language. `inherit` follows reply language; `auto` lets the engine interpret text. Does not translate the text. |
| `/voice language` | Player | Show microphone-recognition language preference. |
| `/voice language <code\|auto\|inherit>` | Player | Select recognition language. `inherit` preserves host STT settings; `auto` requests automatic recognition. |

| Code | Language | Code | Language |
| --- | --- | --- | --- |
| `en` | English | `ru` | Russian |
| `de` | German | `fr` | French |
| `es` | Spanish | `it` | Italian |
| `pt` | Portuguese (Brazil) | `nl` | Dutch |
| `pl` | Polish | `cs` | Czech |
| `sk` | Slovak | `ro` | Romanian |
| `hu` | Hungarian | `da` | Danish |
| `fi` | Finnish | `sv` | Swedish |
| `nb` | Norwegian Bokmal | `el` | Greek |
| `tr` | Turkish | `ar` | Arabic |
| `he` | Hebrew | `hi` | Hindi |
| `id` | Indonesian | `ms` | Malay |
| `vi` | Vietnamese | `zh` | Simplified Chinese |
| `zh_tw` | Traditional Chinese | `ja` | Japanese |
| `ko` | Korean | `bg` | Bulgarian |
| `hr` | Croatian | `sr` | Serbian |

Speech support depends on engine/model. Selecting a language does not install a
model or expand its capabilities. Unsupported speech fallbacks are skipped.
See [language behavior and limits](LANGUAGES.md).

## TTS and microphone recording

| Command/control | Access | Description |
| --- | --- | --- |
| `/tts` | Player | Show TTS state, Gemini voice and speech language. |
| `/tts status` | Player | Show the cached server speech capabilities and current provider state. |
| `/tts on` | Player | Enable your voice replies when the selected provider is ready; does not change provider or quota. |
| `/tts off` | Player | Disable voice replies; keep text replies. |
| `/tts voices` | Player | List accepted Gemini voice names when configured Google TTS is selected. |
| `/tts voice <name>` | Player | Select a Gemini voice, for example `/tts voice Achird`. Does not enable TTS automatically. Local TTS uses its prepared voice profile instead. |
| `/voice` | Player | Show recording instructions and recognition-language setting. |
| `/voice status` | Player | Show the cached server recognition capabilities and current backend state. |
| `/tts provider <provider>` | OP | Select a currently available shared TTS provider: `disabled`, `google`, `drama3` or `silero`. Completion includes prepared/configured choices only. |
| `/voice backend <backend>` | OP | Select a currently available shared recognition backend: `disabled`, `google`, `faster-whisper`, `cuda`, `rocm`, `vulkan`, `native-cuda` or `native-cpu`. |
| Hold **R**, release to send | Player | Default push-to-talk. Pressing also interrupts current playback. Rebind in Controls if needed. |
| **M** | Player | Cycle microphones. Selection is saved on the client and shown on the HUD. |

Accepted Gemini voices (case-insensitive selection):

```text
Zephyr Puck Charon Kore Fenrir Leda Orus Aoede Callirrhoe Autonoe
Enceladus Iapetus Umbriel Algieba Despina Erinome Algenib Rasalgethi Laomedeia
Achernar Alnilam Schedar Gacrux Pulcherrima Achird Zubenelgenubi Vindemiatrix
Sadachbia Sadaltager Sulafat
```

The host must enable recognition. `/voice language` cannot enable Google uploads
or supply a key. See [cloud setup](CLOUD.md) and [server setup](SERVER.md).

## LLM controls

`/llm`, `/llm help` and `/llm language` are available to ordinary players. Other
controls below require OP and affect the **shared server text model**. Switching
chat backend does not switch TTS or recognition backend.

| Command | Access | Description |
| --- | --- | --- |
| `/llm` | Player | Show model-control help. |
| `/llm help` | Player | Same help as `/llm`. |
| `/llm models` | OP | Query LM Studio's current downloaded-model inventory and capabilities without selecting. |
| `/llm local` | OP | Same inventory action as `/llm models`; does not switch models. |
| `/llm local <model>` | OP | Load/select a downloaded model or variant. Use its inventory/completion ID; spaces are accepted. Reports loading progress and context. |
| `/llm cloud` | OP | Switch replies to the configured Google model. Does not enable billing, provide credentials or change audio settings. |
| `/llm status` | OP | Show provider/model, context, overrides, loading job and cached runtime capabilities; works from the server console too. |
| `/llm refresh` | OP | Refresh bounded provider metadata and prepared-file checks asynchronously. Does not load a model, generate speech or spend generation quota. |
| `/llm caps` | OP | Inspect the selected local model's advertised capabilities. Requires local mode. |
| `/llm reasoning off\|on\|low\|medium\|high` | OP | Set supported local-model reasoning. `on` maps to `low`; `off` requests none. Unsupported controls are rejected. |
| `/llm context <tokens>` | OP | Set local context: 4,096–1,048,576 tokens, bounded by model capacity. Increasing it can reload the model and increase memory use. Google manages its own context. |
| `/llm temperature <value>` | OP | Temporary sampling override, 0–2. |
| `/llm max_tokens <tokens>` | OP | Temporary output-token override, 128–4,096. Context fitting/provider limits still apply. |
| `/llm system_prompt` | OP | Show the temporary addition, not the entire core prompt. |
| `/llm system_prompt show` | OP | Same as `/llm system_prompt`. |
| `/llm system_prompt append <text>` | OP | Append instructions alongside the persona. Combined additions may contain up to 2,000 characters. |
| `/llm system_prompt <text>` | OP | Shorthand for appending. Use `append` explicitly if text starts with a reserved subcommand. |
| `/llm system_prompt clear` | OP | Clear additions while retaining core persona/tool rules. |
| `/llm reset` | OP | Clear temporary overrides and additions. Does not reset relationships, delete dialogue or switch provider/model. |

Temporary overrides last until cleared/reset or provider-runtime restart. They do
not replace core instructions or grant game permissions. Failed local chat does
not silently switch to Google. Audio settings remain independent.

## Available controls and refresh

Tab completion and command visibility follow cached server capabilities. LM
Studio inventory controls appear when its loopback server is reachable; selecting
a local model also requires a downloaded inventory entry. Context/capability
controls require a selected local model confirmed by that inventory. Reasoning
choices come from the model's actual advertised options. Google voice controls
appear only when Google TTS is selected and configured. Personal language, help,
status and voice-off controls remain available.

Metadata refreshes asynchronously at startup/login, about every 30 seconds and
after a successful provider/backend selection. After preparing assets, repairing
credentials or starting an optional service, run `/llm refresh` to refresh the
menu sooner. Python backends need their audio worker running in standby; an
unreachable worker hides those choices even when its packages are installed.
The installation profile file is advisory and does not prove availability.

Availability means configured metadata and prepared dependencies, not a live
inference test, remaining cloud quota or guaranteed microphone accuracy. Selecting
a shared audio backend rechecks readiness and refuses changes while audio is
busy. Commands do not download weights or install missing software.

## Horror and personal bosses

| Command | Access | Description |
| --- | --- | --- |
| `/companion horror` | Player | Show server horror/boss settings. |
| `/companion horror status` | Player | Same status as `/companion horror`. |
| `/companion horror stop` | Player | End your episode and remove your rival. Starts your cooldown; does not change server modes. |
| `/companion horror mode off\|mood\|random` | OP | Set overall mode. `off` also prevents bosses, regardless of boss mode. |
| `/companion horror boss off\|mood\|random` | OP | Set personal boss mode, subject to overall horror eligibility. |
| `/companion horror threshold <mood>` | OP | Mood threshold, 0–100. Mood mode triggers at or below it; 0 means fully unhappy only. |
| `/companion horror chance <percent>` | OP | Random-mode probability, 0–100 percent. Mood mode is deterministic, not chance-based. |
| `/companion horror cooldown <seconds>` | OP | Regular event cooldown, 10–3,600 seconds. Boss cooldown is a separate host setting. |
| `/companion horror event darkness on\|off` | OP | Enable/disable darkness. |
| `/companion horror event fog on\|off` | OP | Enable/disable fog. |
| `/companion horror event sounds on\|off` | OP | Enable/disable horror sounds. |
| `/companion horror event stalker on\|off` | OP | Enable/disable stalker presentation. |

Fresh settings: overall horror **off**, boss mode **mood**, threshold **0**. Boss
mode alone cannot trigger while overall horror is off. Mood encounters require
relationships. Creative, spectator, dead and disconnected players are ineligible.
Relationships/encounters are personal; one player's behavior does not make
another player the boss target.

Boss cooldown and episode duration are host options in
`config/verity-companion-horror.json`, without slash-command setters. Original
global punishment handlers remain disabled.

## Examples

Use ordinary chat or voice for gameplay tools. These are **chat examples**, not
extra slash commands:

```text
Where am I, and what mobs are nearby?
What enchantments are on the item I am holding?
Which quests can I work on next?
Give me an eternal steak.
Apply night vision permanently.
Remove the night vision effect.
Build a small house nearby.
Undo the house you built.
```

Actions still require server settings, permissions and an actual request. Results
depend on the model and supported mod interfaces. A chat plan does not prove
that a game action completed.

Personal language/voice setup:

```text
/companion language ru
/llm language inherit
/tts language inherit
/voice language ru
/tts voice Achird
/tts on
```

Recover a hidden/stuck Verity:

```text
/companion find
/companion come
/companion follow on
```

OP setup for horror/bosses only at mood zero:

```text
/companion mode true
/companion horror mode mood
/companion horror boss mood
/companion horror threshold 0
```

OP setup for friendly assistance without mood restrictions or horror:

```text
/companion mode false
/companion horror mode off
/companion horror boss off
```

Never enter API keys in game chat. Add credentials only on the host using the
[server setup instructions](CLOUD.md).
