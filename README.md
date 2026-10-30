# Verity Friendly Companion

Unofficial Minecraft companion with server-side AI, Russian/English speech
recognition, voice replies, world inspection and building tools.

**1.2.0-beta.20** targets **Minecraft 1.21.1**, **NeoForge 21.1.250–21.1.x** and
**original Verity JE exactly 3.4.1**. Tested with ATM10 Aeronautics 0.7.1.

Install the same addon JAR on the server and every client, together with
[original Verity JE](https://www.curseforge.com/minecraft/mc-mods/verity-je).
Clients need no Python, model weights, tokens or API keys. This independent addon
does not bundle original Verity or support other loaders/Minecraft versions.

## Features

- Ordinary chat with Gemini or an LM Studio model; tools execute on the server.
- Hold **R** to speak, release to send; **M** cycles microphones. Rebind in Controls.
  The selected input is saved locally and displayed on the recording HUD.
- Complete server-generated voice replies; pressing R interrupts playback.
- Item, inventory, enchantment, effect, mob, recipe, achievement and FTB Quests inspection.
- Gifts, effects, enchanting, structures and recorded undo, subject to settings,
  permissions and protection checks. Building uses free blocks.
- Individual names, personas, language, mood and faces. Random horror is off by
  default; the default personal boss mode is mood-triggered at zero.

This is beta software. Not every mod GUI/network is supported. Role selection
changes the persona, not the 3D model. Voice is push-to-talk, not a continuous call.
The first recognition request loads its model; free cloud replies can queue.
Provider quotas, hardware limits and model tool-use quality apply.

## Installation

Download the JAR from [Releases](https://github.com/Personal-Stuffs-johnmart19/verity-friendly-companion/releases)
and place it in `mods/` on both server and clients. Stop Minecraft before changing
JARs. Remove old privately patched Verity JARs; keep official Verity 3.4.1.
Server owners must also configure the separate bridge service.

- [Server setup](docs/SERVER.md)
- [Commands](docs/COMMANDS.md)
- [Build and release packaging](docs/BUILDING.md)
- [Privacy and security](SECURITY.md)
- [Changelog](CHANGELOG.md)

Source and release packages exclude tests, diagnostics, recordings, models,
credentials and game data. Addon code is under [MIT](LICENSE); required mods
retain their own licenses. No affiliation with the original Verity author is implied.
