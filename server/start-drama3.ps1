param([string]$ServerRoot=(Get-Location).Path)
$ErrorActionPreference='Stop'
$voiceConfig=Join-Path $ServerRoot 'verity-service/voice-config.json'
if((Test-Path -LiteralPath $voiceConfig) -and ((Get-Content -LiteralPath $voiceConfig -Raw | ConvertFrom-Json).provider -ne 'drama3')){exit 0}
$tts=Join-Path $ServerRoot 'verity-tts'
try {
 $models=Invoke-RestMethod 'http://127.0.0.1:8773/v1/models' -TimeoutSec 2
 if(@($models.data | Where-Object {$_.id -eq 'verity-drama3' -and $_.family -eq 'fish_drama3'}).Count -gt 0){exit 0}
 throw 'Unexpected service on Drama 3 port'
} catch {
 if($_.Exception.Message -eq 'Unexpected service on Drama 3 port'){throw}
}
$native=Join-Path $tts 'runtime/audiocpp_server.exe'
$config=Join-Path $tts 'server.json'
if(!(Test-Path -LiteralPath $native) -or !(Test-Path -LiteralPath $config)){throw 'Install the server-only Drama 3 model and native runtime first.'}
Start-Process -FilePath $native -ArgumentList @('--config',('"'+$config+'"'),'--no-ui') -WorkingDirectory $tts -WindowStyle Hidden -RedirectStandardOutput (Join-Path $tts 'native.log') -RedirectStandardError (Join-Path $tts 'native-error.log')
$service=Join-Path $ServerRoot 'verity-service'
Start-Process -FilePath (Join-Path $service '.venv/Scripts/pythonw.exe') -ArgumentList ('"'+(Join-Path $service 'warm_drama3.py')+'"') -WorkingDirectory $service -WindowStyle Hidden -RedirectStandardOutput (Join-Path $tts 'warmup.log') -RedirectStandardError (Join-Path $tts 'warmup-error.log')
