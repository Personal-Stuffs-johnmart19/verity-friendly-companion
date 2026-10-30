# Windows PowerShell 5.1+; all paths are based on this script, never the caller's cwd.
[CmdletBinding()]
param(
 [string]$JavaHome,
 [string]$CacheDirectory,
 [string]$SocketDirectory,
 [string]$ServerRoot,
 [string]$ClientRoot,
 [string]$MinecraftRoot,
 [ValidateSet('basic','advanced')][string]$Profile,
 [string]$AddonJar,
 [string]$ExportDirectory,
 [string]$VenvPath,
 [switch]$UseExistingVenv,
 [switch]$InstallPythonPackages,
 [ValidateSet('keep','google','silero','drama3','disabled')][string]$TtsBackend='keep',
 [switch]$SetupLocalChat,
 [string]$LocalModel,
 [switch]$StandaloneServer,
 [switch]$PrepareModels,
 [switch]$Install,
 [switch]$BuildOnly,
 [switch]$Plan,
 [switch]$Discover,
 [switch]$OptionalBackends,
 [ValidateSet('keep','auto','cpu','cuda','native-cuda','native-cpu','vulkan','rocm','google','disabled')][string]$SttBackend='keep',
 [string]$PythonExecutable,
 [string]$PythonVersion='3.12',
 [ValidateSet('auto','locked','compatible','existing')][string]$DependencyMode='auto',
 [switch]$VulkanFallback,
 [ValidateSet('keep','on','off')][string]$CpuFallback='keep',
 [switch]$SkipModelDownloads,
 [switch]$DownloadLargeModels,
 [switch]$UsePortableJdk,
 [switch]$Offline,
 [switch]$PackageRelease,
 [switch]$NonInteractive
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=$PSScriptRoot
$sourceRoot=Split-Path (Split-Path $PSScriptRoot)
if((Split-Path $PSScriptRoot -Leaf) -eq 'windows' -and (Split-Path (Split-Path $PSScriptRoot) -Leaf) -eq 'setup' -and (Test-Path -LiteralPath (Join-Path $sourceRoot 'build.gradle'))){$repo=$sourceRoot}
if([Environment]::OSVersion.Platform -ne 'Win32NT'){throw 'This bootstrap supports Windows. On other systems use Java 21 and Gradle 9.2.1: gradle -p <repo> jar'}
if($BuildOnly -and ($ServerRoot -or $ClientRoot -or $Install -or $Profile -or $Discover -or $Plan -or $ExportDirectory -or $StandaloneServer)){throw '-BuildOnly compiles source without installing; pass installation options in a separate invocation'}
$wizard=Join-Path $repo 'setup/windows/windows-wizard.ps1'
if(!(Test-Path -LiteralPath $wizard)){
 foreach($relative in @('windows-wizard.ps1','verity-service/windows-wizard.ps1')){if(Test-Path -LiteralPath (Join-Path $repo $relative)){$wizard=Join-Path $repo $relative;break}}
}
$useWizard=!$BuildOnly -and ($PSBoundParameters.Count -eq 0 -or $Install -or $Profile -or $Plan -or $Discover -or $ExportDirectory -or $StandaloneServer)
if($useWizard){
 if(!(Test-Path -LiteralPath $wizard)){throw 'Installer helpers are missing; extract the complete source/setup archive'}
 $options=@{SourceRoot=$repo}
 foreach($name in @('Profile','AddonJar','ExportDirectory','ServerRoot','ClientRoot','MinecraftRoot','JavaHome','CacheDirectory','VenvPath','UseExistingVenv','InstallPythonPackages','PythonExecutable','PythonVersion','DependencyMode','SttBackend','TtsBackend','SetupLocalChat','LocalModel','CpuFallback','VulkanFallback','StandaloneServer','Offline','NonInteractive','Plan','Discover','PrepareModels','DownloadLargeModels')){if($PSBoundParameters.ContainsKey($name)){$options[$name]=$PSBoundParameters[$name]}}
 & $wizard @options
 return
}
if(!(Test-Path -LiteralPath (Join-Path $repo 'build.gradle'))){throw 'Compilation needs a complete source checkout. Use install.cmd for a packaged addon.'}
if(!$CacheDirectory){$CacheDirectory=Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'VerityFriendlyCompanion/build-tools'}
$cache=[IO.Path]::GetFullPath($CacheDirectory)
New-Item -ItemType Directory -Force -Path $cache|Out-Null
[Net.ServicePointManager]::SecurityProtocol=[Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

function Get-VerifiedArchive([string]$Url,[string]$Name,[string]$Sha256){
 $file=Join-Path $cache $Name
 if(!(Test-Path -LiteralPath $file)){
  if($Offline){throw "Offline prerequisite missing: $file"}
  $partial=$file+'.partial'
  Write-Host "Downloading $Name from its official distribution..."
  $savedProgress=$ProgressPreference
  try{$ProgressPreference='SilentlyContinue';Invoke-WebRequest -Uri $Url -OutFile $partial -UseBasicParsing -TimeoutSec 600}finally{$ProgressPreference=$savedProgress}
  if((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $Sha256){throw "Checksum mismatch: $Name; refusing to run downloaded software"}
  Move-Item -LiteralPath $partial -Destination $file -Force
 }
 if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $Sha256){throw "Cached checksum mismatch: $Name; remove this archive and retry"}
 return $file
}
function Test-Jdk21([string]$Directory){
 if(!$Directory){return $false}
 $release=Join-Path $Directory 'release'
 return (Test-Path -LiteralPath (Join-Path $Directory 'bin/java.exe')) -and (Test-Path -LiteralPath (Join-Path $Directory 'bin/javac.exe')) -and (Test-Path -LiteralPath $release) -and ([IO.File]::ReadAllText($release) -match '(?m)^JAVA_VERSION="21(?:[.\"+])')
}
if($JavaHome -and !(Test-Jdk21 $JavaHome)){throw '-JavaHome must point to a complete Java 21 JDK'}
if(!$JavaHome -and !$UsePortableJdk){
 if(Test-Jdk21 $env:JAVA_HOME){$JavaHome=$env:JAVA_HOME}
 if(!$JavaHome){$compiler=Get-Command javac.exe -ErrorAction SilentlyContinue;if($compiler){$candidate=Split-Path (Split-Path $compiler.Source);if(Test-Jdk21 $candidate){$JavaHome=$candidate}}}
 if(!$JavaHome){. (Join-Path $repo 'setup/windows/windows-environment.ps1');$detected=@(Get-VfcDiscoveredJdks);if($detected.Count){$JavaHome=$detected[0].Path}}
}
if(!$JavaHome){
 if([Environment]::GetEnvironmentVariable('PROCESSOR_ARCHITECTURE') -ne 'AMD64' -or $env:PROCESSOR_ARCHITEW6432 -eq 'ARM64'){throw 'Automatic portable JDK installation supports Windows x64. Supply -JavaHome for your architecture.'}
 $jdkZip=Get-VerifiedArchive 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip' 'temurin-21.0.13-9-windows-x64.zip' 'a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9'
 $JavaHome=Join-Path $cache 'jdk-21.0.13+9'
 if(!(Test-Jdk21 $JavaHome)){Expand-Archive -LiteralPath $jdkZip -DestinationPath $cache -Force}
 if(!(Test-Jdk21 $JavaHome)){throw 'Portable JDK extraction failed'}
}
$gradleZip=Get-VerifiedArchive 'https://services.gradle.org/distributions/gradle-9.2.1-bin.zip' 'gradle-9.2.1-bin.zip' '72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f'
$gradle=Join-Path $cache 'gradle-9.2.1/bin/gradle.bat'
if(!(Test-Path -LiteralPath $gradle)){Expand-Archive -LiteralPath $gradleZip -DestinationPath $cache -Force}
if(!(Test-Path -LiteralPath $gradle)){throw 'Gradle extraction failed'}
$savedJava=$env:JAVA_HOME
$savedOptions=$env:JAVA_TOOL_OPTIONS
try{
 $env:JAVA_HOME=[IO.Path]::GetFullPath($JavaHome)
 # JDK 21 uses Unix-domain sockets internally even on Windows. Long profile
 # temp paths can exceed the native socket path limit before Gradle starts.
 $socketTemp=if($SocketDirectory){[IO.Path]::GetFullPath($SocketDirectory)}else{Join-Path ([Environment]::GetFolderPath('UserProfile')) '.vfc-jtmp'}
 New-Item -ItemType Directory -Force -Path $socketTemp|Out-Null
 $socketTemp=$socketTemp.Replace('\','/')
 if($socketTemp.Length -gt 65){throw 'Java needs a shorter socket directory; pass -SocketDirectory with a short user-writable path'}
 $env:JAVA_TOOL_OPTIONS=($savedOptions+' "-Djdk.net.unixdomain.tmpdir='+$socketTemp+'"').Trim()
 Write-Host "Building with Java 21 and Gradle 9.2.1. Repository: $repo"
 $arguments=@('--no-daemon','--console=plain','-p',$repo,'-g',(Join-Path $cache 'gradle-home'),('-Dorg.gradle.java.home='+$env:JAVA_HOME),('-Porg.gradle.java.installations.paths='+$env:JAVA_HOME))
 if($Offline){$arguments+='--offline'}
 & $gradle @arguments jar
 if($LASTEXITCODE){throw "Gradle compilation failed (exit $LASTEXITCODE); no game installation was changed"}
 if($PackageRelease){& (Join-Path $repo 'tools/release/package-release.ps1') -JavaHome $env:JAVA_HOME}
 $metadata=[IO.File]::ReadAllText((Join-Path $repo 'src/main/resources/META-INF/neoforge.mods.toml'))
 $version=[regex]::Match($metadata,'(?m)^version="([0-9A-Za-z.-]+)"').Groups[1].Value
 $artifact=Join-Path $repo ('dist/verity-friendly-companion-'+$version+'.jar')
 if(!(Test-Path -LiteralPath $artifact)){throw 'Gradle did not produce the expected JAR'}
 Get-FileHash -LiteralPath $artifact -Algorithm SHA256
 Write-Host "Ready: $artifact"
 if($ServerRoot -and !$BuildOnly){& (Join-Path $repo 'setup/windows/setup-server.ps1') -ServerRoot $ServerRoot -AddonJar $artifact -NonInteractive:$NonInteractive -Offline:$Offline -OptionalBackends:$OptionalBackends -SkipModelDownloads:$SkipModelDownloads -DownloadLargeModels:$DownloadLargeModels -SttBackend $SttBackend -TtsBackend $TtsBackend -PythonExecutable $PythonExecutable -VenvPath $VenvPath -UseExistingVenv:$UseExistingVenv -InstallPythonPackages:$InstallPythonPackages -PythonVersion $PythonVersion -DependencyMode $DependencyMode -VulkanFallback:$VulkanFallback -CpuFallback $CpuFallback -SetupLocalChat:$SetupLocalChat -LocalModel $LocalModel}
 if($ClientRoot -and !$BuildOnly){& (Join-Path $repo 'setup/windows/setup-client.ps1') -ClientRoot $ClientRoot -MinecraftRoot $MinecraftRoot -AddonJar $artifact -JavaHome $env:JAVA_HOME -NonInteractive:$NonInteractive -Offline:$Offline}
}finally{$env:JAVA_HOME=$savedJava;$env:JAVA_TOOL_OPTIONS=$savedOptions}
