@echo off
setlocal
rem PowerShell 7 may pass a module path without Windows PowerShell's modules.
set "PSModulePath=%SystemRoot%\System32\WindowsPowerShell\v1.0\Modules;%ProgramFiles%\WindowsPowerShell\Modules;%PSModulePath%"
set "VFC_SETUP=%~dp0setup\windows\setup.ps1"
if not exist "%VFC_SETUP%" set "VFC_SETUP=%~dp0setup.ps1"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%VFC_SETUP%" %*
exit /b %errorlevel%
