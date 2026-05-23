@echo off
setlocal

cd /d "%~dp0"

if "%SERVER_PORT%"=="" set "SERVER_PORT=8000"

mvn compile exec:java "-Dexec.mainClass=com.agentmesh.deterministic.StackLauncher"
