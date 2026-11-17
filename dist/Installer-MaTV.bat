@echo off
rem MaTV : double-clique pour installer ou mettre a jour MaTV sur les TV de ton Wi-Fi.
rem Il prend toujours la derniere version de l installeur sur GitHub.
title MaTV
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol='Tls12'; $d=Join-Path $env:LOCALAPPDATA 'MaTV'; New-Item -ItemType Directory -Force $d | Out-Null; $f=Join-Path $d 'install.ps1'; try { Invoke-WebRequest 'https://raw.githubusercontent.com/Torkor29/MaTVXiao/main/install.ps1' -OutFile $f -UseBasicParsing } catch { Write-Host 'Pas d acces a GitHub : verifie la connexion Internet.' -ForegroundColor Red; exit 1 }; & $f"
pause
