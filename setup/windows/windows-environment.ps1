# Bounded, read-only discovery. Never downloads, starts Minecraft or imports models.
function Resolve-VfcWindowsFile([string]$Root,[string]$Name){
 foreach($candidate in @((Join-Path $Root ('setup/windows/'+$Name)),(Join-Path $Root ('setup/'+$Name)),(Join-Path $Root ('verity-service/'+$Name)),(Join-Path $Root $Name))){if(Test-Path -LiteralPath $candidate -PathType Leaf){return $candidate}}
 throw ('Setup file is missing: '+$Name)
}
function Test-VfcJdk21([string]$Path){
 if(!$Path){return $false}
 try{$release=Join-Path $Path 'release'
  return (Test-Path -LiteralPath (Join-Path $Path 'bin/java.exe') -PathType Leaf) -and (Test-Path -LiteralPath (Join-Path $Path 'bin/javac.exe') -PathType Leaf) -and (Test-Path -LiteralPath $release -PathType Leaf) -and (Get-Item -LiteralPath $release).Length -le 65536 -and ([IO.File]::ReadAllText($release) -match '(?m)^JAVA_VERSION="21(?:[."+])')
 }catch{return $false}
}
function Get-VfcDiscoveredJdks {
 $candidates=[Collections.Generic.List[object]]::new()
 function Add-Jdk([string]$Path,[string]$Origin){if($Path -and (Test-VfcJdk21 $Path)){$candidates.Add([pscustomobject]@{Path=[IO.Path]::GetFullPath($Path);Origin=$Origin})}}
 Add-Jdk $env:JAVA_HOME 'JAVA_HOME'
 $compiler=Get-Command javac.exe -ErrorAction SilentlyContinue
 if($compiler){Add-Jdk (Split-Path (Split-Path $compiler.Source)) 'PATH'}
 $roots=@((Join-Path $env:ProgramFiles 'Java'),(Join-Path $env:ProgramFiles 'Eclipse Adoptium'),(Join-Path $env:ProgramFiles 'Microsoft'),(Join-Path ([Environment]::GetFolderPath('UserProfile')) '.jdks'),(Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle/jdks'))
 foreach($minecraft in Get-VfcMinecraftRoots){$roots+=Join-Path $minecraft 'Install';$roots+=Join-Path $minecraft 'Install/runtime'}
 foreach($root in $roots){
  if(!(Test-Path -LiteralPath $root -PathType Container)){continue}
  Add-Jdk $root 'Known tools directory'
  foreach($directory in @(Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue|Select-Object -First 80)){
   Add-Jdk $directory.FullName 'Known tools directory'
   if($directory.Name -match '^(java|jdk|jbr|temurin|runtime)'){foreach($child in @(Get-ChildItem -LiteralPath $directory.FullName -Directory -ErrorAction SilentlyContinue|Select-Object -First 12)){Add-Jdk $child.FullName 'Launcher runtime'}}
  }
 }
 $seen=@{};foreach($item in $candidates){if(!$seen.ContainsKey($item.Path)){$seen[$item.Path]=$true;$item}}
}
function Get-VfcMinecraftRoots {
 $profile=[Environment]::GetFolderPath('UserProfile')
 $roots=@($env:CURSEFORGE_MINECRAFT_PATH,(Join-Path $profile 'curseforge/minecraft'),(Join-Path $profile 'Documents/CurseForge/Minecraft'),(Join-Path $profile '.curseforge/minecraft'))
 $seen=@{};foreach($path in $roots){if($path -and (Test-Path -LiteralPath $path -PathType Container)){$full=[IO.Path]::GetFullPath($path);if(!$seen.ContainsKey($full)){$seen[$full]=$true;$full}}}
}
function Get-VfcHostCandidate([string]$Path){
 if(!(Test-Path -LiteralPath $Path -PathType Container)){return}
 $root=[IO.Path]::GetFullPath($Path);$mc='';$loader='';$kind='client';$minecraft=''
 foreach($name in 'minecraftinstance.json','manifest.json'){
  $file=Join-Path $root $name;if(!(Test-Path -LiteralPath $file -PathType Leaf)){continue}
  if((Get-Item -LiteralPath $file).Length -gt 2MB){continue}
  try{
   $json=Get-Content -LiteralPath $file -Raw|ConvertFrom-Json
   if($name -eq 'minecraftinstance.json' -and $json.PSObject.Properties['baseModLoader'] -and $json.baseModLoader){$loader=[string]$json.baseModLoader.name;if($json.baseModLoader.PSObject.Properties['minecraftVersion']){$mc=[string]$json.baseModLoader.minecraftVersion}}
   if($name -eq 'manifest.json' -and $json.PSObject.Properties['minecraft']){$mc=[string]$json.minecraft.version;$selected=@($json.minecraft.modLoaders|Where-Object { $_.id -like 'neoforge-*' });if($selected.Count -eq 1){$loader=[string]$selected[0].id}}
  }catch{continue}
  if($loader){break}
 }
 if(!$loader -and ((Test-Path -LiteralPath (Join-Path $root 'server.properties')) -or (Test-Path -LiteralPath (Join-Path $root 'libraries/net/neoforged/neoforge')))){
  $kind='server';$directory=Join-Path $root 'libraries/net/neoforged/neoforge'
  if(Test-Path -LiteralPath $directory){$versions=@(Get-ChildItem -LiteralPath $directory -Directory|Where-Object Name -Match '^21\.1\.\d+$'|Sort-Object {[version]$_.Name} -Descending);foreach($version in $versions){if(Test-Path -LiteralPath (Join-Path $version.FullName 'win_args.txt')){$loader='neoforge-'+$version.Name;$mc='1.21.1';break}}}
 }
 if(!$loader){return}
 $compatible=$mc -eq '1.21.1' -and $loader -match '^neoforge-21\.1\.(\d+)$' -and [int]$Matches[1] -ge 250
 $shared=Join-Path (Split-Path (Split-Path $root)) 'Install'
 if(Test-Path -LiteralPath (Join-Path $shared 'libraries')){$minecraft=$shared}
 [pscustomobject]@{Path=$root;Kind=$kind;MinecraftVersion=$mc;Loader=$loader;MinecraftRoot=$minecraft;Compatible=[bool]$compatible}
}
function Get-VfcDiscoveredHosts {
 $paths=@((Get-Location).Path)
 foreach($root in Get-VfcMinecraftRoots){
  $paths+=$root;$instances=Join-Path $root 'Instances'
  if(Test-Path -LiteralPath $instances){$paths+=@(Get-ChildItem -LiteralPath $instances -Directory -ErrorAction SilentlyContinue|Select-Object -First 256|ForEach-Object FullName)}
 }
 $seen=@{};foreach($path in $paths){$full=[IO.Path]::GetFullPath($path);if(!$seen.ContainsKey($full)){$seen[$full]=$true;Get-VfcHostCandidate $full}}
}
function Get-VfcHardwareOptions {
 $names=@();try{$names=@(Get-CimInstance Win32_VideoController -ErrorAction Stop|ForEach-Object Name)}catch{}
 $stt=@('disabled','google','native-cpu','cpu')
 if(Test-Path -LiteralPath (Join-Path ([Environment]::SystemDirectory) 'vulkan-1.dll')){$stt+='vulkan'}
 if(($names -join ' ') -match 'NVIDIA'){$stt+='native-cuda';$stt+='cuda'}
 if(($names -join ' ') -match 'AMD|Radeon'){$stt+='rocm'}
 [pscustomobject]@{Graphics=$names;Stt=$stt;Note='Hardware detection is advisory; selected backend preparation validates packages and assets.'}
}
function Assert-VfcSafePath([string]$Path){
 if(!$Path){throw 'A selected destination path is required'}
 $current=[IO.Path]::GetFullPath($Path)
 while($current){
  if((Test-Path -LiteralPath $current) -and ((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw ('Installation destination must not use symbolic links or junctions: '+$current)}
  $parent=Split-Path $current;if($parent -eq $current){break};$current=$parent
 }
}
function Assert-VfcTargetIdle([string]$Root,[switch]$IncludeAudio){
 Assert-VfcSafePath $Root
 foreach($relative in @('mods','config','libraries','verity-service','verity-update-backups','eula.txt','user_jvm_args.txt','run-verity-basic.cmd')){Assert-VfcSafePath (Join-Path $Root $relative)}
 if($IncludeAudio){$prefix=[IO.Path]::GetFullPath($Root).Replace('\','/').TrimEnd('/')+'/';foreach($process in @(Get-CimInstance Win32_Process -ErrorAction Stop)){
  if($process.Name -match '^(?:pythonw?|audiocpp_server)\.exe$' -and $process.CommandLine -and $process.CommandLine.Replace('\','/').IndexOf($prefix,[StringComparison]::OrdinalIgnoreCase) -ge 0){throw 'The selected server audio service is running. Stop it before updating its code or dependencies.'}
 }}
 $mods=Join-Path $Root 'mods'
 if(Test-Path -LiteralPath $mods){foreach($jar in @(Get-ChildItem -LiteralPath $mods -Filter '*.jar' -File)){
  Assert-VfcSafePath $jar.FullName
  $stream=$null
  try{$stream=[IO.File]::Open($jar.FullName,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None)}catch{throw ('Cannot safely update a mod file; close Minecraft/the server and check permissions: '+$jar.Name)}finally{if($stream){$stream.Dispose()}}
 }}
}
function Write-VfcInstallationManifest([string]$Root,[string]$Profile){
 Assert-VfcSafePath (Join-Path $Root 'config/verity-installation.json')
 $path=Join-Path $Root 'config/verity-installation.json';$installed=@();$python='';$voice=Join-Path $Root 'verity-service/voice-config.json'
 if(Test-Path -LiteralPath $voice){$settings=Get-Content -LiteralPath $voice -Raw|ConvertFrom-Json;foreach($key in 'stt_engine','provider'){if($settings.PSObject.Properties[$key] -and $settings.$key -notin 'google','disabled'){$installed+=[string]$settings.$key}};if($settings.PSObject.Properties['python_executable']){$python=[string]$settings.python_executable}}
 $state=[ordered]@{schema=1;profile=$Profile;installed_backends=@($installed|Select-Object -Unique);python_executable=$python;note='Preparation record only; the running addon rechecks actual availability.'}
 foreach($directory in @([IO.Path]::GetFullPath($Root),(Split-Path $path))){if(Test-Path -LiteralPath $directory){if((Get-Item -LiteralPath $directory -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Installation metadata directory must not be a symbolic link or junction'}}}
 if(Test-Path -LiteralPath $path){if((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Installation metadata must not be a symbolic link'};if((Get-Item -LiteralPath $path).Length -gt 65536){throw 'Installation metadata is too large'};$previous=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json;if($previous.PSObject.Properties['profile'] -and $previous.profile -eq 'advanced' -and $Profile -eq 'basic'){$state.profile='advanced'}}
 New-Item -ItemType Directory -Force -Path (Split-Path $path)|Out-Null
 $temp=Join-Path (Split-Path $path) ('verity-installation-'+[guid]::NewGuid().ToString('N')+'.tmp')
 $stream=[IO.File]::Open($temp,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
 try{$bytes=[Text.UTF8Encoding]::new($false).GetBytes(($state|ConvertTo-Json -Depth 8));$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
 try{Move-Item -LiteralPath $temp -Destination $path -Force}finally{if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp}}
}
