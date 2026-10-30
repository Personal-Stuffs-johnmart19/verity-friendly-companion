param([string]$ServerRoot=(Get-Location).Path)
$ErrorActionPreference='Stop'
$service=Join-Path $ServerRoot 'verity-service'
$voiceConfig=Join-Path $service 'voice-config.json'
if((Test-Path -LiteralPath $voiceConfig) -and ((Get-Content -LiteralPath $voiceConfig -Raw | ConvertFrom-Json).provider -eq 'drama3')){& (Join-Path $service 'start-drama3.ps1') -ServerRoot $ServerRoot}
try{if((Invoke-RestMethod 'http://127.0.0.1:8766/health' -TimeoutSec 2).ready){exit 0}}catch{}
$service=Join-Path $ServerRoot 'verity-service'
$python=Join-Path $service '.venv/Scripts/pythonw.exe'
if(!(Test-Path $python)){throw 'Install the server-only Python environment described in the addon README.'}
Start-Process -FilePath $python -ArgumentList ('"'+(Join-Path $service 'voice_server.py')+'"') -WorkingDirectory $service -WindowStyle Hidden -RedirectStandardOutput (Join-Path $service 'service.log') -RedirectStandardError (Join-Path $service 'service-error.log')
