# Commands

Ordinary chat supports actions and settings. Minecraft completion and
`/companion help`, `/llm help`, `/tts` show accepted arguments.

| Command | Purpose |
| --- | --- |
| `/companion` | Status |
| `/companion language en\|ru\|auto` | Reply language |
| `/companion girl`, `/companion boy` | Persona shortcuts |
| `/companion gender male\|female\|neutral` | Persona pronouns |
| `/companion role orb\|protector\|anime` | Persona role; no entity model replacement |
| `/companion rename <name>` | Personal companion name |
| `/companion face <emotion\|auto>` | Face or automatic expressions |
| `/companion mood auto\|on\|off` | Mood-bar visibility |
| `/companion find`, `come`, `summon`, `box`, `stay` | Presence actions (each under `/companion`) |
| `/companion follow [on\|off]` | Follow owner |
| `/companion blueprints [query]`, `/companion schematics` | Building catalogues |
| `/tts on`, `/tts off` | Personal voice replies |
| `/tts voices`, `/tts voice <name>` | Voice selection |

Summoning can create an extra entity when the existing one is far away;
unloaded areas are not searched.

## Operator controls

`/llm` requires permission level 2; routing/model settings affect the shared service.

| Command | Purpose |
| --- | --- |
| `/llm status`, `/llm caps` | Current state and capabilities |
| `/llm models`, `/llm local [model]` | Discover/select LM Studio models |
| `/llm cloud` | Configured Google chat |
| `/llm reasoning on\|off\|low\|medium\|high` | Reasoning when supported |
| `/llm context <4096..1048576>` | Requested context; model capacity still applies |
| `/llm temperature <0..2>` | Sampling |
| `/llm max_tokens <128..4096>` | Output budget |
| `/llm system_prompt [show\|clear]` | Inspect/clear extra situational guidance |
| `/llm system_prompt append <text>` | Add guidance alongside the base persona |
| `/llm reset` | Reset model settings |

Unsupported controls report their lack of support. Extra prompt text does not
replace the base persona or remove tool validation.

Operators can use `/companion mode <true|false>` for relationships and
`/companion retaliation <true|false>` for the rival setting. `/companion reset`
resets the invoking player's relationship and removes their rival.

## Horror

`/companion horror` shows settings. Operator subcommands:

- `mode off|mood|random`
- `boss off|mood|random`
- `threshold <0..100>` — zero means entirely unhappy players only
- `event <name> on|off` — use completion for effect names

Each follows `/companion horror`. `/companion horror stop` ends your current
encounter without changing the server mode. Original global punishment logic
stays disabled; these settings control the addon's personal encounters.
