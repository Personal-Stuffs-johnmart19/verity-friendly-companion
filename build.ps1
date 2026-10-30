param(
 [Parameter(Mandatory=$true)][string]$ServerRoot,
 [Parameter(Mandatory=$true)][string]$ClientRoot,
 [string]$JavaHome=$env:JAVA_HOME
)
$ErrorActionPreference='Stop'
if(!$JavaHome){$JavaHome=Split-Path (Split-Path (Get-Command javac.exe -ErrorAction Stop).Source)}
$javac=Join-Path $JavaHome 'bin/javac.exe'
$jar=Join-Path $JavaHome 'bin/jar.exe'
$root=$PSScriptRoot
$metadata=Get-Content -LiteralPath (Join-Path $root 'src/main/resources/META-INF/neoforge.mods.toml') -Raw
$version=[regex]::Match($metadata,'(?m)^version="([0-9A-Za-z.-]+)"').Groups[1].Value
if(!$version){throw 'Missing addon version'}
$build=Join-Path $root 'build'
$classes=Join-Path $build 'classes'
if(Test-Path $classes){
 $absolute=[IO.Path]::GetFullPath($classes);$expected=[IO.Path]::GetFullPath((Join-Path $root 'build/classes'));if($absolute -ne $expected){throw 'Unexpected build directory'};Remove-Item -LiteralPath $absolute -Recurse -Force
}
New-Item -ItemType Directory -Force $classes,(Join-Path $build 'tmp'),(Join-Path $root 'dist')|Out-Null
$server=Get-ChildItem (Join-Path $ServerRoot 'libraries/net/minecraft/server') -Recurse -Filter '*srg.jar'|Select-Object -First 1
$client=Get-ChildItem (Join-Path $ClientRoot 'libraries/net/minecraft/client') -Recurse -Filter '*srg.jar'|Select-Object -First 1
if(!$server -or !$client){throw 'Provide matching installed MC 1.21.1 server and client dependency roots.'}
$deps=@($client.FullName,$server.FullName)+@(Get-ChildItem (Join-Path $ServerRoot 'libraries') -Recurse -Filter '*.jar'|ForEach-Object FullName)+@(Get-ChildItem (Join-Path $ClientRoot 'libraries') -Recurse -Filter '*.jar'|ForEach-Object FullName)
$classpath=($deps|Select-Object -Unique)-join ';'
$sources=Get-ChildItem (Join-Path $root 'src/main/java') -Recurse -Filter '*.java'|ForEach-Object {'"'+$_.FullName.Replace('\','/')+'"'}
$argsFile=Join-Path $build 'javac.args'
$compilerArgs=@('-encoding UTF-8','-proc:none','--release 21',('-classpath "'+$classpath.Replace('\','/')+'"'),('-d "'+$classes.Replace('\','/')+'"'))+$sources
[IO.File]::WriteAllLines($argsFile,$compilerArgs,[Text.UTF8Encoding]::new($false))
& $javac "@$argsFile"
if($LASTEXITCODE){throw 'Addon compilation failed'}
Copy-Item (Join-Path $root 'src/main/resources/*') $classes -Recurse -Force
Copy-Item (Join-Path $root 'LICENSE') (Join-Path $classes 'META-INF/LICENSE.txt') -Force
$output=Join-Path $root ('dist/verity-friendly-companion-'+$version+'.jar')
& $jar ('-J-Djava.io.tmpdir='+ (Join-Path $build 'tmp')) --create --file $output -C $classes .
if($LASTEXITCODE){throw 'Addon packaging failed'}
Get-FileHash $output -Algorithm SHA256
