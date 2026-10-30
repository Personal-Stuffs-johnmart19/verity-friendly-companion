# Core game/mod installation only; no Python, local models, credentials or inference.
function Get-VfcWizardAddon([string]$SourceRoot,[string]$Explicit){
 if($Explicit){$path=[IO.Path]::GetFullPath($Explicit);if(!(Test-Path -LiteralPath $path -PathType Leaf)){throw 'Selected addon JAR does not exist'};return $path}
 $metadata=Join-Path $SourceRoot 'src/main/resources/META-INF/neoforge.mods.toml'
 if(Test-Path -LiteralPath $metadata){$version=[regex]::Match([IO.File]::ReadAllText($metadata),'(?m)^version="([0-9A-Za-z.-]+)"').Groups[1].Value;$path=Join-Path $SourceRoot ('dist/verity-friendly-companion-'+$version+'.jar');if(Test-Path -LiteralPath $path){return $path};return $null}
 foreach($folder in @((Join-Path $SourceRoot 'addon'),(Join-Path $SourceRoot 'verity-service/addon'))){if(Test-Path -LiteralPath $folder){$files=@(Get-ChildItem -LiteralPath $folder -Filter 'verity-friendly-companion*.jar' -File);if($files.Count -eq 1){return $files[0].FullName};if($files.Count -gt 1){throw 'Several bundled addon JARs found; choose -AddonJar explicitly'}}}
 return $null
}
function Install-VfcWizardMods([string]$Root,[string]$AddonJar,[string]$Cache,[switch]$Offline){
 Assert-VfcTargetIdle $Root
 $source=[IO.Path]::GetFullPath($AddonJar);$saved=@()
 $mods=Get-VfcMods $Root
 try{foreach($old in $mods.verity_companion){
  if([IO.Path]::GetFullPath($old.path) -eq $source){continue}
  if((Get-FileHash -LiteralPath $old.path).Hash -eq (Get-FileHash -LiteralPath $source).Hash){continue}
  if([IO.Path]::GetFileName($old.path) -notlike 'verity-friendly-companion*.jar'){throw 'A custom-named addon is installed. Preserve it and choose its replacement explicitly before setup.'}
  $directory=Join-Path $Root ('verity-update-backups/setup-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fffffffZ'))
  Assert-VfcSafePath $directory
  New-Item -ItemType Directory -Force -Path $directory|Out-Null
  $backup=Join-Path $directory ([IO.Path]::GetFileName($old.path));Copy-Item -LiteralPath $old.path -Destination $backup
  $saved+=@{target=$old.path;backup=$backup};Remove-Item -LiteralPath $old.path
 }
 Install-VfcMods $Root $source $Cache -Offline:$Offline}
 catch{
  $target=Join-Path $Root ('mods/'+[IO.Path]::GetFileName($source))
  if($saved.Count -and (Test-Path -LiteralPath $target) -and (Get-FileHash -LiteralPath $target).Hash -eq (Get-FileHash -LiteralPath $source).Hash){Remove-Item -LiteralPath $target}
  foreach($old in $saved){Copy-Item -LiteralPath $old.backup -Destination $old.target -Force}
  throw
 }
}
function Invoke-VfcBasicInstall {
 param([string]$SourceRoot,[string]$AddonJar,[string]$ServerRoot,[string]$ClientRoot,[string]$MinecraftRoot,[string]$JavaHome,[string]$CacheDirectory,[string]$ExportDirectory,[switch]$Offline,[switch]$NonInteractive,[switch]$StandaloneServer)
 . (Resolve-VfcWindowsFile $SourceRoot 'dependency-tools.ps1')
 if(!$CacheDirectory){$CacheDirectory=Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'VerityFriendlyCompanion/server-tools'}
 $cache=[IO.Path]::GetFullPath($CacheDirectory)
 if(!$AddonJar -or !(Test-Path -LiteralPath $AddonJar -PathType Leaf)){throw 'Build the source first or select a setup archive containing the addon JAR'}
 $version=Get-VfcAddonVersion $AddonJar
 if(!$version){throw 'Selected file is not a Verity Friendly Companion addon JAR'}
 $archive=[IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($AddonJar))
 try{
  $entry=$archive.GetEntry('META-INF/neoforge.mods.toml')
  if(!$entry -or $entry.Length -gt 262144 -or !$archive.GetEntry('dev/johnmart19/veritycompanion/CompanionAddon.class')){throw 'Selected file does not contain the Verity Friendly Companion addon'}
  $reader=[IO.StreamReader]::new($entry.Open());try{$toml=$reader.ReadToEnd()}finally{$reader.Dispose()}
  if($toml -notmatch '(?m)^\s*modId\s*=\s*"verity_companion"\s*$'){throw 'Selected JAR has a different mod identity'}
 }finally{$archive.Dispose()}
 if($ExportDirectory){
  $destination=[IO.Path]::GetFullPath($ExportDirectory);Assert-VfcSafePath $destination;Assert-VfcSafePath (Join-Path $destination ([IO.Path]::GetFileName($AddonJar)));New-Item -ItemType Directory -Force -Path $destination|Out-Null
  $target=Join-Path $destination ([IO.Path]::GetFileName($AddonJar))
  if([IO.Path]::GetFullPath($AddonJar) -ne $target){
   if(Test-Path -LiteralPath $target){if((Get-FileHash -LiteralPath $target).Hash -ne (Get-FileHash -LiteralPath $AddonJar).Hash){throw 'Another JAR exists at the export destination; choose another folder'}}else{Copy-Item -LiteralPath $AddonJar -Destination $target}
  }
  Write-Host "JAR-only export ready: $target"
  Write-Host 'Install it with original Verity 3.4.1 and GeckoLib on the matching Minecraft 1.21.1 / NeoForge instance. No Python or local models are needed.'
 }
 if($ServerRoot){
  $server=[IO.Path]::GetFullPath($ServerRoot);Assert-VfcTargetIdle $server
  if(!$StandaloneServer){$candidate=Get-VfcHostCandidate $server;if(!$candidate -or !$candidate.Compatible -or $candidate.Kind -ne 'server'){throw 'Choose a compatible existing NeoForge server, or use -StandaloneServer for an explicitly selected new server directory'}}
  elseif((Test-Path -LiteralPath $server) -and @(Get-ChildItem -LiteralPath $server -Force).Count){$candidate=Get-VfcHostCandidate $server;if(!$candidate -or !$candidate.Compatible -or $candidate.Kind -ne 'server'){throw 'Standalone server destination must be empty or an existing compatible NeoForge server; no game data was changed'}}
  New-Item -ItemType Directory -Force -Path $cache,$server|Out-Null
  $java=Get-VfcJava $cache $JavaHome -Offline:$Offline
  $loader=Install-VfcServerLoader $server $java $cache -Offline:$Offline
  Install-VfcWizardMods $server $AddonJar $cache -Offline:$Offline
  $config=Join-Path $server 'config';New-Item -ItemType Directory -Force -Path $config|Out-Null
  $runtime=Join-Path $config 'verity-runtime.json'
  if(!(Test-Path -LiteralPath $runtime)){[IO.File]::WriteAllText($runtime,(@{javaHome=$java;neoforgeArgs=$loader.Substring($server.Length).TrimStart('\','/')}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))}
  $eula=Join-Path $server 'eula.txt';if(!(Test-Path -LiteralPath $eula)){[IO.File]::WriteAllText($eula,"# Review https://aka.ms/MinecraftEULA before accepting.`neula=false`n",[Text.UTF8Encoding]::new($false))}
  $launcher=Join-Path $server 'run-verity-basic.cmd'
  if(!(Test-Path -LiteralPath $launcher)){
   $arguments=Join-Path $server 'user_jvm_args.txt';if(!(Test-Path -LiteralPath $arguments)){[IO.File]::WriteAllText($arguments,"-Xms1G`n-Xmx6G`n",[Text.UTF8Encoding]::new($false))}
   $exe=(Join-Path $java 'bin/java.exe').Replace('%','%%');$loaderArgument=$loader.Substring($server.Length).TrimStart('\','/').Replace('%','%%')
   $text='@echo off'+"`r`n"+'cd /d "%~dp0"'+"`r`n"+'"'+$exe+'" @user_jvm_args.txt @"'+$loaderArgument+'" nogui'+"`r`n"
   [IO.File]::WriteAllText($launcher,$text,[Text.UTF8Encoding]::new($false))
  }
  Write-VfcInstallationManifest $server 'basic'
  Write-Host "Core server installed: $server. Existing settings, saves and launchers were preserved. Nothing was started."
  Write-Host 'After accepting the Minecraft EULA yourself, start your launcher and use /companion setup. Save Google credentials only in server verity-service/.secrets/google-api-key; confirm a no-billing project before enabling cloud calls.'
 }
 if($ClientRoot){
  Assert-VfcTargetIdle $ClientRoot
  $parameters=@{ClientRoot=$ClientRoot;AddonJar=$AddonJar;CacheDirectory=$cache;NonInteractive=$NonInteractive;Offline=$Offline;UpdateAddon=$true}
  if($MinecraftRoot){$parameters.MinecraftRoot=$MinecraftRoot};if($JavaHome){$parameters.JavaHome=$JavaHome}
  # Install/verify game dependencies using the established client path, never server settings.
  $candidate=Get-VfcHostCandidate $ClientRoot
  if(!$MinecraftRoot -and $candidate -and $candidate.MinecraftRoot){$MinecraftRoot=$candidate.MinecraftRoot;$parameters.MinecraftRoot=$MinecraftRoot}
  if($candidate -and !$candidate.Compatible){throw 'Selected client metadata is incompatible; it will not be rewritten'}
  $librariesReady=$candidate -and $candidate.Compatible -and $MinecraftRoot -and (Test-VfcClientLibraries $MinecraftRoot $candidate.Loader)
  if($librariesReady){Install-VfcWizardMods ([IO.Path]::GetFullPath($ClientRoot)) $AddonJar $cache -Offline:$Offline;Write-Host ('Client mod dependencies and NeoForge libraries verified: '+$ClientRoot);Write-Host 'Java runtime selection remains managed by the existing Minecraft launcher.'}
  else{& (Resolve-VfcWindowsFile $SourceRoot 'setup-client.ps1') @parameters}
 }
}
