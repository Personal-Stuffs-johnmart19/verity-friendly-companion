# Windows PowerShell 5.1 native asset preparation. Never starts a model or Python.
function Resolve-VfcAudioPath([string]$Service,[string]$Value){
 if([IO.Path]::IsPathRooted($Value)){return [IO.Path]::GetFullPath($Value)}
 return [IO.Path]::GetFullPath((Join-Path $Service $Value))
}
function Write-VfcAudioJson([string]$Path,$Value){
 New-Item -ItemType Directory -Force -Path (Split-Path $Path)|Out-Null
 $temporary=$Path+'.setup-tmp'
 [IO.File]::WriteAllText($temporary,($Value|ConvertTo-Json -Depth 24),[Text.UTF8Encoding]::new($false))
 Move-Item -LiteralPath $temporary -Destination $Path -Force
}
function Get-VfcPinnedModel([string]$Repo,[string]$Revision,[string]$Name,[string]$Directory,[switch]$Offline,[switch]$AllowLarge){
 if($Repo -notin 'audio-cpp/audio.cpp-gguf','Jmica/Drama-3-GGUF' -or $Revision -cnotmatch '^[a-f0-9]{40}$' -or $Name -match '(^|[\\/])\.\.?([\\/]|$)' -or [IO.Path]::IsPathRooted($Name)){throw 'Invalid pinned model identity'}
 $file=Join-Path $Directory $Name;$manifest=Join-Path $Directory 'verity-assets.json'
 if(Test-Path -LiteralPath $file){
  if(!(Test-Path -LiteralPath $manifest)){throw "Existing model has no integrity manifest; preserve it and supply a verified model directory: $file"}
  $saved=Get-Content -LiteralPath $manifest -Raw|ConvertFrom-Json
  $entry=@($saved.files|Where-Object name -ceq $Name)
  if($saved.repo -cne $Repo -or $saved.revision -cne $Revision -or $entry.Count -ne 1 -or $entry[0].sha256 -cnotmatch '^[a-f0-9]{64}$' -or (Get-Item -LiteralPath $file).Length -ne $entry[0].bytes -or (Get-FileHash -LiteralPath $file).Hash -ine $entry[0].sha256){throw 'Existing model integrity check failed; no file was replaced'}
  return $file
 }
 if($Offline){throw "Offline model missing: $file"}
 $metadata=Invoke-RestMethod -Uri ('https://huggingface.co/api/models/'+$Repo+'/revision/'+$Revision+'?blobs=true') -TimeoutSec 60
 $entries=@($metadata.siblings|Where-Object rfilename -ceq $Name)
 if($metadata.sha -cne $Revision -or $entries.Count -ne 1){throw 'Pinned model metadata does not match requested revision/file'}
 $entry=$entries[0]
 if(!$entry.PSObject.Properties['lfs'] -or !$entry.lfs -or $entry.lfs.sha256 -cnotmatch '^[a-f0-9]{64}$' -or [long]$entry.size -lt 1 -or [long]$entry.size -gt 40GB){throw 'Model metadata lacks bounded size and SHA256; refusing download'}
 $bytes=[long]$entry.size
 Write-Host ('Model '+$Repo+': '+[math]::Round($bytes/1GB,2)+' GiB at '+$Revision+'; preparation only')
 if($bytes -gt 2GB -and !$AllowLarge){throw 'Large model download requires explicit -DownloadLargeModels consent'}
 New-Item -ItemType Directory -Force -Path (Split-Path $file)|Out-Null
 $drive=[IO.DriveInfo]::new([IO.Path]::GetPathRoot([IO.Path]::GetFullPath($Directory)))
 if($drive.AvailableFreeSpace -lt $bytes*2){throw 'Insufficient disk space for model and temporary download'}
 $partial=$file+'.partial';$progress=$ProgressPreference
 try{
  $ProgressPreference='SilentlyContinue'
  Invoke-WebRequest -Uri ('https://huggingface.co/'+$Repo+'/resolve/'+$Revision+'/'+$Name+'?download=true') -OutFile $partial -UseBasicParsing -TimeoutSec 86400
  if((Get-Item -LiteralPath $partial).Length -ne $bytes -or (Get-FileHash -LiteralPath $partial).Hash -ine $entry.lfs.sha256){throw 'Model size/SHA256 mismatch; no model activated'}
  Move-Item -LiteralPath $partial -Destination $file
 }finally{$ProgressPreference=$progress}
 Write-VfcAudioJson $manifest @{repo=$Repo;revision=$Revision;files=@(@{name=$Name;bytes=$bytes;sha256=$entry.lfs.sha256})}
 return $file
}
function Install-VfcAudioArchive([string]$Archive,[string]$Directory,[switch]$RuntimeOnly){
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $zip=[IO.Compression.ZipFile]::OpenRead($Archive)
 $prefix=[IO.Path]::GetFullPath($Directory).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
 try{foreach($entry in $zip.Entries){$target=[IO.Path]::GetFullPath((Join-Path $Directory $entry.FullName));if(!$target.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe native archive path'}}}finally{$zip.Dispose()}
 New-Item -ItemType Directory -Force -Path $Directory|Out-Null
 Expand-Archive -LiteralPath $Archive -DestinationPath $Directory -Force
 if($RuntimeOnly){return}
 $executables=@(Get-ChildItem -LiteralPath $Directory -Recurse -Filter audiocpp_server.exe -File)
 if($executables.Count -ne 1){throw 'Native archive must contain one audio.cpp server'}
 return $executables[0].FullName
}
function Assert-VfcReferenceWave([string]$Path){
 $stream=[IO.File]::OpenRead($Path);$reader=[IO.BinaryReader]::new($stream)
 try{
  if($stream.Length -lt 44 -or $stream.Length -gt 64MB){throw 'Reference WAV size must be bounded (maximum 64 MiB)'}
  if([Text.Encoding]::ASCII.GetString($reader.ReadBytes(4)) -cne 'RIFF'){throw 'Reference must be a PCM16 WAV'}
  $null=$reader.ReadUInt32();if([Text.Encoding]::ASCII.GetString($reader.ReadBytes(4)) -cne 'WAVE'){throw 'Reference must be WAV'}
  $format=$null;$data=0L
  while($stream.Position+8 -le $stream.Length){
   $id=[Text.Encoding]::ASCII.GetString($reader.ReadBytes(4));$size=$reader.ReadUInt32();$next=$stream.Position+[long]$size+($size%2)
   if($next -gt $stream.Length){throw 'Truncated WAV chunk'}
   if($id -ceq 'fmt '){if($size -lt 16){throw 'Invalid WAV format'};$format=@{code=$reader.ReadUInt16();channels=$reader.ReadUInt16();rate=$reader.ReadUInt32();byteRate=$reader.ReadUInt32();align=$reader.ReadUInt16();bits=$reader.ReadUInt16()}}
   if($id -ceq 'data'){$data+=$size}
   $stream.Position=$next
  }
  if(!$format -or $format.code -ne 1 -or $format.bits -ne 16 -or $format.channels -notin 1,2 -or $format.rate -lt 8000 -or $format.rate -gt 192000 -or $format.align -ne $format.channels*2 -or $format.byteRate -ne $format.rate*$format.align -or $data%$format.align -ne 0 -or $data -lt $format.byteRate -or $data -gt 30L*$format.byteRate){throw 'Use PCM16 mono/stereo WAV, 1-30 seconds, 8-192 kHz; no resampling is performed. Convert MP3/OGG explicitly using the optional Python/PyAV preparation tool first.'}
 }finally{$reader.Dispose();$stream.Dispose()}
}
function Install-VfcAudioReferences([string]$Directory,[string]$ReferenceConfig,[switch]$CheckOnly){
 $voices=Join-Path $Directory 'voices';$textFile=Join-Path $voices 'reference-text.json'
 $texts=@{};if(Test-Path -LiteralPath $textFile){$old=Get-Content -LiteralPath $textFile -Raw|ConvertFrom-Json;foreach($property in $old.PSObject.Properties){$texts[$property.Name]=$property.Value}}
 $references=if($ReferenceConfig){Get-Content -LiteralPath $ReferenceConfig -Raw|ConvertFrom-Json}else{$null}
 $pending=@()
 foreach($language in @('ru','en')){
  $target=Join-Path $voices ('verity-'+$language+'.wav')
  if((Test-Path -LiteralPath $target) -and $texts[$language]){Assert-VfcReferenceWave $target;continue}
  if(!$references -or !$references.PSObject.Properties[$language]){throw 'Drama 3 needs RU/EN reference WAVs and transcripts. Supply -ReferenceConfig; existing references are preserved.'}
  $entry=$references.$language
  if(!$entry.PSObject.Properties['audio'] -or !$entry.PSObject.Properties['text'] -or !$entry.text -or $entry.text.Length -gt 8192 -or !(Test-Path -LiteralPath $entry.audio -PathType Leaf)){throw 'ReferenceConfig requires audio path and exact transcript for ru/en'}
  Assert-VfcReferenceWave $entry.audio
  if((Test-Path -LiteralPath $target) -and [IO.Path]::GetFullPath($target) -ine [IO.Path]::GetFullPath($entry.audio)){throw 'Existing reference audio is preserved; supply its own path/transcript or choose another directory'}
  $pending+=@{source=$entry.audio;target=$target;language=$language;text=$entry.text.Trim()}
 }
 if($CheckOnly){return}
 New-Item -ItemType Directory -Force -Path $voices|Out-Null
 foreach($item in $pending){if(!(Test-Path -LiteralPath $item.target)){Copy-Item -LiteralPath $item.source -Destination $item.target};$texts[$item.language]=$item.text}
 Write-VfcAudioJson $textFile $texts
}
function Initialize-VfcNativeAudio([string]$Service,$Voice,[string]$Cache,[string]$Stt,[string]$Tts,[string]$ReferenceConfig,[switch]$Offline,[switch]$AllowLarge){
 function Set-AudioValue([string]$Name,$Value){$Voice|Add-Member -NotePropertyName $Name -NotePropertyValue $Value -Force}
 $nativeEngines=@('vulkan','native-cuda','native-cpu')
 $needAsr=$Stt -in $nativeEngines
 $needDrama=$Tts -eq 'drama3' -or (Get-VfcLocalFallback $Voice $Tts) -eq 'drama3'
 $vulkanArchive=$null
 function Get-VulkanArchive {
  return Get-VfcDownload 'https://github.com/0xShug0/audio.cpp/releases/download/v0.9.3/audio-v0.9.3-bin-windows-x64-vulkan-portable.zip' 'audio-v0.9.3-bin-windows-x64-vulkan-portable.zip' '51f8d8ce6cc7789bc9dc3d15f35e05f143aaf2ce1cbecc525a4104bcdde82a42' $Cache -Offline:$Offline
 }
 if($needAsr){
  $backend=if($Stt -eq 'native-cpu'){'cpu'}elseif($Stt -eq 'native-cuda'){'cuda'}else{'vulkan'}
  $existing=if($Voice.PSObject.Properties['stt_native_executable']){Resolve-VfcAudioPath $Service $Voice.stt_native_executable}else{$null}
  if(!$existing -or !(Test-Path -LiteralPath $existing -PathType Leaf) -or !$Voice.PSObject.Properties['stt_native_backend'] -or $Voice.stt_native_backend -ne $backend){
   $archive=if($backend -eq 'cuda'){Get-VfcDownload 'https://github.com/0xShug0/audio.cpp/releases/download/v0.9.3/audio-v0.9.3-bin-windows-x64-cuda12.4.zip' 'audio-v0.9.3-bin-windows-x64-cuda12.4.zip' '976b1fe4329407be45b272fc656d91006f081d22706f453f8879bc69dbb4a9b3' $Cache -Offline:$Offline}elseif($backend -eq 'cpu'){Get-VfcDownload 'https://github.com/0xShug0/audio.cpp/releases/download/v0.9.3/audio-v0.9.3-bin-windows-x64-cpu-portable.zip' 'audio-v0.9.3-bin-windows-x64-cpu-portable.zip' 'f5fa4662b7ec323dbe31d37e224e46159b064836a9005da384551a5248771eeb' $Cache -Offline:$Offline}else{Get-VulkanArchive}
   $existing=Install-VfcAudioArchive $archive (Join-Path $Service ('native-asr/'+$backend+'/runtime'))
  }
  if($backend -eq 'cuda'){
   $runtime=Join-Path $Service 'native-asr/cuda/runtime'
   $runtimeMarker=Join-Path $runtime 'verity-cuda-runtime.json'
   if(!(Test-Path -LiteralPath $runtimeMarker)){
    $cudaRuntime=Get-VfcDownload 'https://github.com/0xShug0/audio.cpp/releases/download/v0.9.3/audio-v0.9.3-cudart-windows-x64-cuda12.4.zip' 'audio-v0.9.3-cudart-windows-x64-cuda12.4.zip' '866a2bc12c4d887eb3b1b62f82f7a8fdd93391cb77ea02b63b0f360cfb3bb4d8' $Cache -Offline:$Offline
    Install-VfcAudioArchive $cudaRuntime $runtime -RuntimeOnly
    Write-VfcAudioJson $runtimeMarker @{version='0.9.3';cuda='12.4';sha256='866a2bc12c4d887eb3b1b62f82f7a8fdd93391cb77ea02b63b0f360cfb3bb4d8'}
   }
   $libraries=@(Get-ChildItem -LiteralPath $runtime -Filter *.dll -File -Recurse|ForEach-Object DirectoryName|Sort-Object -Unique)
   if($libraries.Count -gt 64){throw 'Native runtime has too many library directories; refusing child PATH configuration'}
   if(!$libraries.Count){throw 'CUDA native runtime DLLs missing; rerun setup after repairing the owned native-asr/cuda/runtime directory'}
   Set-AudioValue 'stt_native_runtime_directory' $runtime
   Set-AudioValue 'stt_native_library_directories' $libraries
  }
  $model=if($Voice.PSObject.Properties['stt_native_model']){Resolve-VfcAudioPath $Service $Voice.stt_native_model}else{$null}
  if(!$model -or !(Test-Path -LiteralPath $model -PathType Leaf)){$model=Get-VfcPinnedModel 'audio-cpp/audio.cpp-gguf' '874fcb41ececda20bcbc0782d6b4ea7f7d069ea8' 'Qwen3-ASR-0.6B-GGUF/qwen3-asr-0.6b-f16.gguf' (Join-Path $Service 'models/native-asr') -Offline:$Offline -AllowLarge:$AllowLarge}
  $cpuFallback=(!$Voice.PSObject.Properties['stt_cpu_fallback'] -or $Voice.stt_cpu_fallback)
  if($cpuFallback -and $backend -ne 'cpu'){
   $cpuExecutable=if($Voice.PSObject.Properties['stt_native_cpu_executable']){Resolve-VfcAudioPath $Service $Voice.stt_native_cpu_executable}else{$null}
   if(!$cpuExecutable -or !(Test-Path -LiteralPath $cpuExecutable -PathType Leaf)){
    $cpuArchive=Get-VfcDownload 'https://github.com/0xShug0/audio.cpp/releases/download/v0.9.3/audio-v0.9.3-bin-windows-x64-cpu-portable.zip' 'audio-v0.9.3-bin-windows-x64-cpu-portable.zip' 'f5fa4662b7ec323dbe31d37e224e46159b064836a9005da384551a5248771eeb' $Cache -Offline:$Offline
    $cpuExecutable=Install-VfcAudioArchive $cpuArchive (Join-Path $Service 'native-asr/cpu/runtime')
   }
   Set-AudioValue 'stt_native_cpu_executable' $cpuExecutable
  }
  Set-AudioValue 'stt_engine' $Stt;Set-AudioValue 'stt_native_backend' $backend;Set-AudioValue 'stt_native_primary_backend' $backend;Set-AudioValue 'stt_native_executable' $existing;Set-AudioValue 'stt_native_model' $model
 }
 if($needDrama){
  $directory=if($Voice.PSObject.Properties['drama3_directory']){Resolve-VfcAudioPath $Service $Voice.drama3_directory}else{Resolve-VfcAudioPath $Service '../verity-tts'}
  Install-VfcAudioReferences $directory $ReferenceConfig
  $executable=if($Voice.PSObject.Properties['drama3_executable']){Resolve-VfcAudioPath $Service $Voice.drama3_executable}else{Join-Path $directory 'runtime/audiocpp_server.exe'}
  if(!(Test-Path -LiteralPath $executable -PathType Leaf)){
   if(!$vulkanArchive){$vulkanArchive=Get-VulkanArchive}
   $executable=Install-VfcAudioArchive $vulkanArchive (Join-Path $directory 'runtime')
  }
  $nativeConfig=Join-Path $directory 'server.json'
  if(!(Test-Path -LiteralPath $nativeConfig)){
   $model=Get-VfcPinnedModel 'Jmica/Drama-3-GGUF' '17a5b40c5dc2c2be986f5371a7e650108b828e29' 'drama-3-backbone-8bit-experts-Q4_K_S.gguf' (Join-Path $directory 'models/drama3-q4') -Offline:$Offline -AllowLarge:$AllowLarge
   Write-VfcAudioJson $nativeConfig @{host='127.0.0.1';port=8773;backend='vulkan';threads=4;lazy_load=$true;max_loaded_models=1;models=@(@{id='verity-drama3';family='fish_drama3';path=$model;task='tts';mode='streaming';session_options=@{'fish_drama3.pin_experts'=$false}})}
  }
  if($Tts -eq 'drama3'){Set-AudioValue 'provider' 'drama3'}
  Set-AudioValue 'drama3_directory' $directory;Set-AudioValue 'drama3_executable' $executable
 }
 return $Voice
}
