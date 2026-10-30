[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$SourceRoot,[ValidateSet('basic','advanced')][string]$Profile,[string]$ServerRoot,[string]$ClientRoot,[string]$MinecraftRoot,[string]$AddonJar,[string]$JavaHome,[string]$CacheDirectory,[string]$ExportDirectory,[string]$VenvPath,[switch]$UseExistingVenv,[switch]$InstallPythonPackages,[string]$PythonExecutable,[string]$PythonVersion='3.12',[ValidateSet('auto','locked','compatible','existing')][string]$DependencyMode='auto',[string]$SttBackend='keep',[string]$TtsBackend='keep',[switch]$SetupLocalChat,[string]$LocalModel,[switch]$VulkanFallback,[ValidateSet('keep','on','off')][string]$CpuFallback='keep',[switch]$StandaloneServer,[switch]$Offline,[switch]$NonInteractive,[switch]$Plan,[switch]$Discover,[switch]$PrepareModels,[switch]$DownloadLargeModels)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'windows-environment.ps1')
. (Join-Path $PSScriptRoot 'windows-basic.ps1')
function Read-VfcChoice([string]$Title,[object[]]$Items){
 Write-Host "`n$Title" -ForegroundColor Cyan
 for($i=0;$i -lt $Items.Count;$i++){Write-Host ('  '+($i+1)+') '+$Items[$i].Label)}
 while($true){$answer=Read-Host 'Number (q to cancel)';if($answer -eq 'q'){throw [OperationCanceledException]::new('Setup cancelled; no further actions will run')};$number=0;if([int]::TryParse($answer,[ref]$number) -and $number -ge 1 -and $number -le $Items.Count){return $Items[$number-1].Value};Write-Host 'Choose a listed number.'}
}
function Read-VfcYes([string]$Question){return (Read-Host ($Question+' [yes/no; default no]')) -ceq 'yes'}
$jdks=@(Get-VfcDiscoveredJdks);$hosts=@(Get-VfcDiscoveredHosts);$hardware=Get-VfcHardwareOptions
$advancedHelper=$null;try{$advancedHelper=Resolve-VfcWindowsFile $SourceRoot 'setup-server.ps1'}catch{}
if($Discover){[pscustomobject]@{jdks=$jdks;installations=$hosts;hardware=$hardware}|ConvertTo-Json -Depth 12;return}
if(!$Profile -and !$NonInteractive -and !$Plan){
 Write-Host 'Verity Friendly Companion setup' -ForegroundColor Green
 Write-Host 'Basic installs only game/mod dependencies. Advanced adds explicitly selected local engines. Nothing starts Minecraft or models.'
 $profiles=@(@{Label='Basic: JAR-only cloud / existing LM Studio';Value='basic'})
 if($advancedHelper){$profiles+=@{Label='Advanced: optional local chat and speech engines';Value='advanced'}}
 if(Test-Path -LiteralPath (Join-Path $SourceRoot 'build.gradle')){$profiles+=@{Label='Compile only';Value='build'}}
 $selection=Read-VfcChoice 'Choose setup' $profiles
 if($selection -eq 'build'){& (Resolve-VfcWindowsFile $SourceRoot 'setup.ps1') -BuildOnly -JavaHome $JavaHome -CacheDirectory $CacheDirectory -Offline:$Offline;return}
 $Profile=$selection
}
if(!$Profile){$Profile='basic'}
if($Profile -eq 'advanced' -and !$advancedHelper){throw 'Advanced setup needs the complete source checkout or server-service ZIP; this client-only bundle was not changed.'}
if($Profile -eq 'basic' -and ($SttBackend -ne 'keep' -or $TtsBackend -ne 'keep' -or $VenvPath -or $UseExistingVenv -or $InstallPythonPackages -or $PrepareModels -or $DownloadLargeModels -or $SetupLocalChat -or $LocalModel -or $VulkanFallback -or $CpuFallback -ne 'keep')){throw 'Local backend preparation options need -Profile advanced. Basic installs only the addon/game dependencies.'}
if(!$ServerRoot -and !$ClientRoot -and !$ExportDirectory -and !$Plan){
 if($NonInteractive){throw 'Choose -ServerRoot, -ClientRoot or -ExportDirectory for unattended installation'}
 $destinations=@();if($Profile -eq 'basic'){$destinations+=@{Label='Export only the addon JAR (no Minecraft installation needed)';Value='export'}}
 foreach($hostInfo in $hosts|Where-Object {$_.Compatible -and ($Profile -ne 'advanced' -or $_.Kind -eq 'server')}){$destinations+=@{Label=($hostInfo.Kind+': '+$hostInfo.Path+' ['+$hostInfo.Loader+']');Value=$hostInfo}}
 $destinations+=@{Label='Choose an existing client/server path';Value='manual'};$destinations+=@{Label='Create a standalone NeoForge server in an empty directory';Value='server'}
 $selected=Read-VfcChoice 'Choose destination (detected installations are never selected automatically)' $destinations
 if($selected -is [string]){switch($selected){
  'export'{$ExportDirectory=Read-Host 'Export directory (Enter uses dist/)';if(!$ExportDirectory){$ExportDirectory=Join-Path $SourceRoot 'dist'}}
  'server'{$ServerRoot=Read-Host 'New server directory (must be empty)';if(!$ServerRoot){throw 'A server directory is required'};$StandaloneServer=$true}
  'manual'{$path=Read-Host 'Existing Minecraft instance or NeoForge server directory';$candidate=Get-VfcHostCandidate $path;if(!$candidate -or !$candidate.Compatible){throw 'No compatible Minecraft 1.21.1 / NeoForge 21.1.250+ metadata found. Use a matching launcher instance, export the JAR, or create a standalone server.'};if($candidate.Kind -eq 'server'){$ServerRoot=$candidate.Path}else{$ClientRoot=$candidate.Path;$MinecraftRoot=$candidate.MinecraftRoot}}
 }}elseif($selected.Kind -eq 'server'){$ServerRoot=$selected.Path}else{$ClientRoot=$selected.Path;$MinecraftRoot=$selected.MinecraftRoot}
}
if($Profile -eq 'advanced' -and !$ServerRoot -and !$Plan){throw 'Advanced local backends belong on the server host. Select a server destination; friends need only the basic client JAR.'}
if(!$JavaHome -and $jdks.Count){$JavaHome=$jdks[0].Path}
$AddonJar=Get-VfcWizardAddon $SourceRoot $AddonJar
if($Plan){
 [pscustomobject]@{profile=$Profile;addon=if($AddonJar){$AddonJar}else{'Build required from source'};jdk=if($JavaHome){$JavaHome}else{'Private Java 21 JDK will be provisioned if compilation/server setup needs it'};server=$ServerRoot;client=$ClientRoot;export=if($ExportDirectory){$ExportDirectory}elseif(!$ServerRoot -and !$ClientRoot){Join-Path $SourceRoot 'dist'}else{''};standalone_server=[bool]$StandaloneServer;stt=$SttBackend;tts=$TtsBackend;venv=$VenvPath;reuse_venv=[bool]$UseExistingVenv;install_packages=[bool]$InstallPythonPackages;download_models=[bool]$PrepareModels;detected_installations=$hosts;hardware=$hardware;note='Plan only: no installation, downloads, provider requests or model loading.'}|ConvertTo-Json -Depth 12;return
}
if($ServerRoot){Assert-VfcTargetIdle $ServerRoot -IncludeAudio:($Profile -eq 'advanced')};if($ClientRoot){Assert-VfcTargetIdle $ClientRoot}
if($Profile -eq 'advanced' -and !$NonInteractive){
 if(!$SetupLocalChat){$SetupLocalChat=Read-VfcYes 'Install/check LM Studio local chat? This may download LM Studio; no model is loaded'}
 if($SetupLocalChat -and !$LocalModel){$LocalModel=Read-Host 'Optional LM Studio model search/download (Enter keeps existing downloaded models)'}
 if($SttBackend -eq 'keep'){
  $names=@{keep='Keep current recognition settings';disabled='Disable recording recognition';google='Google recognition (explicit host upload consent required)';'native-cpu'='CPU native Qwen ASR (no Python)';cpu='CPU Faster Whisper (Python)';vulkan='Vulkan native Qwen ASR (compatible Vulkan driver)';'native-cuda'='NVIDIA native CUDA Qwen ASR (no Python)';cuda='NVIDIA CUDA Faster Whisper (Python)';rocm='AMD ROCm Whisper (Python; compatible GPU/wheels required)'}
  $items=@(@{Label=$names.keep;Value='keep'});foreach($backend in $hardware.Stt){$items+=@{Label=$names[$backend];Value=$backend}}
  $SttBackend=Read-VfcChoice 'Recognition backend (hardware detection is advisory)' $items
 }
 if($TtsBackend -eq 'keep'){$TtsBackend=Read-VfcChoice 'Speech output' @(@{Label='Keep current speech settings';Value='keep'},@{Label='Google TTS (no local voice model)';Value='google'},@{Label='Silero local CPU voices (Python)';Value='silero'},@{Label='Drama 3 native local voice (large download; reference audio required)';Value='drama3'},@{Label='Text replies only';Value='disabled'})}
 if($SttBackend -in 'cpu','cuda','rocm' -or $TtsBackend -eq 'silero'){
  if(!$VenvPath){$kind=Read-VfcChoice 'Python environment' @(@{Label='Create/use managed server verity-service/.venv';Value='managed'},@{Label='Create a new isolated venv at a custom path';Value='new'},@{Label='Use an existing isolated venv (check-only by default)';Value='existing'});if($kind -ne 'managed'){$VenvPath=Read-Host 'Absolute venv directory';if(!$VenvPath){throw 'A venv directory is required'};$UseExistingVenv=$kind -eq 'existing'}}
  if($UseExistingVenv -and !$InstallPythonPackages){$InstallPythonPackages=Read-VfcYes 'Allow adding/updating packages in this selected environment? No keeps it check-only'}
 }
 if($SttBackend -notin 'keep','google','disabled' -or $TtsBackend -in 'silero','drama3'){$PrepareModels=Read-VfcYes 'Prepare missing local models now? This downloads model weights; no inference runs. No defers local readiness'}
 if($TtsBackend -eq 'drama3' -and $PrepareModels){$DownloadLargeModels=Read-VfcYes 'Drama 3 may download about 23 GiB. Allow this large download?'}
}
Write-Host "`nInstallation summary" -ForegroundColor Cyan
Write-Host ('Profile: '+$Profile);if($ServerRoot){Write-Host ('Server: '+$ServerRoot)};if($ClientRoot){Write-Host ('Client: '+$ClientRoot)};if($ExportDirectory){Write-Host ('Export: '+$ExportDirectory)}
Write-Host ('JAR: '+$(if($AddonJar){$AddonJar}else{'will compile from source with a detected/private JDK'}))
if($Profile -eq 'advanced'){Write-Host ('Recognition: '+$SttBackend+'; TTS: '+$TtsBackend+'; models now: '+[bool]$PrepareModels);Write-Host ('Venv: '+$(if($VenvPath){$VenvPath}else{'managed if needed'})+'; existing environment package changes: '+[bool]$InstallPythonPackages)}
Write-Host 'Existing addon updates are backed up; settings, credentials and worlds are preserved. No game/model will start.'
if(!$NonInteractive -and !(Read-VfcYes 'Continue with these actions?')){Write-Host 'Cancelled.';return}
if(!$AddonJar){
 if(!(Test-Path -LiteralPath (Join-Path $SourceRoot 'build.gradle'))){throw 'This setup package has no bundled addon or source build. Download a complete setup ZIP or source checkout.'}
 & (Resolve-VfcWindowsFile $SourceRoot 'setup.ps1') -BuildOnly -JavaHome $JavaHome -CacheDirectory $CacheDirectory -Offline:$Offline -NonInteractive
 $AddonJar=Get-VfcWizardAddon $SourceRoot ''
 if(!$AddonJar){throw 'The build did not produce an addon JAR'}
}
$basic=@{SourceRoot=$SourceRoot;AddonJar=$AddonJar;ServerRoot=$ServerRoot;ClientRoot=$ClientRoot;MinecraftRoot=$MinecraftRoot;JavaHome=$JavaHome;CacheDirectory=$CacheDirectory;ExportDirectory=$ExportDirectory;Offline=$Offline;NonInteractive=$NonInteractive;StandaloneServer=$StandaloneServer}
Invoke-VfcBasicInstall @basic
if($Profile -eq 'advanced'){
 $advanced=@{ServerRoot=$ServerRoot;AddonJar=$AddonJar;UpdateService=$true;RuntimeMode='native';SttBackend=$SttBackend;TtsBackend=$TtsBackend;SetupLocalChat=$SetupLocalChat;LocalModel=$LocalModel;CpuFallback=$CpuFallback;VulkanFallback=$VulkanFallback;NonInteractive=$NonInteractive;Offline=$Offline;SkipModelDownloads=(!$PrepareModels);DownloadLargeModels=$DownloadLargeModels;VenvPath=$VenvPath;UseExistingVenv=$UseExistingVenv;InstallPythonPackages=$InstallPythonPackages;PythonExecutable=$PythonExecutable;PythonVersion=$PythonVersion;DependencyMode=$DependencyMode}
 if($JavaHome){$advanced.JavaHome=$JavaHome};if($CacheDirectory){$advanced.CacheDirectory=$CacheDirectory}
 if($InstallPythonPackages -and $VenvPath){$prefix=[IO.Path]::GetFullPath($VenvPath).TrimEnd('\','/')+'\';foreach($process in @(Get-CimInstance Win32_Process -ErrorAction Stop)){if($process.ExecutablePath -and $process.ExecutablePath.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)){throw 'The selected venv is in use. Stop its processes or choose a new isolated environment before installing packages.'}}}
 & (Resolve-VfcWindowsFile $SourceRoot 'setup-server.ps1') @advanced
 Write-VfcInstallationManifest ([IO.Path]::GetFullPath($ServerRoot)) 'advanced'
 Write-Host 'Advanced preparation complete. In-game capability refresh shows only configured/prepared available choices; deferred or missing models remain unavailable. Use /llm status or /companion setup for details.'
}
