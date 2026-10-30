# MaTV : ta TV Xiaomi, sans pub, à ton goût

Pour la **Xiaomi TV A Pro 55"** (Google TV 14), et toute TV Android TV / Google TV.

| Pièce | D'où ça vient | Ce que ça fait |
|---|---|---|
| **MaTV (écran d'accueil)** `apk/MaTV-Home.apk` | [Free My TV](https://github.com/simbasjungles/free-my-tv) (MIT) + la liste Xiaomi testée de [CleanYourTV](https://github.com/Gathin23/cleanyourtv) (MIT) | Remplace l'accueil Google TV/PatchWall plein de pub, désactive la télémétrie et les promos Xiaomi, ajoute les réglages d'apparence et le mode art |
| **MaTV Live** `apk/MaTV-Live.apk` | nouvelle appli | Lecteur IPTV avec codes Xtream : chaînes avec guide, films, séries, favoris |
| **TizenTube** | [reisxd/TizenTubeCobalt](https://github.com/reisxd/TizenTubeCobalt) | YouTube sans pub, avec SponsorBlock, téléchargé à jour par l'installeur |

## Installer (Windows, environ 10 minutes)

1. **Sur la TV** : Paramètres → Système → À propos → appuie 7 fois sur *Build du système Android TV*. Puis Paramètres → Système → Options pour les développeurs → **Débogage USB** activé. Note l'adresse IP (Paramètres → Réseau et Internet).
2. **Sur le PC** (même Wi-Fi) : décompresse ce dossier, double-clique **`install.bat`**, tape l'IP de la TV.
   Accepte *Autoriser le débogage USB ?* sur la TV en cochant *Toujours autoriser*. Si Play Protect affiche *Appli à risque*, choisis *Plus d'infos* → *Installer quand même*.
3. **Sur la TV** : ouvre **Free My TV** → *Écran d'accueil* → *Free My TV*, puis accepte encore une fois le débogage.

`adb` est téléchargé tout seul depuis Google s'il manque.

## Régler sur la TV

*Free My TV* → *Réglages de l'accueil* :

- **Style du verre** : Verre liquide (reflets et réfraction), Givré ou Opaque.
- **Teinte du verre** : Légère, Normale ou Forte.
- **Couleur d'accent** : Neutre, Bleu, Violet, Rose, Or ou Vert.
- **Écran de veille** : *Suggestions et photos*, ou **Mode art (tableaux)**. Ce mode affiche des peintures du domaine public de l'Art Institute of Chicago et du Cleveland Museum of Art en paysage, avec le titre, l'artiste et le musée. Douze nouvelles œuvres arrivent toutes les 12 h, soixante au plus sont gardées.
- Et tout ce que Free My TV avait déjà : dock et ordre des applis, météo, horloge, thème jour/nuit, tes photos, applis récentes…

*Boutons de la télécommande* → **YouTube** → *TizenTube* : le bouton YouTube ouvre la version sans pub.

## Ce qui est désactivé (jamais désinstallé)

- Télémétrie Xiaomi et Google (analytics, statistiques, envoi de rapports qualité, feedback).
- Chaînes promo Mi TV+, l'onglet « Mi Home », Mi Channels, installations automatiques d'applis partenaires.
- Mi Music, la boîte à outils, le manuel, les pages légales, Play Jeux et YouTube Music.
- L'accueil Google TV et son économiseur d'écran. PatchWall reste actif, car il sert au choix des entrées sur certaines Xiaomi.

Tes applis installées et leurs connexions (Netflix, Prime, Disney+…) ne sont pas touchées. Les mises à jour du système, la télécommande, le Bluetooth, le HDMI, le tuner et Chromecast restent actifs.

**Tout annuler :** *Free My TV* → *Écran d'accueil* → *Original*. Tout revient comme à la sortie d'usine.

## MaTV Live

Au premier lancement, entre le serveur (`http://exemple.com:8080`), l'utilisateur et le mot de passe.

- Les onglets *Chaînes*, *Films*, *Séries* et *Favoris* affichent les catégories à gauche et les flux à droite, avec une recherche.
- **OK** lance la lecture, **OK long** (ou la touche Menu) ajoute un favori.
- Dans le lecteur : ▲ ▼ ou CH+/CH− pour zapper, les chiffres pour aller à une chaîne, OK pour voir ce qui passe. Dans un film, ◀ ▶ avancent ou reculent de 30 s, et l'épisode suivant s'enchaîne.
- Si un flux ne passe pas avec le lecteur de la TV, OK propose un lecteur externe (MX Player est préinstallé sur ta Xiaomi). *Compte* → *Lecteur* permet de l'utiliser tout le temps.

À utiliser avec un abonnement IPTV légal.

## Recompiler

Les sources sont dans `launcher/` et `iptv/`, en Java pur, sans Gradle. Il faut un JDK 17 et le SDK Android (build-tools 35, android-34). Lance `./build.sh --generic` dans `launcher/` et `./build.sh` dans `iptv/`, depuis Git Bash. Garde les fichiers `*.keystore` : une mise à jour doit être signée avec la même clé. Les vérifications de Free My TV passent (`launcher/tools/check.sh`).
