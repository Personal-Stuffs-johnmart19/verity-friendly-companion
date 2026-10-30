[CmdletBinding()]
param([string]$ClientRoot,[string]$MinecraftRoot,[string]$AddonJar,[string]$JavaHome,[string]$CacheDirectory,[switch]$CheckOnly,[switch]$Offline,[switch]$NonInteractive,[switch]$UpdateAddon)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'dependency-tools.ps1')
if([Environment]::OSVersion.Platform -ne 'Win32NT'){throw 'Automatic client setup currently supports Windows; install matching Java 21/NeoForge/mods with your launcher on other systems'}
if(!$ClientRoot){if($NonInteractive){throw 'Pass -ClientRoot with the Minecraft instance directory'};$ClientRoot=Read-Host 'Minecraft client instance directory (contains mods/)'}
if(!$ClientRoot){throw 'A client instance directory is required'}
$root=[IO.Path]::GetFullPath($ClientRoot)
if(!$MinecraftRoot){$candidate=Join-Path (Split-Path (Split-Path $root)) 'Install';if(Test-Path -LiteralPath (Join-Path $candidate 'libraries')){$MinecraftRoot=$candidate}}
if(!$CacheDirectory){$CacheDirectory=Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'VerityFriendlyCompanion/server-tools'}
$cache=[IO.Path]::GetFullPath($CacheDirectory)
$script:requestedLoaderVersion='21.1.250'
function Test-ClientLoader {
 foreach($name in @('minecraftinstance.json','manifest.json')){
  $path=Join-Path $root $name
  if(!(Test-Path -LiteralPath $path)){continue}
  if((Get-Item -LiteralPath $path).Length -gt 2097152){throw 'Client instance metadata exceeds its size limit; existing launcher files were preserved'}
  $meta=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
  $loader='';$mc=''
  if($name -eq 'minecraftinstance.json' -and $meta.PSObject.Properties['baseModLoader']){$loader=$meta.baseModLoader.name;$mc=$meta.baseModLoader.minecraftVersion}
  if($name -eq 'manifest.json' -and $meta.PSObject.Properties['minecraft']){$mc=$meta.minecraft.version;$loader=(@($meta.minecraft.modLoaders|Where-Object primary|ForEach-Object id) -join '')}
  if($loader){if($mc -ne '1.21.1' -or $loader -notmatch '^neoforge-21\.1\.(\d+)$' -or [int]$Matches[1] -lt 250){throw 'This modpack declares an incompatible Minecraft/loader version. Its metadata will not be changed; use Minecraft 1.21.1 / NeoForge 21.1.250 or newer 21.1.x.'};$script:requestedLoaderVersion=$loader.Substring(9);if($MinecraftRoot){return Test-VfcClientLibraries $MinecraftRoot $loader};return $false}
 }
 if($MinecraftRoot){
  $versions=Join-Path ([IO.Path]::GetFullPath($MinecraftRoot)) 'versions'
  if(Test-Path -LiteralPath $versions){foreach($version in Get-ChildItem -LiteralPath $versions -Directory){if($version.Name -match '^neoforge-(21\.1\.(\d+))$' -and [int]$Matches[2] -ge 250){$script:requestedLoaderVersion=$Matches[1];if(Test-VfcClientLibraries $MinecraftRoot $version.Name){return $true}}}}
 }
 return $false
}
if($CheckOnly){
 $expected=Get-VfcAddonVersion (Get-VfcBundledAddon $PSScriptRoot)
 $problems=@(Get-VfcModProblems $root $expected)
 if(!(Get-VfcJava $cache $JavaHome -CheckOnly)){$problems+='Java 21 missing'}
 if(!(Test-ClientLoader)){$problems+='Compatible client loader not detected. Pass -MinecraftRoot for an official-launcher installation, or use a matching CurseForge/Prism instance.'}
 foreach($problem in $problems){Write-Warning $problem}
 if($problems.Count){throw 'Client dependency checks failed; no files were changed'}
 Write-Host 'PASS client Java, loader and all mod dependencies. No Python, server configuration or keys are needed.'
 return
}
if($UpdateAddon){
 if(!(Get-Command Assert-VfcTargetIdle -ErrorAction SilentlyContinue)){
  $helpers=if(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'windows')){Join-Path $PSScriptRoot 'windows'}else{$PSScriptRoot}
  . (Join-Path $helpers 'windows-environment.ps1')
  . (Join-Path $helpers 'windows-basic.ps1')
 }
 Assert-VfcTargetIdle $root
}
$java=Get-VfcJava $cache $JavaHome -Offline:$Offline
if(!(Test-ClientLoader)){
 if(!$MinecraftRoot -and !$NonInteractive){$MinecraftRoot=Read-Host 'Official Minecraft launcher directory (contains launcher_profiles.json), or Enter to use a managed modpack instance instead'}
 if(!$MinecraftRoot){throw 'Install/select a Minecraft 1.21.1 NeoForge instance in your launcher, or pass -MinecraftRoot to install its loader automatically. A Minecraft account/launcher cannot be provisioned by this mod.'}
 $MinecraftRoot=[IO.Path]::GetFullPath($MinecraftRoot)
 if(!(Test-Path -LiteralPath (Join-Path $MinecraftRoot 'launcher_profiles.json'))){throw '-MinecraftRoot must be an initialized official-launcher directory. Launch/sign in to Minecraft once first.'}
 if($Offline){throw 'A missing client loader requires an online installation'}
 $installer=Get-VfcNeoForgeInstaller $script:requestedLoaderVersion $cache
 & (Join-Path $java 'bin/java.exe') -jar $installer --installClient $MinecraftRoot
 if($LASTEXITCODE -or !(Test-ClientLoader)){throw 'NeoForge client installation did not finish successfully'}
}
if(!$AddonJar){$AddonJar=Get-VfcBundledAddon $PSScriptRoot}
if(!$AddonJar){throw 'Build with setup.cmd first or use the client-setup ZIP containing the addon'}
if($UpdateAddon){
 Install-VfcWizardMods $root $AddonJar $cache -Offline:$Offline
}else{Install-VfcMods $root $AddonJar $cache -Offline:$Offline}
Write-Host "Client setup complete: original Verity, GeckoLib and addon installed in $root/mods . Select the compatible NeoForge instance in your launcher. Java 21: $java"
