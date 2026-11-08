# MaTV - installe tout sur ta TV Android / Google TV depuis Windows, en Wi-Fi.
#   1. l'ecran d'accueil MaTV (Free My TV + nettoyage Xiaomi + apparence + mode art)
#   2. MaTV Live (lecteur IPTV, codes Xtream)
#   3. TizenTube (YouTube sans pub + SponsorBlock), derniere version depuis GitHub
# Rien n'est desinstalle de la TV. Pour tout annuler : sur la TV, Free My TV > Ecran d'accueil > Original.
#
# Lancement : clic droit sur install.bat > Executer, ou dans PowerShell :
#   powershell -ExecutionPolicy Bypass -File install.ps1 -TvIp 192.168.1.50

param(
    [string]$TvIp = "",
    [switch]$SkipTizenTube
)

# "Continue": in Windows PowerShell 5.1, "Stop" turns any adb line on stderr into a fatal error.
$ErrorActionPreference = "Continue"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Say($text, $color = "White") { Write-Host $text -ForegroundColor $color }
function Step($n, $text) { Write-Host ""; Write-Host "[$n] $text" -ForegroundColor Cyan }

# --- adb ---------------------------------------------------------------------------------------------------------
Step 1 "Outils Android (adb)"
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $local = Join-Path $here "platform-tools\adb.exe"
    if (-not (Test-Path $local)) {
        Say "Telechargement des platform-tools de Google (environ 7 Mo)..."
        $zip = Join-Path $env:TEMP "platform-tools.zip"
        Invoke-WebRequest "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip -UseBasicParsing -ErrorAction Stop
        Expand-Archive $zip -DestinationPath $here -Force -ErrorAction Stop
        Remove-Item $zip
    }
    $adb = $local
}
Say "adb : $adb" "DarkGray"

# --- Connexion a la TV -------------------------------------------------------------------------------------------
Step 2 "Connexion a la TV"
Say "Sur la TV : Parametres > Systeme > A propos > appuie 7 fois sur 'Build du systeme Android TV',"
Say "puis Parametres > Systeme > Options pour les developpeurs > active 'Debogage USB'."
Say "L'adresse IP est dans Parametres > Reseau et Internet > ton Wi-Fi."
if (-not $TvIp) { $TvIp = Read-Host "Adresse IP de la TV (ex. 192.168.1.50)" }
$TvIp = $TvIp.Trim()
$target = if ($TvIp -match ":\d+$") { $TvIp } else { "${TvIp}:5555" }

& $adb disconnect $target *> $null
& $adb connect $target | Out-Host
Say ""
Say ">> Regarde la TV : accepte 'Autoriser le debogage USB ?' et coche 'Toujours autoriser'." "Yellow"
$ok = $false
for ($i = 0; $i -lt 60; $i++) {
    $state = (& $adb -s $target get-state 2>$null)
    if ($state -match "device") { $ok = $true; break }
    if ($i % 10 -eq 9) { & $adb connect $target *> $null }
    Start-Sleep -Seconds 2
}
if (-not $ok) { Say "La TV n'a pas repondu. Verifie l'IP, le debogage USB et que la TV est allumee, puis relance." "Red"; exit 1 }

$brand = (& $adb -s $target shell getprop ro.product.brand).Trim()
$model = (& $adb -s $target shell getprop ro.product.model).Trim()
$android = (& $adb -s $target shell getprop ro.build.version.release).Trim()
$abis = (& $adb -s $target shell getprop ro.product.cpu.abilist).Trim()
Say "TV : $brand $model, Android $android ($abis)" "Green"

# --- Ce qui est deja sur la TV (pour ne reinstaller que ce qui a change) -------------------------------------------
$statePath = Join-Path $here "matv-etat.json"
$state = @{}
if (Test-Path $statePath) {
    try { (Get-Content $statePath -Raw | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $state[$_.Name] = $_.Value } } catch { $state = @{} }
}
function Save-State { $state | ConvertTo-Json | Set-Content -Path $statePath -Encoding UTF8 }
function On-Tv($pkg) { return ((& $adb -s $target shell pm path $pkg 2>$null | Out-String) -match "package:") }
function Hash($path) { return (Get-FileHash $path -Algorithm SHA256).Hash }
$updated = 0

function Install-Apk($path, $label) {
    Say "Installation de $label..."
    Say "  (si rien ne bouge : regarde la TV, Play Protect peut demander 'Installer quand meme')" "DarkGray"
    $out = (& $adb -s $target install -r $path 2>&1 | Out-String)
    if ($out -match "Success") { Say "  $label installe." "Green"; return $true }
    Say "  Echec : $out" "Red"
    return $false
}

# --- Ecran d'accueil + IPTV --------------------------------------------------------------------------------------
Step 3 "Ecran d'accueil MaTV"
$homeApk = Join-Path $here "apk\MaTV-Home.apk"
$h = Hash $homeApk
if ($state["home"] -eq $h -and (On-Tv "io.freemytv")) { Say "  Deja a jour, rien a faire." "DarkGray" }
else {
    $launcherOk = Install-Apk $homeApk "MaTV (ecran d'accueil)"
    if ($launcherOk) { $state["home"] = $h; Save-State; $updated++ }
    if ($launcherOk -and $brand -match "TCL") {
        & $adb -s $target shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow" *> $null
    }
}

Step 4 "MaTV Live (IPTV)"
$liveApk = Join-Path $here "apk\MaTV-Live.apk"
$h = Hash $liveApk
if ($state["live"] -eq $h -and (On-Tv "io.matv.iptv")) { Say "  Deja a jour, rien a faire." "DarkGray" }
elseif (Install-Apk $liveApk "MaTV Live") { $state["live"] = $h; Save-State; $updated++ }

# --- TizenTube -----------------------------------------------------------------------------------------------------
if (-not $SkipTizenTube) {
    Step 5 "TizenTube (YouTube sans pub)"
    try {
        $rel = Invoke-RestMethod "https://api.github.com/repos/reisxd/TizenTubeCobalt/releases/latest" -Headers @{ "User-Agent" = "MaTV-installer" } -ErrorAction Stop
        $apks = @($rel.assets | Where-Object { $_.name -like "*.apk" })
        if ($apks.Count -eq 0) { throw "aucun APK dans la derniere version" }
        # The asset that matches the TV's processor, else the first APK.
        $pick = $null
        foreach ($abi in ($abis -split ",")) {
            $short = switch -Regex ($abi) { "arm64" { "arm64" } "armeabi" { "arm" } "x86_64" { "x86_64" } default { $abi } }
            $pick = $apks | Where-Object { $_.name -match [regex]::Escape($short) } | Select-Object -First 1
            if ($pick) { break }
        }
        if (-not $pick) { $pick = $apks | Where-Object { $_.name -match "universal|release" } | Select-Object -First 1 }
        if (-not $pick) { $pick = $apks[0] }
        $ttPkg = $state["ttPkg"]
        if ($state["tt"] -eq $rel.tag_name -and $ttPkg -and (On-Tv $ttPkg)) { Say "  Deja a jour ($($rel.tag_name)), rien a faire." "DarkGray"; throw "SKIP" }
        $before = (& $adb -s $target shell pm list packages -3 2>$null | Out-String) -split "`r?`n"
        $file = Join-Path $env:TEMP $pick.name
        Say "Telechargement de $($pick.name) ($($rel.tag_name))..."
        Invoke-WebRequest $pick.browser_download_url -OutFile $file -UseBasicParsing -ErrorAction Stop
        if (Install-Apk $file "TizenTube") {
            if (-not $ttPkg) {   # its package name, learned from what appeared on the TV
                $after = (& $adb -s $target shell pm list packages -3 2>$null | Out-String) -split "`r?`n"
                $new = @($after | Where-Object { $_ -and ($before -notcontains $_) })
                if ($new.Count -ge 1) { $ttPkg = ($new[0] -replace "^package:", "").Trim() }
            }
            $state["tt"] = $rel.tag_name; if ($ttPkg) { $state["ttPkg"] = $ttPkg }; Save-State; $updated++
        }
        Remove-Item $file -ErrorAction SilentlyContinue
    } catch {
        if ($_.Exception.Message -ne "SKIP") {
            Say "TizenTube n'a pas pu etre installe automatiquement : $($_.Exception.Message)" "Yellow"
            Say "Telecharge l'APK sur https://github.com/reisxd/TizenTubeCobalt/releases puis : adb install -r <fichier>.apk" "Yellow"
        }
    }
}

# --- Fin -------------------------------------------------------------------------------------------------------------
& $adb disconnect $target *> $null   # avant le basculement : la TV doit poser sa question a MaTV, pas a l'ordinateur
if ($state["done"]) {   # a later run: only updates
    Say ""
    if ($updated -eq 0) { Say "Tout etait deja a jour." "Green" } else { Say "Mise a jour terminee ($updated appli(s)). Tes reglages sont conserves." "Green" }
    exit 0
}
$state["done"] = $true; Save-State
Step 6 "Derniere etape, sur la TV"
Say "1. Ouvre l'appli 'Free My TV' > Ecran d'accueil > Free My TV."
Say "   La TV redemande 'Autoriser le debogage USB ?' : coche 'Toujours autoriser' et accepte."
Say "   Si elle demande d'oublier le Wi-Fi puis de le reconnecter : fais-le, puis recommence."
Say "2. Free My TV > Reglages de l'accueil :"
Say "   Style du verre, Teinte du verre, Couleur d'accent, Ecran de veille > Mode art (tableaux)."
Say "3. Boutons de la telecommande > YouTube > TizenTube : le bouton YouTube ouvre la version sans pub."
Say "4. Ouvre 'MaTV Live' et entre tes codes Xtream."
Say ""
Say "Pour tout remettre comme avant : Free My TV > Ecran d'accueil > Original." "Green"
