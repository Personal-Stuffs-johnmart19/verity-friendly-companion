param([string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
if(!$JavaHome){throw 'Provide -JavaHome with a Java 21 JDK'}
$root=[IO.Path]::GetFullPath((Split-Path (Split-Path $PSScriptRoot)))
$jarName=if([Environment]::OSVersion.Platform -eq 'Win32NT'){'jar.exe'}else{'jar'}
$jar=Join-Path $JavaHome ('bin/'+$jarName)
if(!(Test-Path -LiteralPath $jar -PathType Leaf)){throw 'Provide a complete Java 21 JDK for release packaging'}
function Assert-VfcPackagingPath([string]$Path,[string]$OwnedParent){
 $absolute=[IO.Path]::GetFullPath($Path)
 $parent=[IO.Path]::GetFullPath($OwnedParent).TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)
 if(!$absolute.StartsWith($parent+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw ('Packaging path escapes its owned directory: '+$absolute)}
 $current=$absolute
 while($current -and $current.Length -ge $root.Length){
  if((Test-Path -LiteralPath $current) -and ((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw ('Packaging refuses a symbolic link or junction: '+$current)}
  if($current -eq $root){break}
  $current=Split-Path $current
 }
 return $absolute
}

function Copy-VfcPublicFile($File,[string]$Destination){
 $target=Join-Path $Destination $File.Name
 if($File.Extension -eq '.sh'){
  $text=[IO.File]::ReadAllText($File.FullName).Replace("`r`n","`n")
  [IO.File]::WriteAllText($target,$text,[Text.UTF8Encoding]::new($false))
 }else{Copy-Item -LiteralPath $File.FullName -Destination $target}
}
function Copy-VfcReleaseDocs([string]$Destination,[string]$EntryName){
 $docs=Join-Path $Destination 'docs'
 New-Item -ItemType Directory -Force -Path $docs|Out-Null
 foreach($file in Get-ChildItem -LiteralPath (Join-Path $root 'docs') -Filter '*.md' -File){$null=Assert-VfcPackagingPath $file.FullName $root;Copy-Item -LiteralPath $file.FullName -Destination $docs}
 foreach($name in @('LICENSE','SECURITY.md')){$path=Assert-VfcPackagingPath (Join-Path $root $name) $root;Copy-Item -LiteralPath $path -Destination $Destination}
 $pointer='# '+[IO.Path]::GetFileNameWithoutExtension($EntryName)+"`n`n"+'See the [complete guide](docs/'+$EntryName+').'+"`n"
 [IO.File]::WriteAllText((Join-Path $Destination $EntryName),$pointer,[Text.UTF8Encoding]::new($false))
}

$metadata=Get-Content -LiteralPath (Join-Path $root 'src/main/resources/META-INF/neoforge.mods.toml') -Raw
$version=[regex]::Match($metadata,'(?m)^version="([0-9A-Za-z.-]+)"').Groups[1].Value
if(!$version){throw 'Missing addon version'}
$dist=Join-Path $root 'dist'
$null=Assert-VfcPackagingPath (Join-Path $dist 'packaging-target') $dist
$artifact=Join-Path $dist ('verity-friendly-companion-'+$version+'.jar')
foreach($path in @($artifact,(Join-Path $dist ('verity-friendly-companion-'+$version+'-server-service.zip')),(Join-Path $dist ('verity-friendly-companion-'+$version+'-client-setup.zip')),(Join-Path $dist 'SHA256SUMS.txt'))){$null=Assert-VfcPackagingPath $path $dist}
if(!(Test-Path -LiteralPath $artifact)){throw 'Build the addon first'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($artifact)
try{
 $names=@($archive.Entries.FullName)
 if($names -match '(^|/)(tests?|diagnostics|\.secrets|models|__pycache__)/|^varmite/|(?:Test|Harness|Agent)\$?[^/]*\.class$|\.(py|wav|mp3|ogg|gguf|safetensors)$'){throw 'Unexpected test, original-mod or server-only content in JAR'}
 foreach($required in @('META-INF/neoforge.mods.toml','META-INF/coremods.json','META-INF/LICENSE.txt','dev/johnmart19/veritycompanion/CompanionAddon.class')){if($required -notin $names){throw ('Missing runtime entry: '+$required)}}
 $reader=[IO.StreamReader]::new($archive.GetEntry('META-INF/neoforge.mods.toml').Open())
 try{if($reader.ReadToEnd().Replace("`r`n","`n") -ne $metadata.Replace("`r`n","`n")){throw 'JAR metadata differs from source'}}finally{$reader.Dispose()}
}finally{$archive.Dispose()}
$stage=Join-Path $root ('build/release-'+$version)
$stage=Assert-VfcPackagingPath $stage (Join-Path $root 'build')
if(Test-Path -LiteralPath $stage){Remove-Item -LiteralPath $stage -Recurse -Force}
$service=Join-Path $stage 'verity-service'
New-Item -ItemType Directory -Path $service|Out-Null
$addonDirectory=Join-Path $service 'addon'
New-Item -ItemType Directory -Force -Path $addonDirectory|Out-Null
Copy-Item -LiteralPath $artifact -Destination $addonDirectory
. (Join-Path $root 'setup/source-layout.ps1')
$files=@(Get-VfcServiceFiles)
if('install.ps1' -in $files.Name){
 foreach($required in @('setup.cmd','setup.ps1','install.cmd','windows-environment.ps1','windows-wizard.ps1','windows-basic.ps1','setup-client.ps1','dependency-tools.ps1','source-layout.ps1')){
  if($required -notin $files.Name){throw ('Incomplete Windows installer source: '+$required)}
 }
}
if('setup.sh' -in $files.Name){
 foreach($required in @('linux-common.sh','linux-bootstrap.sh','linux-detect.sh','linux-install.sh','linux-optional.sh','linux-launch.sh','LinuxSetup.java','start-server.sh','start-bridge.sh')){
  if($required -notin $files.Name){throw ('Incomplete Linux installer source: '+$required)}
 }
}
foreach($file in $files){Copy-VfcPublicFile $file $service}
Copy-VfcReleaseDocs $stage 'SERVER.md'
& $jar --create --file (Join-Path $dist ('verity-friendly-companion-'+$version+'-server-service.zip')) --no-manifest -C $stage .
if($LASTEXITCODE){throw 'Server archive creation failed'}
$client=Join-Path $root ('build/client-release-'+$version)
$client=Assert-VfcPackagingPath $client (Join-Path $root 'build')
if(Test-Path -LiteralPath $client){Remove-Item -LiteralPath $client -Recurse -Force}
$clientSetup=Join-Path $client 'verity-client-setup'
New-Item -ItemType Directory -Force -Path (Join-Path $clientSetup 'addon')|Out-Null
foreach($file in Get-VfcClientFiles){Copy-VfcPublicFile $file $clientSetup}
Copy-Item -LiteralPath $artifact -Destination (Join-Path $clientSetup 'addon')
Copy-VfcReleaseDocs $clientSetup 'CLIENT.md'
& $jar --create --file (Join-Path $dist ('verity-friendly-companion-'+$version+'-client-setup.zip')) --no-manifest -C $client .
if($LASTEXITCODE){throw 'Client setup archive creation failed'}
$releaseNames=@(('verity-friendly-companion-'+$version+'.jar'),('verity-friendly-companion-'+$version+'-server-service.zip'),('verity-friendly-companion-'+$version+'-client-setup.zip'))
$checksums=Get-ChildItem -LiteralPath $dist -File | Where-Object {$_.Name -in $releaseNames} | Sort-Object Name | ForEach-Object {((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())+'  '+$_.Name}
if(@($checksums).Count -ne 3){throw 'Release artifact set incomplete'}
[IO.File]::WriteAllLines((Join-Path $dist 'SHA256SUMS.txt'),$checksums,[Text.UTF8Encoding]::new($false))
$checksums
