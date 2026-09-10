@echo off
set "JAVA_HOME=%~dp0"
call "%~dp0bin\sdrtrunk-vce.bat" %*
if errorlevel 1 pause
