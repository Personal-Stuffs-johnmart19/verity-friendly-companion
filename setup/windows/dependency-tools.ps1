$layout=Join-Path $PSScriptRoot 'source-layout.ps1'
if(!(Test-Path -LiteralPath $layout)){$layout=Join-Path (Split-Path $PSScriptRoot) 'source-layout.ps1'}
. $layout
# Shared host/client dependency helpers. No global installs, credentials or inference.
function Get-VfcTextHash([string]$Path){
 $text=[IO.File]::ReadAllText($Path).Replace("`r`n","`n").TrimStart([char]0xFEFF)
 $algorithm=[Security.Cryptography.SHA256]::Create()
 try{return [BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($text))).Replace('-','').ToLowerInvariant()}finally{$algorithm.Dispose()}
}
function Install-VfcLaunchers([string]$Root,[string]$Source,[switch]$UpdateService){
 foreach($name in @('run-verity-server.cmd','run-verity-server.ps1')){
  $destination=Join-Path $Root $name;$template=Resolve-VfcSourceFile $name
  if(!(Test-Path -LiteralPath $destination)){Copy-Item -LiteralPath $template -Destination $destination;continue}
  if(!$UpdateService -or (Get-VfcTextHash $destination) -eq (Get-VfcTextHash $template)){continue}
  # Only replace the exact previously published launcher; preserve custom edits.
  if($name -eq 'run-verity-server.ps1' -and (Get-VfcTextHash $destination) -eq '2ee993a3d3ba209ff1d7ddd5a1fecab9b7eaa0a81735fde108d81a9665497607'){
   $backup=$destination+'.before-native'
   if(!(Test-Path -LiteralPath $backup)){Copy-Item -LiteralPath $destination -Destination $backup}
   Copy-Item -LiteralPath $template -Destination $destination -Force
  }else{
   $candidate=Join-Path $Root ('updated-'+$name)
   if(!(Test-Path -LiteralPath $candidate)){Copy-Item -LiteralPath $template -Destination $candidate}
   Write-Warning "Custom launcher preserved: $destination. Review the updated template at $candidate; run it for native mode instead of a custom bridge-only launcher."
  }
 }
}
function Get-VfcEffectiveStt([string]$Root,$Voice){
 if($Voice.PSObject.Properties['stt_engine']){return $Voice.stt_engine}
 $settings=Join-Path $Root 'config/verity-companion-server.json'
 if(Test-Path -LiteralPath $settings){$saved=Get-Content -LiteralPath $settings -Raw|ConvertFrom-Json;if($saved.PSObject.Properties['runtime'] -and $saved.runtime -eq 'native'){return 'disabled'}}
 return 'faster-whisper'
}
function Get-VfcLocalFallback($Voice,[string]$Provider){
 if($Provider -ne 'google' -or !$Voice.PSObject.Properties['local_fallback'] -or !$Voice.local_fallback){return 'disabled'}
 if($Voice.PSObject.Properties['local_fallback_provider']){return $Voice.local_fallback_provider}
 return 'silero'
}
function Test-VfcNeedsPython([string]$Root){
 $bridge=Join-Path $Root 'config/verity-companion-server.json'
 $voice=Join-Path $Root 'verity-service/voice-config.json'
 if(!(Test-Path -LiteralPath $voice)){return $true}
 $audio=Get-Content -LiteralPath $voice -Raw|ConvertFrom-Json
 $stt=Get-VfcEffectiveStt $Root $audio
 $provider=if($audio.PSObject.Properties['provider']){$audio.provider}else{'google'}
 $fallback=Get-VfcLocalFallback $audio $provider
 return $stt -notin 'google','disabled','vulkan','native-cuda','native-cpu' -or $provider -notin 'google','disabled','drama3' -or ($fallback -notin 'disabled','drama3')
}
function Get-VfcNativeSettingsProblems([string]$Root){
 try{
  $voice=Get-Content -LiteralPath (Join-Path $Root 'verity-service/voice-config.json') -Raw|ConvertFrom-Json
  $routingPath=Join-Path $Root 'verity-service/chat-routing.json'
  $provider='google'
  if(Test-Path -LiteralPath $routingPath){$routing=Get-Content -LiteralPath $routingPath -Raw|ConvertFrom-Json;$provider=$routing.provider;if($provider -notin 'google','local'){'Invalid chat routing: select google or local explicitly';return}}
  $tts=if($voice.PSObject.Properties['provider']){$voice.provider}else{'google'}
  $stt=Get-VfcEffectiveStt $Root $voice
  $fallback=Get-VfcLocalFallback $voice $tts
  if($fallback -notin 'disabled','silero','drama3'){'Invalid local_fallback_provider: select silero or drama3.'}
  if($stt -eq 'google' -and (!$voice.PSObject.Properties['google_audio_transcription_confirmed'] -or $voice.google_audio_transcription_confirmed -ne $true)){'Cloud microphone transcription needs explicit host consent: use -ConfirmCloudTranscription only after agreeing to upload microphone recordings to Google, or choose local/disabled recognition.'}
  if($provider -eq 'google' -or $tts -eq 'google' -or $stt -eq 'google'){
   $keyPath=Join-Path $Root 'verity-service/.secrets/google-api-key'
   if(!(Test-Path -LiteralPath $keyPath)){'Save your Google key only in verity-service/.secrets/google-api-key or rerun setup with its hidden prompt.'}
   elseif((Get-Item -LiteralPath $keyPath).Length -gt 4096 -or [IO.File]::ReadAllText($keyPath).Trim() -cnotmatch '^[\x21-\x7e]{16,512}$'){'Google key is empty/invalid; replace it with the hidden setup prompt.'}
   if(!$voice.PSObject.Properties['google_free_project_confirmed'] -or $voice.google_free_project_confirmed -ne $true){'Confirm that the Google project has no billing, then set google_free_project_confirmed=true in voice-config.json.'}
   if($voice.PSObject.Properties['allow_external_requests'] -and $voice.allow_external_requests -eq $false){'Cloud features are selected but allow_external_requests=false. Select local chat/recognition or explicitly enable cloud requests.'}
  }
  $service=Join-Path $Root 'verity-service'
  . (Join-Path $PSScriptRoot 'native-audio-assets.ps1')
  $needsNativeAsr=$stt -in 'vulkan','native-cuda','native-cpu' -or ($stt -in 'cuda','rocm' -and $voice.PSObject.Properties['stt_vulkan_fallback'] -and $voice.stt_vulkan_fallback)
  if($needsNativeAsr){
   foreach($name in @('stt_native_executable','stt_native_model')){if(!$voice.PSObject.Properties[$name] -or !(Test-Path -LiteralPath (Resolve-VfcAudioPath $service $voice.$name) -PathType Leaf)){('Native recognition asset missing: '+$name+'; rerun setup with the selected native recognition backend.')}}
  }
  if($needsNativeAsr -and $stt -ne 'native-cpu' -and (!$voice.PSObject.Properties['stt_cpu_fallback'] -or $voice.stt_cpu_fallback)){
   if(!$voice.PSObject.Properties['stt_native_cpu_executable'] -or !(Test-Path -LiteralPath (Resolve-VfcAudioPath $service $voice.stt_native_cpu_executable) -PathType Leaf)){'Native CPU fallback executable missing; rerun native recognition setup or explicitly use -CpuFallback off.'}
  }
  if($stt -eq 'native-cuda' -and $voice.PSObject.Properties['stt_native_library_directories']){
   foreach($directory in $voice.stt_native_library_directories){if(!(Test-Path -LiteralPath (Resolve-VfcAudioPath $service $directory) -PathType Container)){('Native CUDA library directory missing: '+$directory+'; rerun native CUDA setup.')}}
  }
  if($tts -eq 'drama3' -or $fallback -eq 'drama3'){
   $directory=if($voice.PSObject.Properties['drama3_directory']){Resolve-VfcAudioPath $service $voice.drama3_directory}else{Resolve-VfcAudioPath $service '../verity-tts'}
   $executable=if($voice.PSObject.Properties['drama3_executable']){Resolve-VfcAudioPath $service $voice.drama3_executable}else{Join-Path $directory 'runtime/audiocpp_server.exe'}
   if(!(Test-Path -LiteralPath $executable -PathType Leaf)){'Drama 3 native executable missing; rerun selected backend setup.'}
   $configPath=Join-Path $directory 'server.json'
   if(!(Test-Path -LiteralPath $configPath)){'Drama 3 server.json missing; rerun selected backend setup.'}
   else{
    try{
     $nativeConfig=Get-Content -LiteralPath $configPath -Raw|ConvertFrom-Json
     if(!$nativeConfig.PSObject.Properties['models'] -or !@($nativeConfig.models|Where-Object {$_.id -eq 'verity-drama3' -and $_.family -eq 'fish_drama3'}).Count){'Drama 3 server.json has no verity-drama3 fish_drama3 model; repair the native model configuration.'}
     else{foreach($model in $nativeConfig.models){if(!$model.PSObject.Properties['path'] -or !(Test-Path -LiteralPath (Resolve-VfcAudioPath $directory $model.path) -PathType Leaf)){'Drama 3 model file missing; rerun selected backend setup.'}}}
    }catch{'Drama 3 server.json is invalid; repair its native model configuration without replacing reference recordings.'}
   }
   try{Install-VfcAudioReferences $directory $null -CheckOnly}catch{'Drama 3 references need attention: '+$_.Exception.Message}
  }
  if($voice.PSObject.Properties['lmstudio_endpoint']){$uri=[uri]$voice.lmstudio_endpoint;if($uri.Scheme -ne 'http' -or $uri.Host -notin '127.0.0.1','localhost','[::1]' -or $uri.UserInfo -or $uri.AbsolutePath -notin '','/' -or $uri.Query -or $uri.Fragment){'lmstudio_endpoint must be a loopback HTTP origin.'}}
 }catch{'Missing/invalid native provider configuration; rerun server setup. Existing files were not replaced.'}
}
function Get-VfcDownload([string]$Url,[string]$Name,[string]$Sha256,[string]$Cache,[switch]$Offline){
 if([IO.Path]::GetFileName($Name) -ne $Name -or $Name -match '[\\/]'){throw 'Dependency cache names must be plain filenames'}
 if(([uri]$Url).Scheme -ne 'https' -or $Sha256 -notmatch '^[a-fA-F0-9]{64}$'){throw 'Dependency downloads require HTTPS and a pinned SHA256'}
 $file=Join-Path $Cache $Name
 if(!(Test-Path -LiteralPath $file)){
  if($Offline){throw "Offline dependency missing: $Name"}
  New-Item -ItemType Directory -Force -Path $Cache|Out-Null
  [Net.ServicePointManager]::SecurityProtocol=[Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
  $partial=$file+'.partial'
  for($attempt=1;$attempt -le 3;$attempt++){
   $progress=$ProgressPreference
   try{$ProgressPreference='SilentlyContinue';Write-Host "Downloading $Name...";Invoke-WebRequest $Url -OutFile $partial -UseBasicParsing -TimeoutSec 600;break}
   catch{if($attempt -eq 3){throw "Cannot download $Name from its official source. Check the connection or supply the matching local file. No existing dependency was replaced."};Start-Sleep -Seconds $attempt}
   finally{$ProgressPreference=$progress}
  }
  if((Get-FileHash -LiteralPath $partial).Hash -ne $Sha256){throw "Checksum mismatch: $Name; refusing installation"}
  Move-Item -LiteralPath $partial -Destination $file -Force
 }
 if((Get-FileHash -LiteralPath $file).Hash -ne $Sha256){throw "Corrupted dependency cache: $Name; remove this cached file and retry"}
 return $file
}
function Test-VfcJava([string]$Directory){
 if(!$Directory){return $false}
 $release=Join-Path $Directory 'release'
 return (Test-Path -LiteralPath (Join-Path $Directory 'bin/java.exe')) -and (Test-Path -LiteralPath (Join-Path $Directory 'bin/javac.exe')) -and (Test-Path -LiteralPath $release) -and ([IO.File]::ReadAllText($release) -match '(?m)^JAVA_VERSION="21(?:[."+])')
}
function Get-VfcJava([string]$Cache,[string]$JavaHome,[switch]$CheckOnly,[switch]$Offline){
 if($JavaHome){if(!(Test-VfcJava $JavaHome)){throw '-JavaHome must point to a Java 21 JDK'};return [IO.Path]::GetFullPath($JavaHome)}
 foreach($candidate in @($env:JAVA_HOME,(Join-Path $Cache 'jdk-21.0.13+9'))){if(Test-VfcJava $candidate){return $candidate}}
 $compiler=Get-Command javac.exe -ErrorAction SilentlyContinue
 if($compiler){$candidate=Split-Path (Split-Path $compiler.Source);if(Test-VfcJava $candidate){return $candidate}}
 if($CheckOnly){return $null}
 $archive=Get-VfcDownload 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip' 'temurin-21.0.13-9-windows-x64.zip' 'a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9' $Cache -Offline:$Offline
 Expand-Archive -LiteralPath $archive -DestinationPath $Cache -Force
 $java=Join-Path $Cache 'jdk-21.0.13+9'
 if(!(Test-VfcJava $java)){throw 'Java 21 extraction failed'}
 return $java
}
function Get-VfcMods([string]$Root){
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $found=@{verity=@();verity_companion=@();geckolib=@()}
 $directory=Join-Path $Root 'mods'
 if(Test-Path -LiteralPath $directory){foreach($file in Get-ChildItem -LiteralPath $directory -Filter '*.jar' -File){
  $zip=[IO.Compression.ZipFile]::OpenRead($file.FullName)
  try{
   $entry=$zip.GetEntry('META-INF/neoforge.mods.toml');if(!$entry){continue};if($entry.Length -gt 262144){throw ('Mod metadata exceeds its size limit: '+$file.Name)}
   $reader=[IO.StreamReader]::new($entry.Open());try{$toml=$reader.ReadToEnd()}finally{$reader.Dispose()}
   foreach($block in [regex]::Matches($toml,'(?ms)^\s*\[\[mods\]\](.*?)(?=^\s*\[|\z)')){
    $id=[regex]::Match($block.Groups[1].Value,'(?m)^\s*modId\s*=\s*"([^"]+)"').Groups[1].Value
    if($found.ContainsKey($id)){$version=[regex]::Match($block.Groups[1].Value,'(?m)^\s*version\s*=\s*"([^"]+)"').Groups[1].Value;$found[$id]+=@{version=$version;path=$file.FullName}}
   }
  }finally{$zip.Dispose()}
 }}
 return $found
}
function Get-VfcModProblems([string]$Root,[string]$ExpectedVersion){
 $mods=Get-VfcMods $Root
 if($mods.verity.Count -ne 1 -or $mods.verity[0].version -ne '3.4.1'){'Exactly one official Verity JE 3.4.1 is required in mods/.'}
 elseif((Get-FileHash -LiteralPath $mods.verity[0].path).Hash -ne 'b32223e720affda0e617efc694c3fb0bda4cb7de5941890c5e9cc828b700a93b'){'Verity 3.4.1 differs from the supported official original JAR. Move patched copies aside explicitly; the installer will not overwrite them.'}
 if($mods.verity_companion.Count -ne 1){'Exactly one matching Verity Friendly Companion JAR is required in mods/.'}
 elseif($ExpectedVersion -and $mods.verity_companion[0].version -ne $ExpectedVersion){'The installed addon version differs from this setup package; update the matching JAR on server and clients after stopping Minecraft.'}
 $version=$null
 if($mods.geckolib.Count -ne 1 -or ![version]::TryParse($mods.geckolib[0].version,[ref]$version) -or $version -lt [version]'4.9.3' -or $version -ge [version]'5.0'){'Exactly one GeckoLib 4.9.3 or compatible newer 4.x for NeoForge 1.21.1 is required in mods/.'}
}
function Install-VfcMods([string]$Root,[string]$AddonJar,[string]$Cache,[switch]$Offline){
 New-Item -ItemType Directory -Force -Path (Join-Path $Root 'mods')|Out-Null
 $mods=Get-VfcMods $Root
 foreach($item in @(
  @{id='verity';name='verity-3.4.1.jar';url='https://edge.forgecdn.net/files/8346/795/verity-3.4.1.jar';sha='b32223e720affda0e617efc694c3fb0bda4cb7de5941890c5e9cc828b700a93b'},
  @{id='geckolib';name='geckolib-neoforge-1.21.1-4.9.3.jar';url='https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/software/bernie/geckolib/geckolib-neoforge-1.21.1/4.9.3/geckolib-neoforge-1.21.1-4.9.3.jar';sha='20a1995e4074f387ff549e2c57ea79dd7d41ee83c6afb9dd6a6e840e592d1c47'})){
  if($mods[$item.id].Count -eq 0){
   $target=Join-Path $Root ('mods/'+$item.name)
   if(Test-Path -LiteralPath $target){throw "Conflicting file exists: $target; move it aside explicitly"}
   $file=Get-VfcDownload $item.url $item.name $item.sha $Cache -Offline:$Offline
   Copy-Item -LiteralPath $file -Destination $target
  }
 }
 if($AddonJar){
  $AddonJar=[IO.Path]::GetFullPath($AddonJar)
  if(!(Test-Path -LiteralPath $AddonJar)){throw 'Addon JAR not found; compile it with setup.cmd or use a complete setup release ZIP'}
  $target=Join-Path $Root ('mods/'+[IO.Path]::GetFileName($AddonJar))
  if(Test-Path -LiteralPath $target){if((Get-FileHash -LiteralPath $target).Hash -ne (Get-FileHash -LiteralPath $AddonJar).Hash){throw 'A different addon with this filename already exists; stop Minecraft and replace it explicitly'}}
  elseif($mods.verity_companion.Count){throw 'An older addon is installed; stop Minecraft and move the old JAR aside explicitly before updating'}
  else{Copy-Item -LiteralPath $AddonJar -Destination $target}
 }
 $expected=if($AddonJar){Get-VfcAddonVersion $AddonJar}else{$null}
 $problems=@(Get-VfcModProblems $Root $expected)
 if($problems.Count){throw ($problems -join "`n")}
}
function Get-VfcServerArgs([string]$Root){
 $directory=Join-Path $Root 'libraries/net/neoforged/neoforge'
 if(!(Test-Path -LiteralPath $directory)){return $null}
 foreach($version in Get-ChildItem -LiteralPath $directory -Directory|Sort-Object Name -Descending){
  if($version.Name -match '^21\.1\.(\d+)$' -and [int]$Matches[1] -ge 250){$file=Join-Path $version.FullName 'win_args.txt';if(Test-Path -LiteralPath $file){return $file}}
 }
 return $null
}
function Test-VfcServerLibraries([string]$Root,[string]$ArgsFile){
 if(!$ArgsFile -or !(Test-Path -LiteralPath $ArgsFile)){return $false}
 $paths=@([regex]::Matches([IO.File]::ReadAllText($ArgsFile),'libraries/[^\s;"\r\n]+?\.jar')|ForEach-Object Value|Sort-Object -Unique)
 if(!$paths.Count){return $false}
 foreach($path in $paths){if(!(Test-Path -LiteralPath (Join-Path $Root $path))){return $false}}
 return $true
}
function Get-VfcNeoForgeInstaller([string]$Version,[string]$Cache,[switch]$Offline){
 if($Version -notmatch '^21\.1\.[0-9]+$'){throw 'Invalid NeoForge version'}
 $sha='0e47a91ba2139a8db4bf7627af081f7b5789b508bb039ee8dea1272b79693d60'
 if($Version -ne '21.1.250'){
  if($Offline){throw 'Repairing this pack loader needs its official checksum online'}
  $response=Invoke-WebRequest ('https://maven.neoforged.net/releases/net/neoforged/neoforge/'+$Version+'/neoforge-'+$Version+'-installer.jar.sha256') -UseBasicParsing -TimeoutSec 30
  $sha=if($response.Content -is [byte[]]){[Text.Encoding]::ASCII.GetString($response.Content).Trim()}else{$response.Content.Trim()}
 }
 return Get-VfcDownload ('https://maven.neoforged.net/releases/net/neoforged/neoforge/'+$Version+'/neoforge-'+$Version+'-installer.jar') ('neoforge-'+$Version+'-installer.jar') $sha $Cache -Offline:$Offline
}
function Install-VfcServerLoader([string]$Root,[string]$JavaHome,[string]$Cache,[switch]$Offline){
 $existing=Get-VfcServerArgs $Root
 if($existing -and (Test-VfcServerLibraries $Root $existing)){return $existing}
 if(!$existing -and ((Test-Path -LiteralPath (Join-Path $Root 'libraries/net/neoforged/neoforge')) -or (Test-Path -LiteralPath (Join-Path $Root 'libraries/net/minecraftforge/forge')))){throw 'An incompatible loader is installed. The pack loader will not be upgraded silently; use NeoForge 21.1.250 or newer 21.1.x.'}
 if($Offline){throw 'A new NeoForge installation needs network access for Minecraft libraries'}
 $version=if($existing){Split-Path (Split-Path $existing) -Leaf}else{'21.1.250'}
 $installer=Get-VfcNeoForgeInstaller $version $Cache
 $socket=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.vfc-jtmp';New-Item -ItemType Directory -Force -Path $socket|Out-Null
 Push-Location $Root
 try{& (Join-Path $JavaHome 'bin/java.exe') ('-Djdk.net.unixdomain.tmpdir='+$socket.Replace('\','/')) -jar $installer --installServer $Root | Out-Host;if($LASTEXITCODE){throw 'NeoForge dependency installation failed'}}finally{Pop-Location}
 $installed=Get-VfcServerArgs $Root
 if(!(Test-VfcServerLibraries $Root $installed)){throw 'NeoForge installer did not create all required server libraries'}
 return $installed
}
function Get-VfcBundledAddon([string]$ScriptRoot){
 $bundled=Join-Path $ScriptRoot 'addon'
 if(Test-Path -LiteralPath $bundled){$jars=@(Get-ChildItem -LiteralPath $bundled -Filter '*.jar' -File);if($jars.Count -eq 1){return $jars[0].FullName}}
 $repo=Get-VfcSourceRoot
 $metadata=Join-Path $repo 'src/main/resources/META-INF/neoforge.mods.toml'
 if(Test-Path -LiteralPath $metadata){$version=[regex]::Match([IO.File]::ReadAllText($metadata),'(?m)^version="([0-9A-Za-z.-]+)"').Groups[1].Value;$jar=Join-Path $repo ('dist/verity-friendly-companion-'+$version+'.jar');if(Test-Path -LiteralPath $jar){return $jar}}
 return $null
}
function Test-VfcNativeRuntime {
 foreach($name in @('vcruntime140.dll','vcruntime140_1.dll','msvcp140.dll')){if(!(Test-Path -LiteralPath (Join-Path ([Environment]::SystemDirectory) $name))){return $false}}
 return $true
}
function Install-VfcNativeRuntime([string]$Cache,[switch]$NonInteractive,[switch]$Offline,[switch]$Force){
 if(!$Force -and (Test-VfcNativeRuntime)){return}
 if($Offline){throw 'Microsoft Visual C++ x64 runtime is missing; an online installation is required'}
 $identity=[Security.Principal.WindowsIdentity]::GetCurrent()
 $admin=([Security.Principal.WindowsPrincipal]::new($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
 if(!$admin -and $NonInteractive){throw 'Microsoft Visual C++ x64 runtime is missing. Rerun setup interactively to approve its official installer/UAC prompt.'}
 if(!$admin -and (Read-Host 'Microsoft Visual C++ x64 runtime is missing. Install the signed Microsoft runtime (Windows administrator approval may appear)? Type yes') -cne 'yes'){throw 'Required native runtime installation was declined; speech dependencies are incomplete'}
 $installer=Join-Path $Cache 'vc_redist.x64.exe'
 New-Item -ItemType Directory -Force -Path $Cache|Out-Null
 if($Force -or !(Test-Path -LiteralPath $installer)){
  $partial=$installer+'.partial'
  Invoke-WebRequest 'https://aka.ms/vs/17/release/vc_redist.x64.exe' -OutFile $partial -UseBasicParsing -TimeoutSec 300
  Move-Item -LiteralPath $partial -Destination $installer -Force
 }
 $signature=Get-AuthenticodeSignature -LiteralPath $installer
 if($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation(?:,|$)'){throw 'Visual C++ installer signature is not a valid Microsoft signature; refusing execution'}
 $options=@{FilePath=$installer;ArgumentList=@('/install','/quiet','/norestart');Wait=$true;PassThru=$true}
 if(!$admin){$options.Verb='RunAs'}else{$options.WindowStyle='Hidden'}
 $process=Start-Process @options
 if($process.ExitCode -eq 3010){throw 'Microsoft runtime installed but Windows requests a restart. Restart when convenient and rerun checks; setup never restarts the PC.'}
 if($process.ExitCode -notin 0,1638 -or !(Test-VfcNativeRuntime)){throw 'Visual C++ runtime installation did not finish successfully'}
}
function Get-VfcLms {
 $command=Get-Command lms.exe -ErrorAction SilentlyContinue
 if($command){return $command.Source}
 $candidate=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.lmstudio/bin/lms.exe'
 if(Test-Path -LiteralPath $candidate){return $candidate}
 return $null
}
function Install-VfcWinget([string]$Cache,[switch]$Offline){
 $command=Get-Command winget.exe -ErrorAction SilentlyContinue
 if($command){return $command.Source}
 if($Offline){throw 'WinGet/LM Studio missing in offline mode'}
 $release=Invoke-RestMethod 'https://api.github.com/repos/microsoft/winget-cli/releases/latest'
 if($release.tag_name -notmatch '^[v0-9A-Za-z.-]+$'){throw 'Invalid WinGet release version'}
 $Cache=Join-Path $Cache ('winget-'+$release.tag_name)
 $bundle=$release.assets|Where-Object name -eq 'Microsoft.DesktopAppInstaller_8wekyb3d8bbwe.msixbundle'
 $dependencies=$release.assets|Where-Object name -eq 'DesktopAppInstaller_Dependencies.zip'
 if(!$bundle -or !$dependencies -or $bundle.digest -notmatch '^sha256:[a-f0-9]{64}$' -or $dependencies.digest -notmatch '^sha256:[a-f0-9]{64}$'){throw 'The official Microsoft WinGet release lacks verifiable installer assets'}
 $file=Get-VfcDownload $bundle.browser_download_url $bundle.name $bundle.digest.Substring(7) $Cache
 $zip=Get-VfcDownload $dependencies.browser_download_url $dependencies.name $dependencies.digest.Substring(7) $Cache
 $directory=Join-Path $Cache 'winget-dependencies'
 Expand-Archive -LiteralPath $zip -DestinationPath $directory -Force
 $packages=@(Get-ChildItem -LiteralPath $directory -Recurse -File|Where-Object {$_.Extension -in '.appx','.msix' -and $_.FullName -match 'x64'}|ForEach-Object FullName)
 # Windows validates the Microsoft AppX signatures; no execution-policy/signature bypass.
 Add-AppxPackage -Path $file -DependencyPath $packages
 $command=Get-Command winget.exe -ErrorAction SilentlyContinue
 if(!$command){$candidate=Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Microsoft/WindowsApps/winget.exe';if(Test-Path -LiteralPath $candidate){return $candidate};throw 'WinGet requires Windows App Installer support; installation was blocked by Windows policy. Contact the host administrator.'}
 return $command.Source
}
function Install-VfcLms([string]$Cache,[switch]$Offline){
 $lms=Get-VfcLms
 if($lms){return $lms}
 if($Offline){throw 'LM Studio CLI missing in offline mode'}
 $winget=Install-VfcWinget $Cache
 & $winget install --id ElementLabs.LMStudio --exact --source winget --scope user --silent --accept-package-agreements --accept-source-agreements --disable-interactivity | Out-Host
 if($LASTEXITCODE){throw 'LM Studio package installation failed; existing models/settings were not modified'}
 $lms=Get-VfcLms
 if(!$lms){throw 'LM Studio is installed. Its first-run initialization is still required: open LM Studio once, close it if desired, then rerun setup. No model was loaded automatically.'}
 return $lms
}

function Get-VfcAddonVersion([string]$Jar){
 if(!$Jar -or !(Test-Path -LiteralPath $Jar)){return $null}
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $archive=[IO.Compression.ZipFile]::OpenRead($Jar)
 try{$entry=$archive.GetEntry('META-INF/neoforge.mods.toml');if(!$entry -or $entry.Length -gt 262144){throw 'Addon metadata is missing or exceeds its size limit'};$reader=[IO.StreamReader]::new($entry.Open());try{$text=$reader.ReadToEnd()}finally{$reader.Dispose()};return [regex]::Match($text,'(?m)^version="([0-9A-Za-z.-]+)"').Groups[1].Value}finally{$archive.Dispose()}
}
function Test-VfcClientLibraries([string]$Root,[string]$Version,[int]$Depth=0){
 if($Depth -gt 4 -or $Version -notmatch '^[0-9A-Za-z._-]+$'){return $false}
 $profile=Join-Path $Root ('versions/'+$Version+'/'+$Version+'.json')
 if(!(Test-Path -LiteralPath $profile)){return $false}
 try{$data=Get-Content -LiteralPath $profile -Raw|ConvertFrom-Json}catch{return $false}
 if($data.PSObject.Properties['inheritsFrom'] -and !(Test-VfcClientLibraries $Root $data.inheritsFrom ($Depth+1))){return $false}
 if($data.PSObject.Properties['downloads'] -and $data.downloads.PSObject.Properties['client'] -and !(Test-Path -LiteralPath (Join-Path $Root ('versions/'+$Version+'/'+$Version+'.jar')))){return $false}
 if(!$data.PSObject.Properties['libraries']){return $false}
 foreach($library in $data.libraries){
  $allowed=$true
  if($library.PSObject.Properties['rules']){
   $allowed=$false
   foreach($rule in $library.rules){
    if($rule.PSObject.Properties['os']){
     if($rule.os.PSObject.Properties['name'] -and $rule.os.name -ne 'windows'){continue}
     if($rule.os.PSObject.Properties['arch'] -and 'amd64' -notmatch $rule.os.arch){continue}
     if($rule.os.PSObject.Properties['version'] -and [Environment]::OSVersion.Version.ToString() -notmatch $rule.os.version){continue}
    }
    if($rule.PSObject.Properties['features'] -and @($rule.features.PSObject.Properties|Where-Object Value).Count){continue}
    $allowed=$rule.action -eq 'allow'
   }
  }
  if(!$allowed){continue}
  $paths=@()
  if($library.PSObject.Properties['downloads']){
   if($library.downloads.PSObject.Properties['artifact']){$paths+=@($library.downloads.artifact.path)}
   if($library.PSObject.Properties['natives'] -and $library.natives.PSObject.Properties['windows']){
    $key=$library.natives.windows.Replace('${arch}','64')
    if($library.downloads.PSObject.Properties['classifiers'] -and $library.downloads.classifiers.PSObject.Properties[$key]){$paths+=@($library.downloads.classifiers.$key.path)}else{return $false}
   }
  }else{
   $coordinates=$library.name.Split(':')
   if($coordinates.Count -lt 3){return $false}
   $suffix=if($coordinates.Count -gt 3){'-'+$coordinates[3]}else{''}
   $paths+=($coordinates[0].Replace('.','/')+'/'+$coordinates[1]+'/'+$coordinates[2]+'/'+$coordinates[1]+'-'+$coordinates[2]+$suffix+'.jar')
  }
  $prefix=[IO.Path]::GetFullPath((Join-Path $Root 'libraries'))+[IO.Path]::DirectorySeparatorChar
  foreach($path in $paths){
   if(!$path){return $false}
   $file=[IO.Path]::GetFullPath((Join-Path $Root ('libraries/'+$path)))
   if(!$file.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -or !(Test-Path -LiteralPath $file)){return $false}
  }
 }
 return $true
}
