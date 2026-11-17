# MaTV : ta TV Xiaomi, sans pub, à ton goût

Pour la **Xiaomi TV A Pro 55"** (Google TV 14), et toute TV Android TV / Google TV.

| Pièce | D'où ça vient | Ce que ça fait |
|---|---|---|
| **MaTV (écran d'accueil)** `apk/MaTV-Home.apk` | [Free My TV](https://github.com/simbasjungles/free-my-tv) (MIT) + la liste Xiaomi testée de [CleanYourTV](https://github.com/Gathin23/cleanyourtv) (MIT) | Remplace l'accueil Google TV/PatchWall plein de pub, désactive la télémétrie et les promos Xiaomi, ajoute les réglages d'apparence et le mode art |
| **MaTV Live** `apk/MaTV-Live.apk` | nouvelle appli | Lecteur IPTV avec codes Xtream : chaînes avec guide, films, séries, favoris |
| **TizenTube** | [reisxd/TizenTubeCobalt](https://github.com/reisxd/TizenTubeCobalt) | YouTube sans pub, avec SponsorBlock, téléchargé à jour par l'installeur |

## Installer sur une TV (environ 10 minutes, rien à taper)

Il suffit d'un ordinateur sur **le même Wi‑Fi que la TV**. L'installeur trouve tout seul les TV du réseau, affiche leur nom et installe sur celle que tu choisis (ou sur toutes).

1. **Sur la TV, une seule fois** : Paramètres → Système → À propos → appuie 7 fois sur *Build du système Android TV*. Puis Paramètres → Système → Options pour les développeurs → **Débogage USB** activé. *(Android TV l'exige pour installer une appli depuis un ordinateur ; si tu l'oublies, l'installeur te guide et attend.)*
2. **Sur l'ordinateur** :
   - **Windows** : télécharge [`Installer-MaTV.bat`](https://github.com/Torkor29/MaTVXiao/releases/latest/download/Installer-MaTV.bat) et double-clique dessus.
   - **Mac / Linux** : dans le Terminal, `curl -fsSL https://raw.githubusercontent.com/Torkor29/MaTVXiao/main/install.sh | bash`
   
   Choisis la TV dans la liste. Sur la TV, accepte *Autoriser le débogage USB ?* en cochant *Toujours autoriser*. Si Play Protect affiche *Appli à risque* : *Plus d'infos* → *Installer quand même*.
3. **Sur la TV** : ouvre l'appli **MaTV** → *Système* → *Écran d'accueil* → **MaTV**, puis accepte encore une fois le débogage.

L'installeur télécharge lui-même `adb` (chez Google), la dernière version de MaTV et TizenTube. Relancé plus tard, il ne réinstalle que ce qui a changé. Pour viser une TV précise : `install.bat 192.168.1.42` (ou `… | bash -s 192.168.1.42`).

## Régler sur la TV

**Réglages** (la roue dentée en haut à droite, ou *Réglages de l'accueil* dans le tiroir du bas), rangés par catégories, chaque option avec une explication :

- **Apparence** : thème (automatique, clair, sombre), style du verre (liquide, givré, opaque), teinte, couleur d'accent, taille des applis du dock, sons.
- **Accueil** : haut de l'écran (*Suggestions*, *Vous regardiez* ou une horloge), applis du dock et leur ordre, boutons du haut, entrées et raccourcis du tiroir, icônes d'état.
- **Suggestions** : **humeur** (Rire, Action, Frissons, Enquête, Émotion, Évasion, En famille, Découvrir, ou Tout), présentation **Affiche** (image plein écran et vignettes, façon Netflix) ou **Cartes** (façon Apple TV), nombre de titres, services, clé TMDB.
- **Écran de veille** : **Fonds d'écran** (tes images, en plein écran) ou suggestions, la collection qui défile, durée par image, horloge, légende, aperçu. Les fonds se gèrent depuis le téléphone (MaTV Connect).
- **Widgets** : météo, ville, prévisions, lecture en cours, cours des cryptos.
- **Téléphone** : le QR code de MaTV Connect.
- **Système** : écran d'accueil (retour à l'ancienne TV avec *Accueil d'origine*), langue, télécommande, minuterie de veille, mises à jour, réglages de la TV.

*Boutons de la télécommande* → **YouTube** → *TizenTube* : le bouton YouTube ouvre la version sans pub.

## MaTV Connect : la TV depuis ton téléphone

Sur la TV : *Réglages* → **Téléphone**, puis scanne le QR code avec l'appareil photo du téléphone (même Wi‑Fi). Une web app s'ouvre ; sur iPhone, *Partager* → *Sur l'écran d'accueil* la garde comme une appli. Elle marche aussi depuis un ordinateur.

- **Ma TV** : ce que montre l'écran de veille dans une TV miniature, et ses réglages (contenu, collection, durée, horloge, légende). *Lancer sur la TV* affiche les fonds tout de suite.
- **Découvrir** : environ 1 400 photos et tableaux en haute définition (images « de qualité » de Wikimedia Commons, sous licence libre), rangés par thèmes : paysages, montagnes, lacs, mer, villes, Paris, Japon, peintures, espace… et une recherche. **+** envoie l'image sur la TV, qui la télécharge elle-même et la recadre en 16:9.
- **Mes fonds** : tous les fonds de la TV, par collection. Importer tes photos (avec recadrage), ranger, retirer de la rotation, supprimer, créer des collections, afficher un fond sur la TV.
- **Télécommande** : pavé tactile, retour, accueil, lecture, volume, texte à taper sur la TV, et les applis de la TV à ouvrir d'un geste. Elle passe par le débogage USB de la TV, déjà activé pour MaTV.

La web app est servie par la TV elle-même (`http://<IP de la TV>:8765`) : rien ne passe par un serveur extérieur, à part les images de Wikimedia. Seul un téléphone qui a scanné le QR code peut agir ; *Déconnecter les téléphones* change le code. La bibliothèque en ligne est dans `library/index.json` (outils dans `tools/library/`).

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

## Mises à jour

La TV vérifie chaque jour la dernière version publiée sur ce dépôt (onglet *Releases*) :

- **MaTV** l'annonce à l'écran ; *Réglages* → *Système* → *Mises à jour* l'installe.
- **MaTV Live** la propose à son ouverture (*Compte* → *Rechercher une mise à jour* pour vérifier tout de suite). La première fois, Android demande d'autoriser MaTV Live à installer ses mises à jour.

Avant chaque installation, la TV vérifie que le fichier vient bien de ce dépôt, qu'il n'a pas été modifié (empreinte SHA-256) et qu'il est **signé avec la clé de MaTV**. Une version signée avec une autre clé est refusée.

Les clés de signature (`launcher/freemytv.keystore` et `iptv/matvlive.keystore`) ne sont **jamais** sur GitHub. Garde-en une copie : sans elles, une nouvelle version ne pourra pas s'installer par-dessus l'ancienne.

Pour une nouvelle TV, `Installer-MaTV.bat` (ou `install.sh`) suffit : il trouve la TV sur le Wi‑Fi. Ensuite elle se met à jour toute seule.

## Clé TMDB

Les versions publiées ne contiennent aucune clé. Pour les suggestions, crée une clé gratuite sur themoviedb.org (*Paramètres* → *API*), puis sur la TV : *Réglages* → *Suggestions* → *Clé TMDB*. Scanne le QR code avec ton téléphone et colle la clé : elle reste sur la TV.
