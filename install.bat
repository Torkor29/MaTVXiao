@echo off
rem Double-clique ce fichier pour installer MaTV sur ta TV (IP 192.168.1.189).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -TvIp 192.168.1.189
pause
