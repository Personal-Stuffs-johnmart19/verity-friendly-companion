# Thin typed alias: keep all installer parameters defined in the canonical entrypoint.
[CmdletBinding()]
param(
 [ValidateSet('basic','advanced')][string]$Profile,
 [string]$ServerRoot,[string]$ClientRoot,[string]$MinecraftRoot,
 [string]$AddonJar,[string]$JavaHome,[string]$CacheDirectory,[string]$ExportDirectory,
 [string]$VenvPath,[switch]$UseExistingVenv,[switch]$InstallPythonPackages,
 [string]$PythonExecutable,[string]$PythonVersion,
 [ValidateSet('auto','locked','compatible','existing')][string]$DependencyMode,
 [ValidateSet('keep','auto','cpu','cuda','native-cuda','native-cpu','vulkan','rocm','google','disabled')][string]$SttBackend,
 [ValidateSet('keep','google','silero','drama3','disabled')][string]$TtsBackend,
 [switch]$SetupLocalChat,[string]$LocalModel,[switch]$VulkanFallback,[ValidateSet('keep','on','off')][string]$CpuFallback,[switch]$StandaloneServer,
 [switch]$Offline,[switch]$NonInteractive,[switch]$Plan,[switch]$Discover,
 [switch]$PrepareModels,[switch]$DownloadLargeModels
)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'setup.ps1') -Install @PSBoundParameters
