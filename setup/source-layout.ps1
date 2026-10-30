# Source tree to installed flat service mapping. Installed data paths never move.
$script:VfcLayoutDirectory=$PSScriptRoot
function Get-VfcSourceRoot {
 $parent=Split-Path $script:VfcLayoutDirectory
 if((Split-Path $script:VfcLayoutDirectory -Leaf) -eq 'setup' -and (Test-Path -LiteralPath (Join-Path $parent 'src/main/resources/META-INF/neoforge.mods.toml'))){return $parent}
 return $script:VfcLayoutDirectory
}
function Get-VfcServiceFiles {
 $root=Get-VfcSourceRoot
 $directories=if($root -ne $script:VfcLayoutDirectory){@('config','runtime/python','setup','setup/python','setup/linux','setup/windows','launchers')|ForEach-Object {Join-Path $root $_}}else{@($root)}
 $names=@{}
 foreach($directory in $directories){
  if(!(Test-Path -LiteralPath $directory -PathType Container)){continue}
  $ancestor=[IO.Path]::GetFullPath($directory)
  while($ancestor){
   if((Get-Item -LiteralPath $ancestor).Attributes -band [IO.FileAttributes]::ReparsePoint){throw ('Service source directory must not be a symbolic link or junction: '+$ancestor)}
   if($ancestor -eq [IO.Path]::GetFullPath($root)){break}
   $ancestor=Split-Path $ancestor
  }
  foreach($file in Get-ChildItem -LiteralPath $directory -File){
   if($file.Extension -notin '.py','.ps1','.cmd','.sh' -and $file.Name -notlike 'requirements*.txt' -and $file.Name -notlike '*.example.json' -and $file.Name -ne 'LinuxSetup.java'){continue}
   if($file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw ('Service source file must not be a symbolic link: '+$file.Name)}
   if($names.ContainsKey($file.Name)){throw ('Duplicate installed service filename: '+$file.Name)}
   $names[$file.Name]=$true
   $file
  }
 }
 if($root -ne $script:VfcLayoutDirectory){
  $path=Join-Path $root 'setup.cmd'
  if(Test-Path -LiteralPath $path -PathType Leaf){
   $file=Get-Item -LiteralPath $path
   if($file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw ('Service source file must not be a symbolic link: '+$file.Name)}
   if($names.ContainsKey($file.Name)){throw ('Duplicate installed service filename: '+$file.Name)}
   $names[$file.Name]=$true
   $file
  }
 }
}
function Get-VfcClientFiles {
 $files=@(Get-VfcServiceFiles)
 foreach($name in @('setup-client.cmd','setup-client.ps1','dependency-tools.ps1','source-layout.ps1')){
  $matching=@($files|Where-Object Name -eq $name)
  if($matching.Count -ne 1){throw ('Missing client setup dependency: '+$name)}
  $matching[0]
 }
 # Linux entry points share the small shell helpers; no Python or live JSON is copied.
 $files|Where-Object {$_.Extension -eq '.sh' -or $_.Name -eq 'LinuxSetup.java' -or $_.Name -in @('setup.cmd','setup.ps1','install.cmd','install.ps1') -or $_.Name -like 'windows-*.ps1'}
}
function Resolve-VfcSourceFile([string]$Name){
 $files=@(Get-VfcServiceFiles|Where-Object Name -eq $Name)
 if($files.Count -ne 1){throw ('Missing or ambiguous service source file: '+$Name)}
 return $files[0].FullName
}
