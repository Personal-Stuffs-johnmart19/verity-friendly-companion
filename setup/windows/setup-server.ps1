# Windows first-run setup. No game launch, inference, world edits or key arguments.
[CmdletBinding()]
param(
 [string]$ServerRoot,
 [string]$AddonJar,
 [string]$CacheDirectory,
 [ValidateSet('google','local')][string]$ChatProvider='google',
 [switch]$NonInteractive,
 [switch]$ConfirmFreeProject,
 [switch]$ConfirmCloudTranscription,
 [switch]$CheckOnly,
 [switch]$UpdateService,
 [switch]$TextOnly,
 [switch]$StartBridge,
 [switch]$ReplaceGoogleKey,
 [string]$JavaHome,
 [ValidateSet('keep','native','bridge')][string]$RuntimeMode='keep',
 [switch]$Offline,
 [switch]$SkipModelDownloads,
 [switch]$OptionalBackends,
 [ValidateSet('keep','auto','cpu','cuda','native-cuda','native-cpu','vulkan','rocm','google','disabled')][string]$SttBackend='keep',
 [ValidateSet('keep','google','silero','drama3','disabled')][string]$TtsBackend='keep',
 [string]$RocmPython,
 [string]$PythonExecutable,
 [string]$VenvPath,
 [switch]$UseExistingVenv,
 [switch]$InstallPythonPackages,
 [string]$PythonVersion='3.12',
 [ValidateSet('auto','locked','compatible','existing')][string]$DependencyMode='auto',
 [switch]$VulkanFallback,
 [ValidateSet('keep','on','off')][string]$CpuFallback='keep',
 [switch]$DownloadLargeModels,
 [string]$ReferenceConfig,
 [switch]$SetupLocalChat,
 [string]$LocalModel
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$source=$PSScriptRoot
$layout=Join-Path $PSScriptRoot 'source-layout.ps1'
if(!(Test-Path -LiteralPath $layout)){$layout=Join-Path (Split-Path $PSScriptRoot) 'source-layout.ps1'}
. $layout
if($ReplaceGoogleKey -and $NonInteractive){throw '-ReplaceGoogleKey requires an interactive hidden prompt; keys are never accepted as CLI arguments'}
if(!$ServerRoot){
 if((Split-Path $source -Leaf) -eq 'verity-service'){$ServerRoot=Split-Path $source}
 else{throw 'Pass -ServerRoot with the Minecraft server directory. Run on the server host, never on a friend''s client.'}
}
$server=[IO.Path]::GetFullPath($ServerRoot)
$service=Join-Path $server 'verity-service'
$configRoot=Join-Path $server 'config'
$utf8=[Text.UTF8Encoding]::new($false)
. (Join-Path $source 'dependency-tools.ps1')
if(!$CacheDirectory){$CacheDirectory=Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'VerityFriendlyCompanion/server-tools'}
$cache=[IO.Path]::GetFullPath($CacheDirectory)
function Save-NewJson([string]$Path,$Value){if(!(Test-Path -LiteralPath $Path)){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 20),$utf8)}}
function Read-Json([string]$Path){try{return [IO.File]::ReadAllText($Path)|ConvertFrom-Json}catch{throw "Invalid JSON; existing file preserved: $Path"}}
function Assert-Mods {$problems=@(Get-VfcModProblems $server);if($problems.Count){throw ($problems -join "`n")};Write-Host 'PASS all required mod dependencies'}
function Get-VerifiedDownload([string]$Url,[string]$Name,[string]$Sha256){return Get-VfcDownload $Url $Name $Sha256 $cache -Offline:$Offline}
function Assert-VfcPythonEnvironmentIdle([string]$EnvironmentRoot){
 $absolute=[IO.Path]::GetFullPath($EnvironmentRoot)
 # Alias paths could hide a process using the same physical environment.
 $ancestor=$absolute
 while($ancestor -and (Test-Path -LiteralPath $ancestor)){
  if((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Package changes through a linked venv path are refused. Select its real directory or keep the environment check-only.'}
  $parent=Split-Path $ancestor
  if($parent -eq $ancestor){break};$ancestor=$parent
 }
 $prefix=$absolute.Replace('\','/').TrimEnd('/')+'/'
 foreach($process in @(Get-Process -ErrorAction Stop)){
  $executable=$null
  try{$executable=$process.Path}catch{}
  if($executable -and ([IO.Path]::GetFullPath($executable).Replace('\','/')).StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)){
   throw 'The selected Python venv is in use. Finish active work and stop its worker yourself, or choose a new isolated environment. No process was stopped.'
  }
  if(!$executable -and $process.ProcessName -match '^python(?:w|[0-9.]*)?$'){
   throw 'Cannot verify whether a running Python process uses the selected venv. Keep this environment check-only, stop its worker yourself, or rerun with access to process metadata.'
  }
 }
}
if($CheckOnly){
 $expected=Get-VfcAddonVersion (Get-VfcBundledAddon $source)
 $problems=@(Get-VfcModProblems $server $expected)
 if(!(Get-VfcJava $cache $JavaHome -CheckOnly)){$problems+='Java 21 JDK missing; rerun automatic setup'}
 if(!(Test-VfcServerLibraries $server (Get-VfcServerArgs $server))){$problems+='Compatible NeoForge server libraries missing/incomplete; rerun automatic setup'}
 if((Test-VfcNeedsPython $server) -and !(Test-VfcNativeRuntime)){$problems+='Microsoft Visual C++ x64 native runtime missing; rerun setup'}
 $python=Join-Path $service '.venv/Scripts/python.exe'
 $checkVoicePath=Join-Path $service 'voice-config.json'
 if(Test-Path -LiteralPath $checkVoicePath){$checkVoice=Read-Json $checkVoicePath;if($checkVoice.PSObject.Properties['python_executable'] -and $checkVoice.python_executable){$python=[string]$checkVoice.python_executable}}
 $problems+=@(Get-VfcNativeSettingsProblems $server)
 if(!(Test-VfcNeedsPython $server)){$pythonFailed=$false}
 elseif(Test-Path -LiteralPath $python){& $python -B (Join-Path $service 'check-setup.py');$pythonFailed=$LASTEXITCODE -ne 0}else{Write-Warning 'Python environment missing; rerun automatic setup';$pythonFailed=$true}
 foreach($problem in $problems){Write-Warning $problem}
 if($problems.Count -or $pythonFailed){throw 'Dependency/setup checks need attention; see every specific hint above'}
 Write-Host 'PASS server dependencies/configuration; no generation or provider requests were performed.'
 return
}
if([Environment]::OSVersion.Platform -ne 'Win32NT' -or [Environment]::GetEnvironmentVariable('PROCESSOR_ARCHITECTURE') -ne 'AMD64' -or $env:PROCESSOR_ARCHITEW6432 -eq 'ARM64'){throw 'Automatic setup supports Windows x64; see docs/SERVER.md for the manual Python route on other systems.'}
New-Item -ItemType Directory -Force -Path $service,$configRoot,(Join-Path $server 'mods')|Out-Null
$JavaHome=Get-VfcJava $cache $JavaHome -Offline:$Offline
$loaderArgs=Install-VfcServerLoader $server $JavaHome $cache -Offline:$Offline
if(!$AddonJar){$AddonJar=Get-VfcBundledAddon $source}
Install-VfcMods $server $AddonJar $cache -Offline:$Offline
$runtime=@{javaHome=$JavaHome;neoforgeArgs=$loaderArgs.Substring($server.Length).TrimStart('\','/')}
[IO.File]::WriteAllText((Join-Path $configRoot 'verity-runtime.json'),($runtime|ConvertTo-Json),$utf8)
if(!(Test-Path -LiteralPath (Join-Path $server 'eula.txt'))){[IO.File]::WriteAllText((Join-Path $server 'eula.txt'),"# Review https://aka.ms/MinecraftEULA before accepting.`neula=false`n",$utf8)}
Install-VfcLaunchers $server $source -UpdateService:$UpdateService
# Refuse mixed source versions unless the host explicitly requests an update.
$files=@(Get-VfcServiceFiles)
foreach($file in $files){
 $destination=Join-Path $service $file.Name
 if($file.FullName -eq $destination){continue}
 if((Test-Path -LiteralPath $destination) -and (Get-FileHash -LiteralPath $file.FullName).Hash -ne (Get-FileHash -LiteralPath $destination).Hash -and !$UpdateService){throw "Existing service code differs. Stop your bridge and use -UpdateService to replace code while preserving settings and secrets: $destination"}
}
foreach($file in $files){$destination=Join-Path $service $file.Name;if($file.FullName -ne $destination){Copy-Item -LiteralPath $file.FullName -Destination $destination -Force}}
$voicePath=Join-Path $service 'voice-config.json'
if(!(Test-Path -LiteralPath $voicePath)){
 $example=Read-Json (Resolve-VfcSourceFile 'voice-config.example.json')
 if($TextOnly){$example.provider='disabled';$example.stt_engine='disabled'}
 Save-NewJson $voicePath $example
}
$voice=Read-Json $voicePath
if($OptionalBackends -and !$NonInteractive){
 Write-Host 'Optional local backends (Enter keeps current settings). No model is loaded during setup.'
 if((Read-Host 'Install/check LM Studio local chat too? Type yes, or Enter to keep current chat') -ceq 'yes'){$SetupLocalChat=$true}
 if($SetupLocalChat){$LocalModel=Read-Host 'Optional model search/download (for example qwen3-4b@q4_k_m); Enter keeps existing downloaded models'}
 $selected=Read-Host 'Recognition: google (no Python), auto (NVIDIA CUDA/other Vulkan), native-cuda (NVIDIA native), native-cpu (native Qwen), cuda (legacy Python), vulkan (AMD/Intel/NVIDIA Qwen3-ASR ~1.8 GiB), cpu (Whisper ~0.5 GB), rocm (AMD), disabled, or Enter'
 if($selected){if($selected -notin 'auto','cpu','cuda','native-cuda','native-cpu','vulkan','rocm','google','disabled'){throw 'Choose google, auto, cuda, vulkan, cpu, rocm or disabled'};$SttBackend=$selected}
 if($SttBackend -in 'cuda','rocm'){$VulkanFallback=(Read-Host 'Also prepare Vulkan GPU fallback (~1.8 GiB model plus native runtime)? y/N') -eq 'y'}
 $selected=Read-Host 'Voice: google, silero (CPU PyTorch + RU/EN models), drama3 (~23 GB + your reference WAVs; Vulkan driver required), disabled, or Enter'
 if($selected){if($selected -notin 'google','silero','drama3','disabled'){throw 'Unsupported voice backend'};$TtsBackend=$selected}
 if($SttBackend -eq 'rocm'){$RocmPython=Read-Host 'Existing ROCm Python path (Enter installs an isolated AMD ROCm 10.1/PyTorch 2.14.0 environment with automatic GFX detection; compatible AMD driver required)'}
 if($TtsBackend -eq 'drama3'){$DownloadLargeModels=(Read-Host 'Confirm the ~23 GB Drama 3 download if missing: type yes') -ceq 'yes'}
}
if($SttBackend -eq 'auto'){
 $gpuNames=@(Get-CimInstance Win32_VideoController -ErrorAction SilentlyContinue | ForEach-Object {$_.Name})
 $SttBackend=if(($gpuNames -join ' ') -match 'NVIDIA'){'native-cuda'}else{'vulkan'}
 Write-Host ('Detected graphics: '+($gpuNames -join ', ')+'. Selected '+$SttBackend+'. Override with -SttBackend if needed.')
}

foreach($kind in @('server','gameplay','horror')){
 $destination=Join-Path $configRoot ('verity-companion-'+$kind+'.json')
 if(!(Test-Path -LiteralPath $destination)){Copy-Item -LiteralPath (Resolve-VfcSourceFile ('verity-companion-'+$kind+'.example.json')) -Destination $destination}
 [void](Read-Json $destination)
}
$bridgeConfigPath=Join-Path $configRoot 'verity-companion-server.json'
$bridgeConfig=Read-Json $bridgeConfigPath
if($RuntimeMode -ne 'keep'){$bridgeConfig|Add-Member -NotePropertyName runtime -NotePropertyValue $RuntimeMode -Force;[IO.File]::WriteAllText($bridgeConfigPath,($bridgeConfig|ConvertTo-Json -Depth 20),$utf8)}
$selectedStt=if($SttBackend -eq 'keep'){Get-VfcEffectiveStt $server $voice}elseif($SttBackend -eq 'cpu'){'faster-whisper'}else{$SttBackend}
if($selectedStt -eq 'google' -and (!$voice.PSObject.Properties['google_audio_transcription_confirmed'] -or !$voice.google_audio_transcription_confirmed)){
 $audioConfirmed=$ConfirmCloudTranscription.IsPresent
 if(!$audioConfirmed -and !$NonInteractive){$audioConfirmed=(Read-Host 'Allow uploading players microphone recordings to Google for transcription? Type yes; Enter keeps it disabled') -ceq 'yes'}
 if($audioConfirmed){$voice|Add-Member -NotePropertyName google_audio_transcription_confirmed -NotePropertyValue $true -Force;[IO.File]::WriteAllText($voicePath,($voice|ConvertTo-Json -Depth 20),$utf8)}
}
if($SttBackend -in 'google','disabled'){$voice|Add-Member -NotePropertyName stt_engine -NotePropertyValue $SttBackend -Force;[IO.File]::WriteAllText($voicePath,($voice|ConvertTo-Json -Depth 20),$utf8)}
if($TtsBackend -in 'google','disabled'){$voice|Add-Member -NotePropertyName provider -NotePropertyValue $TtsBackend -Force;[IO.File]::WriteAllText($voicePath,($voice|ConvertTo-Json -Depth 20),$utf8)}
$private=Join-Path $service '.secrets';New-Item -ItemType Directory -Force -Path $private|Out-Null
# Keep keys private to this Windows account; never echo their contents.
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $private /inheritance:r /grant:r ('*'+$sid+':(OI)(CI)F')|Out-Null
if($LASTEXITCODE){throw 'Cannot secure the server credential directory'}
$token=Join-Path $private 'bridge-token'
if(!(Test-Path -LiteralPath $token)){
 $bytes=New-Object byte[] 32;$rng=[Security.Cryptography.RandomNumberGenerator]::Create()
 try{$rng.GetBytes($bytes)}finally{$rng.Dispose()}
 [IO.File]::WriteAllText($token,[Convert]::ToBase64String($bytes),$utf8)
}
if(![IO.File]::ReadAllText($token).Trim()){throw "Existing bridge-token is empty; repair $token. It has not been replaced."}
$routingPath=Join-Path $service 'chat-routing.json'
if(!(Test-Path -LiteralPath $routingPath)){
 if($ChatProvider -eq 'local'){Save-NewJson $routingPath @{provider='local'}}
 else{Save-NewJson $routingPath @{provider='google'}}
}
$routing=Read-Json $routingPath
if($routing.provider -eq 'local' -and !(Get-VfcLms)){$SetupLocalChat=$true}
if($SetupLocalChat){
 $lms=Install-VfcLms $cache -Offline:$Offline
 if($LocalModel){if($NonInteractive){throw 'Model search/quantization requires interactive confirmation; pre-download a model for unattended local setup'};& $lms get $LocalModel --gguf --limit 5;if($LASTEXITCODE){throw 'LM Studio model download did not finish; existing chat routing was preserved'}}
 $routing=[pscustomobject]@{provider='local'}
 Write-Host 'LM Studio installed. No model was loaded; start its Model Server and select a downloaded model with /llm local in game.'
}
$selectedProvider=if($TtsBackend -eq 'keep'){if($voice.PSObject.Properties['provider']){$voice.provider}else{'google'}}else{$TtsBackend}
$selectedLocalFallback=Get-VfcLocalFallback $voice $selectedProvider
$nativeDramaNeeded=$selectedProvider -eq 'drama3' -or $selectedLocalFallback -eq 'drama3'
$nativeVulkanFallbackNeeded=$selectedStt -in 'cuda','rocm' -and ($VulkanFallback -or ($voice.PSObject.Properties['stt_vulkan_fallback'] -and $voice.stt_vulkan_fallback))
$needsGoogle=$routing.provider -ne 'local' -or $selectedProvider -eq 'google' -or $selectedStt -eq 'google'
$keyFile=Join-Path $private 'google-api-key'
function Test-GoogleKey {
 if(!(Test-Path -LiteralPath $keyFile)){return $false}
 $value=[IO.File]::ReadAllText($keyFile).Trim()
 return $value.Length -ge 16 -and $value.Length -le 512 -and $value -cmatch '^[\x21-\x7e]+$' -and $value -notmatch '^(your_|paste_|replace_)'
}
if($needsGoogle -and ($ReplaceGoogleKey -or !(Test-GoogleKey))){
 if(!$NonInteractive){
  Write-Host "Google key will be saved ONLY on the server: $keyFile"
  $secure=Read-Host 'Google API key (hidden; Enter to configure later)' -AsSecureString
  $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try{$plain=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer).Trim();if($plain){[IO.File]::WriteAllText($keyFile,$plain,$utf8)}}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer);$plain=$null;$secure.Dispose()}
 }
 if(!(Test-GoogleKey)){Write-Warning "Google is not configured. Rerun this setup or save a valid key in $keyFile. Local LM Studio chat does not need a Google key."}
}
if($needsGoogle -and (!$voice.PSObject.Properties['google_free_project_confirmed'] -or $voice.google_free_project_confirmed -ne $true)){
 $confirmed=$ConfirmFreeProject.IsPresent
 if(!$confirmed -and !$NonInteractive){$confirmed=(Read-Host 'Have you confirmed this Google project has NO billing? Type yes to enable cloud calls') -ceq 'yes'}
 if($confirmed){$voice|Add-Member -NotePropertyName google_free_project_confirmed -NotePropertyValue $true -Force;[IO.File]::WriteAllText($voicePath,($voice|ConvertTo-Json -Depth 20),$utf8)}
 else{Write-Warning "Cloud calls remain disabled. Confirm your free project in $voicePath or rerun setup. A key alone does not enable cloud calls."}
}
$requiresPython=($selectedStt -notin 'google','disabled','vulkan','native-cuda','native-cpu' -or $selectedProvider -notin 'google','disabled','drama3' -or ($selectedLocalFallback -notin 'disabled','drama3'))
$previousAudioSettings=[IO.File]::ReadAllText($voicePath)
 . (Join-Path $service 'native-audio-assets.ps1')
 if($selectedStt -in 'vulkan','native-cuda','native-cpu' -or $nativeDramaNeeded -or $nativeVulkanFallbackNeeded){
  if($SkipModelDownloads){Write-Warning 'Native audio preparation deferred; selected changes were not activated';return}
  if($nativeDramaNeeded -and !$ReferenceConfig -and !$NonInteractive){
   $dramaDirectory=if($voice.PSObject.Properties['drama3_directory']){Resolve-VfcAudioPath $service $voice.drama3_directory}else{Resolve-VfcAudioPath $service '../verity-tts'}
   if(!(Test-Path -LiteralPath (Join-Path $dramaDirectory 'voices/reference-text.json'))){
    $refs=@{};foreach($language in @('ru','en')){$audio=Read-Host ($language+' PCM16 WAV path (1-30 seconds; kept unchanged)');$text=Read-Host ($language+' exact transcript');$refs[$language]=@{audio=$audio;text=$text}}
    $ReferenceConfig=Join-Path $private 'setup-reference.json';Write-VfcAudioJson $ReferenceConfig $refs
   }
  }
  if($CpuFallback -ne 'keep'){$voice|Add-Member -NotePropertyName stt_cpu_fallback -NotePropertyValue ($CpuFallback -eq 'on') -Force}
  $nativeSttToPrepare=if($nativeVulkanFallbackNeeded){'vulkan'}else{$selectedStt}
  $voice=Initialize-VfcNativeAudio $service $voice $cache $nativeSttToPrepare $selectedProvider $ReferenceConfig -Offline:$Offline -AllowLarge:$DownloadLargeModels
  if($nativeVulkanFallbackNeeded){
   $voice|Add-Member -NotePropertyName stt_engine -NotePropertyValue $selectedStt -Force
   $voice|Add-Member -NotePropertyName stt_vulkan_fallback -NotePropertyValue $true -Force
  }
  Write-VfcAudioJson $voicePath $voice
 }
if(!$requiresPython){
 if($SetupLocalChat){[IO.File]::WriteAllText($routingPath,($routing|ConvertTo-Json),$utf8)}
 $problems=@(Get-VfcNativeSettingsProblems $server)
 foreach($problem in $problems){Write-Warning $problem}
 if($problems.Count){[IO.File]::WriteAllText($voicePath,$previousAudioSettings,$utf8);throw 'Native provider setup needs attention; review the specific hints above'}
 Write-Host 'Native Java/audio setup complete. No Python, Whisper, PyTorch or voice bridge is required. Start Minecraft with run-verity-server.cmd.'
 return
}
try{
$voice|Add-Member -NotePropertyName stt_engine -NotePropertyValue $selectedStt -Force
Write-VfcAudioJson $voicePath $voice
Install-VfcNativeRuntime $cache -NonInteractive:$NonInteractive -Offline:$Offline
[Net.ServicePointManager]::SecurityProtocol=[Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$uvZip=Get-VerifiedDownload 'https://github.com/astral-sh/uv/releases/download/0.13.6/uv-x86_64-pc-windows-msvc.zip' 'uv-0.13.6-windows-x64.zip' 'a2a8820d5eea32f08ef467ef3b7f66883a9b076d5737a2fc2fbb5f458d1bb50f'
$uvDirectory=Join-Path $cache 'uv-0.13.6'
if(!(Test-Path -LiteralPath (Join-Path $uvDirectory 'uv.exe'))){Expand-Archive -LiteralPath $uvZip -DestinationPath $uvDirectory -Force}
$uv=Join-Path $uvDirectory 'uv.exe'
$offlineUv=@();if($Offline){$offlineUv+='--offline'}
$savedPython=$env:UV_PYTHON_INSTALL_DIR;$savedCache=$env:UV_CACHE_DIR;$savedBin=$env:UV_PYTHON_BIN_DIR
try{
 $env:UV_PYTHON_INSTALL_DIR=Join-Path $cache 'python';$env:UV_CACHE_DIR=Join-Path $cache 'cache';$env:UV_PYTHON_BIN_DIR=Join-Path $cache 'bin'
 $venvRoot=if($VenvPath){[IO.Path]::GetFullPath($VenvPath)}elseif($voice.PSObject.Properties['python_executable'] -and $voice.python_executable){Split-Path (Split-Path ([string]$voice.python_executable))}else{Join-Path $service '.venv'}
 $python=Join-Path $venvRoot 'Scripts/python.exe'
 $reusedPython=Test-Path -LiteralPath $python -PathType Leaf
 $customPython=$venvRoot -ne (Join-Path $service '.venv')
 if($UseExistingVenv -and !$reusedPython){throw '-UseExistingVenv requires an existing isolated venv; select its root with -VenvPath.'}
 if(!$reusedPython){
  if((Test-Path -LiteralPath $venvRoot) -and @(Get-ChildItem -LiteralPath $venvRoot -Force).Count){throw 'Selected venv directory is not empty; it was preserved. Choose a new directory or an existing isolated venv.'}
  $pythonSelection=if($PythonExecutable){[IO.Path]::GetFullPath($PythonExecutable)}else{$PythonVersion}
  & $uv --no-config @offlineUv venv --python $pythonSelection $venvRoot --no-progress
  if($LASTEXITCODE){throw 'Python environment creation failed'}
 }
 $readOnlyReuse=$reusedPython -and ($customPython -or $UseExistingVenv) -and !$InstallPythonPackages
 $effectiveDependencyMode=if($readOnlyReuse){'existing'}else{$DependencyMode}
 if($effectiveDependencyMode -ne 'existing'){
  Assert-VfcPythonEnvironmentIdle $venvRoot
  if($selectedStt -eq 'rocm'){
   $managedRocm=Join-Path $service '.venv-rocm'
   if(Test-Path -LiteralPath $managedRocm){Assert-VfcPythonEnvironmentIdle $managedRocm}
  }
 }
 $dependencyArgs=@('--service',$service,'--uv',$uv,'--venv-root',$venvRoot,'--mode',$effectiveDependencyMode,'--stt',$selectedStt,'--tts',$selectedProvider)
 if($readOnlyReuse){$dependencyArgs+='--reused'}
 if($InstallPythonPackages){$dependencyArgs+='--install-packages'}
 if($Offline){$dependencyArgs+='--offline'}
 $enableCpuFallback=if($CpuFallback -eq 'on'){$true}elseif($CpuFallback -eq 'off'){$false}else{!$voice.PSObject.Properties['stt_cpu_fallback'] -or $voice.stt_cpu_fallback}
 if($enableCpuFallback){$dependencyArgs+='--cpu-fallback'}
 if($VulkanFallback -or ($voice.PSObject.Properties['stt_vulkan_fallback'] -and $voice.stt_vulkan_fallback)){$dependencyArgs+='--native-fallback'}
 if($selectedLocalFallback -eq 'silero'){$dependencyArgs+='--local-fallback'}
 $pythonInfo=& $python -B (Join-Path $service 'python_environment.py') @dependencyArgs
 if($LASTEXITCODE){throw 'Selected Python dependencies need attention. Existing environments are preserved; choose -DependencyMode compatible or existing, or create a separate service environment with -PythonExecutable/-PythonVersion.'}
 $pythonSelectionInfo=($pythonInfo -join "`n")|ConvertFrom-Json
 $packageArgs=@('--prepare','--packages-only','--uv',$uv,'--stt',$SttBackend,'--tts',$TtsBackend,'--dependency-mode',$effectiveDependencyMode,'--cpu-fallback',$CpuFallback)
 if($Offline){$packageArgs+='--offline'}
 if($RocmPython){$packageArgs+=@('--rocm-python',$RocmPython)}
 if($VulkanFallback){$packageArgs+='--vulkan-fallback'}
 & $python -B (Join-Path $service 'backend_setup.py') @packageArgs
 if($LASTEXITCODE){throw 'Selected Python framework packages are incomplete. Model files were not downloaded; existing settings were preserved.'}
 $voice|Add-Member -NotePropertyName python_executable -NotePropertyValue ([string]$pythonSelectionInfo.python_executable) -Force
 Write-VfcAudioJson $voicePath $voice
}finally{$env:UV_PYTHON_INSTALL_DIR=$savedPython;$env:UV_CACHE_DIR=$savedCache;$env:UV_PYTHON_BIN_DIR=$savedBin}
Write-Host 'Python ready. Preparing selected model files without inference or cloud generation.'
if(!$SkipModelDownloads){
 $native=$null
 $needsVulkan=$selectedStt -eq 'vulkan' -or ($selectedStt -in 'cuda','rocm' -and ($VulkanFallback -or ($voice.PSObject.Properties['stt_vulkan_fallback'] -and $voice.stt_vulkan_fallback)))
 function Resolve-ServicePath([string]$Value){if([IO.Path]::IsPathRooted($Value)){return [IO.Path]::GetFullPath($Value)};return [IO.Path]::GetFullPath((Join-Path $service $Value))}
 $needsNativeArchive=$false
 if($needsVulkan){$needsNativeArchive=(!$voice.PSObject.Properties['stt_native_executable'] -or !(Test-Path -LiteralPath (Resolve-ServicePath $voice.stt_native_executable) -PathType Leaf))}
 if($selectedProvider -eq 'drama3'){
  $dramaFolder=if($voice.PSObject.Properties['drama3_directory']){Resolve-ServicePath $voice.drama3_directory}else{Resolve-ServicePath '../verity-tts'}
  $dramaExecutable=if($voice.PSObject.Properties['drama3_executable']){Resolve-ServicePath $voice.drama3_executable}else{Join-Path $dramaFolder 'runtime/audiocpp_server.exe'}
  $needsNativeArchive=$needsNativeArchive -or !(Test-Path -LiteralPath $dramaExecutable -PathType Leaf)
 }
 if($needsNativeArchive){$native=Get-VerifiedDownload 'https://github.com/0xShug0/audio.cpp/releases/download/v0.9.3/audio-v0.9.3-bin-windows-x64-vulkan-portable.zip' 'audio-v0.9.3-bin-windows-x64-vulkan-portable.zip' '51f8d8ce6cc7789bc9dc3d15f35e05f143aaf2ce1cbecc525a4104bcdde82a42'}
 $createdReference=$false
 if($nativeDramaNeeded -and !$ReferenceConfig -and !$NonInteractive){
  $dramaRelative=if($voice.PSObject.Properties['drama3_directory']){$voice.drama3_directory}else{'../verity-tts'};$dramaDirectory=[IO.Path]::GetFullPath((Join-Path $service $dramaRelative))
  if(!(Test-Path -LiteralPath (Join-Path $dramaDirectory 'voices/reference-text.json'))){
   Write-Host 'Drama 3 requires your own short reference recordings and exact transcripts; these stay on the server.'
   $refs=@{}
   foreach($language in @('ru','en')){$audio=Read-Host ($language+' reference audio file (WAV/MP3/OGG, 1-30 seconds)');$text=Read-Host ($language+' exact words in that recording');$refs[$language]=@{audio=$audio;text=$text}}
   $ReferenceConfig=Join-Path $private 'setup-reference.json';[IO.File]::WriteAllText($ReferenceConfig,($refs|ConvertTo-Json -Depth 4),$utf8);$createdReference=$true
  }
 }
 $backendArgs=@('--prepare','--uv',$uv,'--stt',$SttBackend,'--tts',$TtsBackend,'--dependency-mode',$effectiveDependencyMode,'--cpu-fallback',$CpuFallback)
 if($VulkanFallback){$backendArgs+='--vulkan-fallback'}
 if($Offline){$backendArgs+='--offline'}
 if($DownloadLargeModels){$backendArgs+='--allow-large'}
 if($RocmPython){$backendArgs+=@('--rocm-python',$RocmPython)}
 if($native){$backendArgs+=@('--native-archive',$native)}
 if($ReferenceConfig){$backendArgs+=@('--reference-config',[IO.Path]::GetFullPath($ReferenceConfig))}
 try{& $python (Join-Path $service 'backend_setup.py') @backendArgs;$backendExit=$LASTEXITCODE}finally{if($createdReference -and (Test-Path -LiteralPath $ReferenceConfig)){Remove-Item -LiteralPath $ReferenceConfig}}
 if($backendExit){throw 'Selected backend dependencies are not complete; settings were preserved. See the exact repair hints above.'}
}else{Write-Warning 'Model downloads deferred explicitly; voice is not dependency-ready yet'}
if($SetupLocalChat){[IO.File]::WriteAllText($routingPath,($routing|ConvertTo-Json),$utf8)}
Assert-Mods
$nativeProblems=@(Get-VfcNativeSettingsProblems $server)
foreach($problem in $nativeProblems){Write-Warning $problem}
if($nativeProblems.Count){throw 'Native provider configuration needs attention; see the specific setup hints above'}
& $python -B (Join-Path $service 'check-setup.py')
if($LASTEXITCODE){throw 'Prepared the service, but setup needs attention. Fix the specific hints above and rerun this script.'}
if($SkipModelDownloads){Write-Warning 'Active settings were checked; selected audio changes remain deferred. Rerun without -SkipModelDownloads to prepare and activate them.'}
else{Write-Host "Setup complete. Server launcher: $server/run-verity-server.cmd . Use setup-client.cmd for friend clients."}
if($StartBridge){& (Join-Path $service 'start-bridge.ps1') -ServerRoot $server}

}catch{[IO.File]::WriteAllText($voicePath,$previousAudioSettings,$utf8);throw}
