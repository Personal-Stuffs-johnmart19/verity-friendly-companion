# Client installation

For the beta.21 cloud profile, install the matching addon JAR, official
Verity JE 3.4.1 and GeckoLib 4.9.3 in your Minecraft 1.21.1 / compatible NeoForge
instance. No client scripts, keys, Python or model files are required. The host
uses [the JAR-only cloud guide](CLOUD.md).

## Optional Windows installer

From source, `setup.cmd` opens the installer wizard. For a
friend/client, choose **basic** and select the detected compatible instance or an
explicit path. In the optional client archive, run
`verity-client-setup/install.cmd`; that bundle offers only basic installation.
Discovery is read-only and does not rewrite CurseForge pack metadata. Advanced
local backends belong on the server host, not on friends' clients.

```powershell
.\setup.cmd -Discover
.\setup.cmd -Plan -Profile basic -ClientRoot 'C:\Games\Minecraft\Instances\My Pack'
.\setup.cmd -Profile basic -ClientRoot 'C:\Games\Minecraft\Instances\My Pack'
```


On Windows x64, extract the **client-setup ZIP** anywhere and run:

```powershell
.\verity-client-setup\setup-client.cmd -ClientRoot 'C:\Games\Minecraft\Instances\My Pack'
```

From source, build and install in one command:

```powershell
.\setup.cmd -Profile basic -ClientRoot 'C:\Games\Minecraft\Instances\My Pack'
```

The installer checks Java 21 and the actual NeoForge installation, downloads a
private Java JDK if needed, and installs original Verity JE 3.4.1, GeckoLib 4.9.3
and the bundled addon. Third-party mod downloads have pinned SHA256 checksums.
Original Verity is downloaded from its official CurseForge file; it is never
patched or redistributed inside this project.

CurseForge's shared `Install/` directory is detected from the instance location.
For an official-launcher installation use `-MinecraftRoot 'C:\Games\.minecraft'`;
the NeoForge installer can install the loader there. Initialize/sign in to your
Minecraft launcher first. A Minecraft account and launcher are separate from
this addon; the installer cannot authenticate an account for you.

```powershell
.\verity-client-setup\setup-client.cmd -ClientRoot 'C:\Games\My Instance' -MinecraftRoot 'C:\Games\.minecraft'
.\verity-client-setup\setup-client.cmd -ClientRoot 'C:\Games\My Instance' -CheckOnly
```

`-CheckOnly` changes nothing and reports missing dependencies. `-Offline` uses
already verified cached downloads. `-CacheDirectory` chooses a custom tools cache.
Stop Minecraft before replacing mods. Incompatible pack versions and duplicate
or privately patched mod JARs are reported; existing files/worlds/settings are not
removed. Source builds require their compiled JAR; the client-setup release ZIP
already includes it.

The client archive contains no Python, server configuration, API keys, recordings
or model weights. Voice/AI processing stays on the server. Connect to a server
with the matching addon version; hold R for voice chat after the host completes
server setup.

## Linux basic installation

Run `bash setup.sh` from source, or
`bash verity-client-setup/setup.sh` after extracting the optional client archive.
Select an existing compatible Minecraft 1.21.1 / NeoForge client. Pass
`--client-root /path/to/instance --minecraft-root /path/to/launcher` for an
explicit target; `--list-candidates` and `--plan` only inspect/report. Existing
CurseForge metadata and accounts remain managed by that launcher. The client
bundle installs game mods only and contains no Python code, models or host keys.

For an explicitly requested addon replacement through the Windows client CLI,
`setup-client.cmd -ClientRoot 'C:\path\to\instance' -UpdateAddon` uses a backed-up
replacement. Close Minecraft first. Basic wizard installation uses this update
path when repairing required client libraries; it does not rewrite pack metadata.
