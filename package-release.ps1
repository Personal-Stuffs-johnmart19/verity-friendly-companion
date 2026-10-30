param([string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
if(!$JavaHome){throw 'Provide -JavaHome with a Java 21 JDK'}
$root=$PSScriptRoot
$metadata=Get-Content -LiteralPath (Join-Path $root 'src/main/resources/META-INF/neoforge.mods.toml') -Raw
$version=[regex]::Match($metadata,'(?m)^version="([0-9A-Za-z.-]+)"').Groups[1].Value
if(!$version){throw 'Missing addon version'}
$dist=Join-Path $root 'dist'
$artifact=Join-Path $dist ('verity-friendly-companion-'+$version+'.jar')
if(!(Test-Path -LiteralPath $artifact)){throw 'Build the addon first'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($artifact)
try{
 $names=@($archive.Entries.FullName)
 if($names -match '(^|/)(tests?|diagnostics|\.secrets|models|__pycache__)/|^varmite/|(?:Test|Harness|Agent)\$?[^/]*\.class$|\.(py|wav|mp3|ogg|gguf|safetensors)$'){throw 'Unexpected test, original-mod or server-only content in JAR'}
 foreach($required in @('META-INF/neoforge.mods.toml','META-INF/coremods.json','META-INF/LICENSE.txt','dev/johnmart19/veritycompanion/CompanionAddon.class')){if($required -notin $names){throw ('Missing runtime entry: '+$required)}}
 $reader=[IO.StreamReader]::new($archive.GetEntry('META-INF/neoforge.mods.toml').Open())
 try{if($reader.ReadToEnd() -ne $metadata){throw 'JAR metadata differs from source'}}finally{$reader.Dispose()}
}finally{$archive.Dispose()}
$stage=Join-Path $root ('build/release-'+$version)
if(Test-Path -LiteralPath $stage){$resolved=[IO.Path]::GetFullPath($stage);$expected=[IO.Path]::GetFullPath((Join-Path $root ('build/release-'+$version)));if($resolved -ne $expected){throw 'Unexpected release staging path'};Remove-Item -LiteralPath $resolved -Recurse -Force}
$service=Join-Path $stage 'verity-service'
New-Item -ItemType Directory -Path $service|Out-Null
$files=@(Get-ChildItem -LiteralPath (Join-Path $root 'server') -File | Where-Object {$_.Extension -in '.py','.ps1' -or $_.Name -eq 'requirements.txt' -or $_.Name -like '*.example.json'})
foreach($file in $files){Copy-Item -LiteralPath $file.FullName -Destination $service}
Copy-Item -LiteralPath (Join-Path $root 'docs/SERVER.md') -Destination (Join-Path $stage 'SERVER.md')
Copy-Item -LiteralPath (Join-Path $root 'LICENSE') -Destination $stage
& (Join-Path $JavaHome 'bin/jar.exe') --create --file (Join-Path $dist ('verity-friendly-companion-'+$version+'-server-service.zip')) --no-manifest -C $stage .
if($LASTEXITCODE){throw 'Server archive creation failed'}
$releaseNames=@(('verity-friendly-companion-'+$version+'.jar'),('verity-friendly-companion-'+$version+'-server-service.zip'))
$checksums=Get-ChildItem -LiteralPath $dist -File | Where-Object {$_.Name -in $releaseNames} | Sort-Object Name | ForEach-Object {((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())+'  '+$_.Name}
if(@($checksums).Count -ne 2){throw 'Release artifact set incomplete'}
[IO.File]::WriteAllLines((Join-Path $dist 'SHA256SUMS.txt'),$checksums,[Text.UTF8Encoding]::new($false))
$checksums
