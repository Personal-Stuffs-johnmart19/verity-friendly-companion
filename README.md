# Verity Friendly Companion

An unofficial addon that turns Verity into a Minecraft companion: talk by chat or
microphone, ask about your surroundings and quests, and get help with items,
effects, enchantments and building. Supports Google Gemini and local LM Studio
models, with server-generated voice replies.

**Current beta: [1.2.0-beta.21](https://github.com/Personal-Stuffs-johnmart19/verity-friendly-companion/releases/tag/v1.2.0-beta.21).**
Update the addon on the server and every client together. Keep your world,
server settings and private credentials; see [updating](docs/SERVER.md#settings-and-updates).

## Requirements

- Minecraft **1.21.1**, Java **21**, NeoForge **21.1.250 or newer 21.1.x**.
- [Original Verity JE **3.4.1**](https://www.curseforge.com/minecraft/mc-mods/verity-je)
  and [GeckoLib **4.9.3**](https://www.curseforge.com/minecraft/mc-mods/geckolib)
  for NeoForge 1.21.1 on the server and every client.
- The same companion addon JAR on both sides. Tested with ATM10 Aeronautics 0.7.1.

## Get started with beta.21

1. Download the release JAR, build from source, or run the setup wizard below. Put the matching addon and
   required mods in the server's and each client's `mods/` folders.
2. Start the server and run `/companion setup` as an operator. Follow the
   [cloud setup guide](docs/CLOUD.md) to configure Google, or select a running
   LM Studio model with `/llm local <model>`.
3. Talk in chat. Hold **R** to speak and release to send; **M** cycles microphones.
   Microphone recognition must first be configured by the host. Keys are
   configurable in Minecraft Controls.

Cloud providers run inside the JAR. Clients need **no API keys, Python or model
downloads**. Google credentials belong only on the server. Cloud microphone
uploads require separate host consent; recognition starts disabled.

## Setup and building

From a cloned repository or extracted source ZIP:

| Task | Windows | Linux |
| --- | --- | --- |
| Setup wizard | `setup.cmd` | `bash setup.sh` |
| Build the JAR only | `setup.cmd -BuildOnly` | `bash setup.sh --build-only` |

Setup can export just the JAR, install into a compatible Minecraft instance, or
create a standalone NeoForge server. Advanced setup offers optional local audio
backends and an isolated Python environment. Downloads and destinations are
explicit choices; setup never launches Minecraft or a model.

## Guides

- [Server setup and local backends](docs/SERVER.md) · [Client setup](docs/CLIENT.md)
- [Commands and examples](docs/COMMANDS.md) · [Languages](docs/LANGUAGES.md)
- [Build and packaging](docs/BUILDING.md) · [Repository layout](docs/ARCHITECTURE.md)
- [Privacy and security](SECURITY.md) · [Changes](CHANGELOG.md)

This is beta software. Model quality, provider quotas, protection rules and mod
integration affect what the companion can do. Not every custom GUI or network
is supported. Role selection changes the persona, not the 3D model.

Addon code is [MIT licensed](LICENSE). Required mods retain their own licenses;
this project is not affiliated with the original Verity author.
