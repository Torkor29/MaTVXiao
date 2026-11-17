@echo off
rem Double-clique ce fichier pour installer ou mettre a jour MaTV sur les TV de ton Wi-Fi (elles sont trouvees toutes seules).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
pause
