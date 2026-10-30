param([ValidateRange(1,128)][int]$MemoryGB=6)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$runtime=Get-Content -LiteralPath (Join-Path $root 'config/verity-runtime.json') -Raw|ConvertFrom-Json
if(!(Test-Path -LiteralPath (Join-Path $root 'eula.txt')) -or [IO.File]::ReadAllText((Join-Path $root 'eula.txt')) -notmatch '(?m)^\s*eula=true\s*$'){
 throw 'Review https://aka.ms/MinecraftEULA and set eula=true in the server eula.txt if you agree. Setup never accepts the Minecraft EULA for you.'
}
$socket=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.vfc-jtmp'
New-Item -ItemType Directory -Force -Path $socket|Out-Null
. (Join-Path $root 'verity-service/dependency-tools.ps1')
function Test-VfcStartAudioWorker([string]$ServerRoot){
 if(Test-VfcNeedsPython $ServerRoot){return $true}
 # Advanced installations keep a prepared adapter idle so availability checks
 # can discover local engines while the current selection still uses Google.
 $manifestPath=Join-Path $ServerRoot 'config/verity-installation.json'
 $voicePath=Join-Path $ServerRoot 'verity-service/voice-config.json'
 if(!(Test-Path -LiteralPath $manifestPath) -or !(Test-Path -LiteralPath $voicePath)){return $false}
 try{
  $manifest=Get-Content -LiteralPath $manifestPath -Raw|ConvertFrom-Json
  if($manifest.profile -ne 'advanced'){return $false}
  $voice=Get-Content -LiteralPath $voicePath -Raw|ConvertFrom-Json
  $python=[string]$voice.python_executable
  $helper=Join-Path $ServerRoot 'verity-service/python_environment.py'
  if(!$python -or ![IO.Path]::IsPathRooted($python) -or !(Test-Path -LiteralPath $python -PathType Leaf) -or !(Test-Path -LiteralPath $helper -PathType Leaf)){return $false}
  $venvRoot=Split-Path (Split-Path $python)
  $null=& $python -B $helper --venv-root $venvRoot --inspect
  if($LASTEXITCODE){Write-Warning 'Advanced audio standby is unavailable: the selected Python venv failed preflight.';return $false}
  return $true
 }catch{Write-Warning 'Advanced audio standby is unavailable: repair the installation manifest or selected Python venv.';return $false}
}
if(Test-VfcStartAudioWorker $root){& (Join-Path $root 'verity-service/start-bridge.ps1') -ServerRoot $root}
Push-Location $root
try{& (Join-Path $runtime.javaHome 'bin/java.exe') '-Xms1G' ('-Xmx'+$MemoryGB+'G') ('-Djdk.net.unixdomain.tmpdir='+$socket.Replace('\','/')) ('@'+$runtime.neoforgeArgs) nogui;if($LASTEXITCODE){throw "Minecraft exited with code $LASTEXITCODE"}}finally{Pop-Location}
