#!/usr/bin/env bash
# MaTV - installe ou met a jour MaTV sur tes TV Android / Google TV depuis un Mac ou Linux, en Wi-Fi.
# L'installeur trouve les TV du reseau (celles dont le debogage USB est actif), te guide sinon, et installe :
# l'ecran d'accueil MaTV, MaTV Live et TizenTube (YouTube sans pub). Rien n'est desinstalle de la TV.
#
#   curl -fsSL https://raw.githubusercontent.com/Torkor29/MaTVXiao/main/install.sh | bash
#   ./install.sh [IP de la TV]
set -u
REPO="Torkor29/MaTVXiao"
HOME_DIR="${MATV_DIR:-$HOME/.matv}"
mkdir -p "$HOME_DIR"
say() { printf '%s\n' "$*"; }
step() { printf '\n\033[36m[%s] %s\033[0m\n' "$1" "$2"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
red() { printf '\033[31m%s\033[0m\n' "$*"; }
# Talks to the terminal even when the script itself comes through a pipe (curl ... | bash).
ask() { local a; read -r -p "$1 " a < /dev/tty; printf '%s' "$a"; }

say ""
say "  MaTV - installation et mises a jour"
say "  L'ordinateur doit etre sur le meme Wi-Fi que la TV."

# --- adb ----------------------------------------------------------------------------------------------------------
step 1 "Outils Android (adb)"
ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
  ADB="$HOME_DIR/platform-tools/adb"
  if [ ! -x "$ADB" ]; then
    case "$(uname -s)" in Darwin) os=darwin ;; *) os=linux ;; esac
    say "Telechargement des platform-tools de Google..."
    curl -fsSL -o "$HOME_DIR/pt.zip" "https://dl.google.com/android/repository/platform-tools-latest-$os.zip" || { red "Telechargement impossible."; exit 1; }
    (cd "$HOME_DIR" && unzip -qo pt.zip && rm -f pt.zip)
  fi
fi
say "adb : $ADB"

# --- Les TV du reseau -----------------------------------------------------------------------------------------------
local_nets() {
  if [ -n "${MATV_NETS:-}" ]; then echo "$MATV_NETS" | tr ' ,' '\n\n'; return; fi   # e.g. MATV_NETS=192.168.1
  { if command -v ip >/dev/null 2>&1; then ip -4 -o addr show scope global | awk '{print $4}' | cut -d/ -f1
    else for i in en0 en1 en2 en3 en4 en5; do ipconfig getifaddr "$i" 2>/dev/null; done; fi; } |
    grep -E '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)' | awk -F. '{print $1"."$2"."$3}' | sort -u
}

# Is host:port open? (one second at most)
open_port() {
  local host="$1" port="$2"
  ( exec 3<>"/dev/tcp/$host/$port" ) 2>/dev/null &
  local pid=$!
  ( sleep 1; kill "$pid" 2>/dev/null ) >/dev/null 2>&1 &
  local killer=$!
  wait "$pid" 2>/dev/null; local rc=$?
  kill "$killer" 2>/dev/null; wait "$killer" 2>/dev/null
  return $rc
}

find_tvs() {
  local out="$HOME_DIR/found.$$"
  : > "$out"
  for net in $(local_nets); do
    for i in $(seq 1 254); do
      ( open_port "$net.$i" 5555 && echo "$net.$i" >> "$out" ) &
    done
    wait
  done
  sort -t. -k4 -n -u "$out"
  rm -f "$out"
}

wait_debugging() {
  local ip="$1"
  say ""
  yellow "Sur la TV, une seule fois :"
  say "  1. Parametres > Systeme (ou Preferences de l'appareil) > A propos : appuie 7 fois sur 'Build'."
  say "  2. Parametres > Systeme > Options pour les developpeurs : active 'Debogage USB'."
  say ""
  say "J'attends que la TV soit prete (jusqu'a 10 minutes, Ctrl+C pour arreter)..."
  for _ in $(seq 1 300); do
    if open_port "$ip" 5555; then green "La TV est prete."; return 0; fi
    sleep 2
  done
  return 1
}

step 2 "Choix de la TV"
TARGETS=()
if [ "${1:-}" != "" ]; then
  TARGETS=("$1")
else
  while [ ${#TARGETS[@]} -eq 0 ]; do
    say "Recherche des TV sur le Wi-Fi..."
    FOUND=()
    while IFS= read -r line; do [ -n "$line" ] && FOUND+=("$line"); done < <(find_tvs)
    if [ ${#FOUND[@]} -eq 0 ]; then
      yellow "Aucune TV prete trouvee (le debogage USB doit etre actif)."
      a="$(ask "Tape l'adresse IP de la TV (Parametres > Reseau), ou Entree pour chercher a nouveau :")"
      if [ -n "$a" ]; then TARGETS=("$a"); fi
      continue
    fi
    say ""
    n=1
    for ip in "${FOUND[@]}"; do green "  $n. TV ($ip) - prete"; n=$((n + 1)); done
    say ""
    if [ ${#FOUND[@]} -eq 1 ]; then
      a="$(ask "Entree pour installer sur cette TV, ou R pour chercher a nouveau :")"
      case "$a" in r|R) continue ;; "") TARGETS=("${FOUND[0]}") ;; *) TARGETS=("$a") ;; esac
    else
      a="$(ask "Numero de la TV, plusieurs separes par des espaces, T pour toutes, ou Entree pour chercher a nouveau :")"
      case "$a" in
        "") continue ;;
        t|T) TARGETS=("${FOUND[@]}") ;;
        *.*.*.*) TARGETS=("$a") ;;
        *) for k in $(echo "$a" | tr ',' ' '); do
             if [ "$k" -ge 1 ] 2>/dev/null && [ "$k" -le ${#FOUND[@]} ]; then TARGETS+=("${FOUND[$((k - 1))]}"); fi
           done ;;
      esac
    fi
  done
fi

# --- Les applis : derniere version publiee --------------------------------------------------------------------------
release_url() {   # URL of an asset of the latest release of repo $1 whose name matches $2
  curl -fsSL "https://api.github.com/repos/$1/releases/latest" | grep -o '"browser_download_url": *"[^"]*"' |
    cut -d'"' -f4 | grep -E "$2" | head -1
}
TAG="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | grep -o '"tag_name": *"[^"]*"' | cut -d'"' -f4)"
for name in MaTV-Home.apk MaTV-Live.apk; do
  say "Telechargement de $name ($TAG)..."
  curl -fsSL -o "$HOME_DIR/$name" "https://github.com/$REPO/releases/latest/download/$name" || red "  $name introuvable."
done

install_apk() {   # target file label
  say "Installation de $3..."
  say "  (si rien ne bouge : regarde la TV, Play Protect peut demander 'Installer quand meme')"
  if "$ADB" -s "$1" install -r "$2" 2>&1 | grep -q Success; then green "  $3 installe."; return 0; fi
  red "  Echec de l'installation de $3."; return 1
}

install_on() {
  local ip="$1" target
  case "$ip" in *:*) target="$ip" ;; *) target="$ip:5555" ;; esac
  say ""; say "======================================================================"; say "  $ip"; say "======================================================================"
  case "$ip" in *:*) ;; *) open_port "$ip" 5555 || wait_debugging "$ip" || { red "La TV n'a pas ouvert le debogage."; return; } ;; esac
  "$ADB" disconnect "$target" >/dev/null 2>&1
  "$ADB" connect "$target" >/dev/null 2>&1
  yellow ">> Regarde la TV : accepte 'Autoriser le debogage USB ?' et coche 'Toujours autoriser'."
  local ok=0
  for i in $(seq 1 60); do
    if "$ADB" -s "$target" get-state 2>/dev/null | grep -q device; then ok=1; break; fi
    [ $((i % 10)) -eq 0 ] && "$ADB" connect "$target" >/dev/null 2>&1
    sleep 2
  done
  [ $ok -eq 1 ] || { red "La TV n'a pas repondu."; return; }
  local brand model abis
  brand="$("$ADB" -s "$target" shell getprop ro.product.brand | tr -d '\r')"
  model="$("$ADB" -s "$target" shell getprop ro.product.model | tr -d '\r')"
  abis="$("$ADB" -s "$target" shell getprop ro.product.cpu.abilist | tr -d '\r')"
  green "TV : $brand $model"
  step 3 "Ecran d'accueil MaTV"
  install_apk "$target" "$HOME_DIR/MaTV-Home.apk" "MaTV (ecran d'accueil)" &&
    case "$brand" in *TCL*|*tcl*) "$ADB" -s "$target" shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow" >/dev/null 2>&1 ;; esac
  step 4 "MaTV Live (IPTV)"
  install_apk "$target" "$HOME_DIR/MaTV-Live.apk" "MaTV Live"
  step 5 "TizenTube (YouTube sans pub)"
  local arch="arm64"; case "$abis" in arm64*) arch="arm64" ;; armeabi*) arch="arm" ;; x86_64*) arch="x86_64" ;; esac
  local tt; tt="$(release_url reisxd/TizenTubeCobalt "$arch.*\.apk$")"
  [ -z "$tt" ] && tt="$(release_url reisxd/TizenTubeCobalt "\.apk$")"
  if [ -n "$tt" ] && curl -fsSL -o "$HOME_DIR/tizentube.apk" "$tt"; then install_apk "$target" "$HOME_DIR/tizentube.apk" "TizenTube"
  else yellow "TizenTube n'a pas pu etre telecharge."; fi
  "$ADB" disconnect "$target" >/dev/null 2>&1
  say ""
  say "Sur la TV : ouvre l'appli 'MaTV' > Systeme > Ecran d'accueil > MaTV (la TV redemande le debogage : accepte)."
  say "Puis MaTV > Telephone : scanne le QR code pour gerer la TV depuis ton telephone."
}

for t in "${TARGETS[@]}"; do install_on "$t"; done
say ""
green "Termine. Les prochaines mises a jour arrivent toutes seules sur la TV (ou relance cet installeur)."
