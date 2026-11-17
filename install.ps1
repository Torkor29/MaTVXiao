# MaTV - installe tout sur tes TV Android / Google TV depuis Windows, en Wi-Fi, sans rien taper :
# l'installeur trouve les TV du reseau, te guide pour activer le debogage USB, puis installe ou met a jour
#   1. l'ecran d'accueil MaTV (Free My TV + nettoyage Xiaomi + apparence + fonds d'ecran + MaTV Connect)
#   2. MaTV Live (lecteur IPTV, codes Xtream)
#   3. TizenTube (YouTube sans pub + SponsorBlock), derniere version depuis GitHub
# Rien n'est desinstalle de la TV. Pour tout annuler : sur la TV, MaTV > Systeme > Ecran d'accueil > Accueil d'origine.
#
# Le plus simple : Installer-MaTV.bat (un double-clic, il prend toujours la derniere version de ce script).
# A la main : powershell -ExecutionPolicy Bypass -File install.ps1 [-TvIp 192.168.1.50]

param(
    [string]$TvIp = "",
    [switch]$SkipTizenTube
)

# "Continue": in Windows PowerShell 5.1, "Stop" turns any adb line on stderr into a fatal error.
$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest's progress bar makes downloads 10x slower in 5.1
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Say($text, $color = "White") { Write-Host $text -ForegroundColor $color }
function Step($n, $text) { Write-Host ""; Write-Host "[$n] $text" -ForegroundColor Cyan }

Write-Host ""
Write-Host "  MaTV - installation et mises a jour" -ForegroundColor White
Write-Host "  L'ordinateur doit etre sur le meme Wi-Fi que la TV." -ForegroundColor DarkGray

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

# --- Les TV du reseau ----------------------------------------------------------------------------------------------
# Deux facons de les voir : le port 5555 ouvert (debogage USB actif : prete a installer), et leur annonce sur le
# reseau (mDNS : _androidtvremote2 et _googlecast, que toute TV Android / Google TV publie avec son nom), qui trouve
# aussi celles ou le debogage n'est pas encore active.

function Get-LocalNets {
    if ($env:MATV_NETS) { return @($env:MATV_NETS -split "[ ,]+" | Where-Object { $_ }) }   # e.g. MATV_NETS=192.168.1
    $nets = @()
    foreach ($ni in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
        if ($ni.OperationalStatus -ne "Up" -or $ni.NetworkInterfaceType -eq "Loopback") { continue }
        if ($ni.Description -match "Hyper-V|VirtualBox|VMware|WSL|TAP|VPN|WireGuard|Tailscale|ZeroTier") { continue }
        foreach ($ua in $ni.GetIPProperties().UnicastAddresses) {
            if ($ua.Address.AddressFamily -ne "InterNetwork") { continue }
            $b = $ua.Address.GetAddressBytes()
            $private = ($b[0] -eq 10) -or ($b[0] -eq 172 -and $b[1] -ge 16 -and $b[1] -le 31) -or ($b[0] -eq 192 -and $b[1] -eq 168)
            if ($private) { $nets += "$($b[0]).$($b[1]).$($b[2])" }
        }
    }
    return @($nets | Select-Object -Unique)
}

# Which of these hosts answer on a TCP port (all at once, within $ms).
function Find-Open($hosts, $port, $ms) {
    $pending = @()
    foreach ($h in $hosts) {
        $c = New-Object System.Net.Sockets.TcpClient
        try { $pending += ,@($h, $c, $c.ConnectAsync($h, $port)) } catch { $c.Close() }
    }
    Start-Sleep -Milliseconds $ms
    $open = @()
    foreach ($p in $pending) {
        if ($p[2].IsCompleted -and -not $p[2].IsFaulted -and $p[1].Connected) { $open += $p[0] }
        try { $p[1].Close() } catch { }
    }
    return $open
}

# A DNS name in a message, following compression pointers.
function Read-DnsName([byte[]]$m, [int]$at) {
    $labels = @(); $end = -1; $guard = 0
    while ($at -lt $m.Length -and $guard -lt 64) {
        $guard++
        $len = $m[$at]
        if ($len -eq 0) { $at++; break }
        if (($len -band 0xC0) -eq 0xC0) {
            if ($end -lt 0) { $end = $at + 2 }
            $at = (($len -band 0x3F) * 256) + $m[$at + 1]
            continue
        }
        $labels += [Text.Encoding]::UTF8.GetString($m, $at + 1, $len)
        $at += 1 + $len
    }
    if ($end -lt 0) { $end = $at }
    return @(($labels -join "."), $end)
}

# One mDNS answer: the TV's name from its Android TV Remote announcement (or Cast's "fn"), keyed by its address.
function Read-Announce([byte[]]$m, [string]$ip, $found) {
    if ($m.Length -lt 12 -or ($m[2] -band 0x80) -eq 0) { return }   # answers only
    $qd = $m[4] * 256 + $m[5]; $rr = ($m[6] * 256 + $m[7]) + ($m[8] * 256 + $m[9]) + ($m[10] * 256 + $m[11])
    $at = 12
    for ($k = 0; $k -lt $qd; $k++) { $at = (Read-DnsName $m $at)[1] + 4 }
    $tv = $false; $name = ""
    for ($k = 0; $k -lt $rr -and $at + 10 -le $m.Length; $k++) {
        $n = Read-DnsName $m $at; $at = $n[1]
        $type = $m[$at] * 256 + $m[$at + 1]; $rdlen = $m[$at + 8] * 256 + $m[$at + 9]; $data = $at + 10
        if ($n[0] -match "_androidtvremote2\._tcp|_googlecast\._tcp") { $tv = $true }
        if ($type -eq 12) {   # PTR: "<the TV's name>._androidtvremote2._tcp.local"
            $target = (Read-DnsName $m $data)[0]
            if ($target -match "^(.+)\._androidtvremote2\._tcp\.local$" -and -not $name) { $name = $Matches[1]; $tv = $true }
            if ($target -match "_googlecast\._tcp") { $tv = $true }
        }
        if ($type -eq 16) {   # TXT: fn=<name> (Cast)
            $t = $data; $stop = [Math]::Min($data + $rdlen, $m.Length)
            while ($t -lt $stop) {
                $l = $m[$t]
                if ($t + 1 + $l -gt $stop) { break }
                $kv = [Text.Encoding]::UTF8.GetString($m, $t + 1, $l); $t += 1 + $l
                if ($kv.StartsWith("fn=") -and -not $name) { $name = $kv.Substring(3) }
            }
        }
        $at = $data + $rdlen
    }
    if ($tv -and (-not $found.ContainsKey($ip) -or (-not $found[$ip] -and $name))) { $found[$ip] = $name }
}

# Asks the network who offers the Android TV and Cast services; returns @{ ip = name } (the TVs' own names).
# Answers come straight back (we ask for unicast) or to the mDNS group, which a second socket listens to.
function Find-Announced {
    $found = @{}
    $socks = @()
    try {
        $ask = New-Object System.Net.Sockets.UdpClient
        $socks += $ask
        try {
            $group = New-Object System.Net.Sockets.UdpClient
            $group.Client.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::Socket, [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)
            $group.Client.Bind((New-Object System.Net.IPEndPoint ([System.Net.IPAddress]::Any), 5353))
            $group.JoinMulticastGroup([System.Net.IPAddress]::Parse("224.0.0.251"))
            $socks += $group
        } catch { }
        $q = New-Object System.Collections.Generic.List[byte]
        $q.AddRange([byte[]](0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0))   # id 0, 2 questions
        foreach ($svc in @("_androidtvremote2._tcp.local", "_googlecast._tcp.local")) {
            foreach ($label in $svc.Split(".")) { $q.Add([byte]$label.Length); $q.AddRange([Text.Encoding]::ASCII.GetBytes($label)) }
            $q.AddRange([byte[]](0, 0, 12, 0x80, 1))   # PTR, class IN, unicast answer wanted
        }
        $bytes = $q.ToArray()
        $ep = New-Object System.Net.IPEndPoint ([System.Net.IPAddress]::Parse("224.0.0.251")), 5353
        $until = (Get-Date).AddSeconds(3)
        $sent = 0
        while ((Get-Date) -lt $until) {
            if ($sent -lt 2 -and (Get-Date) -gt $until.AddSeconds(-3 + 1.2 * $sent)) { [void]$ask.Send($bytes, $bytes.Length, $ep); $sent++ }
            $got = $false
            foreach ($sock in $socks) {
                while ($sock.Available -gt 0) {
                    $from = New-Object System.Net.IPEndPoint ([System.Net.IPAddress]::Any), 0
                    try { $m = $sock.Receive([ref]$from); Read-Announce $m $from.Address.ToString() $found; $got = $true } catch { break }
                }
            }
            if (-not $got) { Start-Sleep -Milliseconds 60 }
        }
    } catch {
    } finally {
        foreach ($sock in $socks) { try { $sock.Close() } catch { } }
    }
    return $found
}

function Find-Tvs {
    Say "Recherche des TV sur le Wi-Fi..."
    $tvs = @{}
    $announced = Find-Announced
    foreach ($ip in $announced.Keys) { $tvs[$ip] = @{ ip = $ip; name = $announced[$ip]; ready = $false } }
    foreach ($net in @(Get-LocalNets)) {
        $hosts = 1..254 | ForEach-Object { "$net.$_" }
        foreach ($ip in @(Find-Open $hosts 5555 1200)) {
            if (-not $tvs[$ip]) { $tvs[$ip] = @{ ip = $ip; name = ""; ready = $true } } else { $tvs[$ip].ready = $true }
        }
    }
    return @($tvs.Values | Sort-Object { -not $_.ready }, { $_.ip })
}

function Wait-Debugging($ip) {
    Say ""
    Say "Sur la TV, une seule fois :" "Yellow"
    Say "  1. Parametres > Systeme (ou Preferences de l'appareil) > A propos :"
    Say "     appuie 7 fois sur 'Build' (ou 'Build du systeme Android TV')."
    Say "  2. Parametres > Systeme > Options pour les developpeurs : active 'Debogage USB'."
    Say "     (Sur certaines TV, active aussi 'Debogage reseau' ou 'ADB sans fil' si l'option existe.)"
    Say ""
    Say "J'attends que la TV soit prete (jusqu'a 10 minutes, Ctrl+C pour arreter)..." "DarkGray"
    for ($i = 0; $i -lt 200; $i++) {
        if (@(Find-Open @($ip) 5555 1500).Count -gt 0) { Say "La TV est prete." "Green"; return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

# --- Choix de la ou des TV ------------------------------------------------------------------------------------------
Step 2 "Choix de la TV"
$targets = @()
if ($TvIp) {
    $targets = @(@{ ip = $TvIp.Trim(); name = ""; ready = $true })
} else {
    while ($targets.Count -eq 0) {
        $tvs = @(Find-Tvs)   # @(): one TV must stay a list, not become the TV itself
        if ($tvs.Count -eq 0) {
            Say "Aucune TV trouvee. Verifie que la TV est allumee et sur le meme Wi-Fi que l'ordinateur." "Yellow"
            $answer = Read-Host "Entree pour chercher a nouveau, ou tape l'adresse IP de la TV"
            if ($answer.Trim()) { $targets = @(@{ ip = $answer.Trim(); name = ""; ready = $false }) }
            continue
        }
        Say ""
        for ($i = 0; $i -lt $tvs.Count; $i++) {
            $t = $tvs[$i]
            $label = if ($t.name) { $t.name } else { "TV" }
            $state = if ($t.ready) { "prete" } else { "debogage USB a activer" }
            Say ("  {0}. {1}  ({2})  - {3}" -f ($i + 1), $label, $t.ip, $state) $(if ($t.ready) { "Green" } else { "White" })
        }
        Say ""
        $hint = if ($tvs.Count -gt 1) { "Numero de la TV, plusieurs separes par des virgules, T pour toutes, ou Entree pour chercher a nouveau" } else { "Entree pour installer sur cette TV, ou R pour chercher a nouveau" }
        $answer = (Read-Host $hint).Trim()
        if ($tvs.Count -eq 1 -and $answer -eq "") { $targets = @($tvs[0]); break }
        if ($answer -match "^[rR]?$") { continue }
        if ($answer -match "^[tT]$") { $targets = $tvs; break }
        if ($answer -match "^\d+\.\d+\.\d+\.\d+(:\d+)?$") { $targets = @(@{ ip = $answer; name = ""; ready = $false }); break }
        foreach ($n in ($answer -split "[, ]+")) {
            if ($n -match "^\d+$" -and [int]$n -ge 1 -and [int]$n -le $tvs.Count) { $targets += $tvs[[int]$n - 1] }
        }
    }
}

# --- Les applis : derniere version publiee ---------------------------------------------------------------------------
# Les applis viennent de la derniere version publiee sur GitHub ; sinon du dossier apk a cote de ce script.
$matvRel = $null
try { $matvRel = Invoke-RestMethod "https://api.github.com/repos/Torkor29/MaTVXiao/releases/latest" -Headers @{ "User-Agent" = "MaTV-installer" } -ErrorAction Stop } catch { }
$apkCache = @{}
function Get-MaTVApk($name) {
    if ($apkCache[$name]) { return $apkCache[$name] }
    $file = Join-Path $here "apk\$name"
    if ($matvRel) {
        $asset = @($matvRel.assets | Where-Object { $_.name -eq $name }) | Select-Object -First 1
        if ($asset) {
            $dl = Join-Path $env:TEMP $name
            Say "Telechargement de $name ($($matvRel.tag_name))..."
            try { Invoke-WebRequest $asset.browser_download_url -OutFile $dl -UseBasicParsing -ErrorAction Stop; $file = $dl } catch { }
        }
    }
    $apkCache[$name] = $file
    return $file
}

function Install-OnTv($tv) {
    $TvIp = $tv.ip
    $label = if ($tv.name) { "$($tv.name) ($TvIp)" } else { $TvIp }
    Write-Host ""
    Write-Host ("=" * 70) -ForegroundColor DarkGray
    Write-Host "  $label" -ForegroundColor White
    Write-Host ("=" * 70) -ForegroundColor DarkGray
    if (-not $tv.ready -and -not ($TvIp -match ":\d+$") -and @(Find-Open @($TvIp) 5555 1500).Count -eq 0) {
        if (-not (Wait-Debugging $TvIp)) { Say "La TV n'a pas ouvert le debogage. Relance l'installeur une fois que c'est fait." "Red"; return }
    }
    $target = if ($TvIp -match ":\d+$") { $TvIp } else { "${TvIp}:5555" }
    & $adb disconnect $target *> $null
    & $adb connect $target | Out-Null
    Say ">> Regarde la TV : accepte 'Autoriser le debogage USB ?' et coche 'Toujours autoriser'." "Yellow"
    $ok = $false
    for ($i = 0; $i -lt 60; $i++) {
        $st = (& $adb -s $target get-state 2>$null)
        if ($st -match "device") { $ok = $true; break }
        if ($i % 10 -eq 9) { & $adb connect $target *> $null }
        Start-Sleep -Seconds 2
    }
    if (-not $ok) { Say "La TV n'a pas repondu. Verifie qu'elle est allumee et que le debogage USB est accepte, puis relance." "Red"; return }

    $brand = (& $adb -s $target shell getprop ro.product.brand).Trim()
    $model = (& $adb -s $target shell getprop ro.product.model).Trim()
    $android = (& $adb -s $target shell getprop ro.build.version.release).Trim()
    $abis = (& $adb -s $target shell getprop ro.product.cpu.abilist).Trim()
    Say "TV : $brand $model, Android $android ($abis)" "Green"

    # --- Ce qui est deja sur la TV (pour ne reinstaller que ce qui a change) -------------------------------------------
    $statePath = Join-Path $here ("matv-etat-" + ($TvIp -replace "[^0-9A-Za-z]", "-") + ".json")
    if (-not (Test-Path $statePath) -and (Test-Path (Join-Path $here "matv-etat.json"))) { Copy-Item (Join-Path $here "matv-etat.json") $statePath }
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
    $homeApk = Get-MaTVApk "MaTV-Home.apk"
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
    $liveApk = Get-MaTVApk "MaTV-Live.apk"
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
        Say "Rappel : si les suggestions ont disparu, Reglages > Suggestions > Cle TMDB (QR code)." "DarkGray"
        return
    }
    $state["done"] = $true; Save-State
    Step 6 "Derniere etape, sur la TV"
    Say "1. Ouvre l'appli 'MaTV' > Systeme > Ecran d'accueil > MaTV."
    Say "   La TV redemande 'Autoriser le debogage USB ?' : coche 'Toujours autoriser' et accepte."
    Say "   Si elle demande d'oublier le Wi-Fi puis de le reconnecter : fais-le, puis recommence."
    Say "2. Reglages > Systeme > Telecommande > YouTube > TizenTube : le bouton YouTube ouvre la version sans pub."
    Say "3. Ouvre 'MaTV Live' et entre tes codes Xtream."
    Say "4. Suggestions : Reglages > Suggestions > Cle TMDB, scanne le QR code avec ton telephone."
    Say "5. Telephone : MaTV > Telephone, scanne le QR code pour ouvrir MaTV Connect (fonds, telecommande)."
    Say ""
    Say "Ensuite, plus besoin de l'ordinateur : la TV propose les mises a jour toute seule." "Green"
    Say "Pour tout remettre comme avant : MaTV > Systeme > Ecran d'accueil > Accueil d'origine." "Green"
}

foreach ($tv in $targets) { Install-OnTv $tv }
Say ""
Say "Termine. Les prochaines mises a jour arrivent toutes seules sur la TV (ou relance cet installeur)." "Green"
