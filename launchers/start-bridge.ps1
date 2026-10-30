param([string]$ServerRoot,[switch]$PassThru)
$ErrorActionPreference='Stop'
if(!$ServerRoot){if((Split-Path $PSScriptRoot -Leaf) -eq 'verity-service'){$ServerRoot=Split-Path $PSScriptRoot}else{throw 'Pass -ServerRoot with your Minecraft server directory'}}
$ServerRoot=[IO.Path]::GetFullPath($ServerRoot)
$service=Join-Path $ServerRoot 'verity-service'
$voiceConfig=Join-Path $service 'voice-config.json'
if(!(Test-Path -LiteralPath $voiceConfig)){throw "Run '$service/setup-server.cmd' first to create configuration and add server credentials"}
$config=Get-Content -LiteralPath $voiceConfig -Raw|ConvertFrom-Json
$port=if($config.port){[int]$config.port}else{8766}
if($port -lt 1024 -or $port -gt 65535){throw 'Invalid bridge port in voice-config.json'}
$endpoint='http://127.0.0.1:'+$port
function Test-Bridge {
 $health=$null
 try{$health=Invoke-RestMethod ($endpoint+'/health') -TimeoutSec 2}catch{return $false}
 if(!$health.ready){throw 'Another service is listening on the configured bridge port'}
 $tokenFile=Join-Path $service '.secrets/bridge-token'
 if(!(Test-Path -LiteralPath $tokenFile)){throw 'Existing listener cannot be authenticated: run setup-server.cmd, then check for an older bridge process'}
 $token=[IO.File]::ReadAllText($tokenFile).Trim()
 try{[void](Invoke-RestMethod ($endpoint+'/audio/status') -Method Post -Headers @{Authorization=('Bearer '+$token)} -ContentType 'application/json' -Body '{}' -TimeoutSec 3)}catch{throw 'The listener does not accept this server bridge token; check for an older/different bridge process'}finally{$token=$null}
 foreach($issue in $health.setup_issues){Write-Warning $issue.hint}
 Write-Host "Verity bridge running at $endpoint (no provider generation performed)"
 return $true
}
if(Test-Bridge){return}
$python=if($config.PSObject.Properties['python_executable'] -and $config.python_executable){[string]$config.python_executable}else{Join-Path $service '.venv/Scripts/python.exe'}
if(![IO.Path]::IsPathRooted($python)){throw 'python_executable must name the absolute isolated venv interpreter selected during setup'}
if(!(Test-Path -LiteralPath $python)){throw "Run '$service/setup-server.cmd' to install the server-only Python prerequisites"}
# Verify the selected venv before launch; this does not install packages or load models.
$venvRoot=Split-Path (Split-Path $python)
$environmentInfo=& $python -B (Join-Path $service 'python_environment.py') --venv-root $venvRoot --inspect
if($LASTEXITCODE){throw 'Selected Python venv failed preflight; rerun advanced setup to select an isolated Python 3.10+ environment'}
$pythonw=Join-Path (Split-Path $python) 'pythonw.exe'
if(Test-Path -LiteralPath $pythonw){$python=$pythonw}
$process=Start-Process -FilePath $python -ArgumentList ('"'+(Join-Path $service 'voice_server.py')+'"') -WorkingDirectory $service -WindowStyle Hidden -RedirectStandardOutput (Join-Path $service 'service.log') -RedirectStandardError (Join-Path $service 'service-error.log') -PassThru
for($attempt=0;$attempt -lt 12;$attempt++){
 if($process.HasExited){throw "Bridge exited during startup; inspect $service/service-error.log (do not share secrets)"}
 if(Test-Bridge){if($PassThru){return $process};return}
 Start-Sleep -Milliseconds 500
}
throw "Bridge startup did not finish; inspect $service/service-error.log. No game server was launched."
