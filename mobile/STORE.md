# Publier l'appli MaTV sur l'App Store et Google Play

L'appli (iPhone : `ios/`, Android : `android/`) trouve les TV du Wi‑Fi, y installe MaTV, puis les pilote. GitHub la compile et la teste à chaque changement (`.github/workflows/mobile.yml`, onglet *Actions* → *Mobile apps*) : les écrans pour les fiches des stores sortent de ces tests.

---

## 1. Google Play (Android)

### Ce qu'il te faut
- Un compte **Google Play Console** (25 $ une fois) : https://play.google.com/console. Google vérifie ton identité (pièce d'identité), compte quelques jours.
- **Compte personnel créé après le 13 novembre 2023 : test fermé obligatoire.** Avant de publier pour tout le monde, au moins **12 testeurs** doivent rester inscrits au test fermé **14 jours d'affilée**. Prévois-les (famille, amis avec un Android). Un compte « organisation » (avec un numéro D‑U‑N‑S) n'a pas cette étape.
- Le fichier à envoyer : **`MaTV-1.0.0.aab`** (je te l'ai envoyé), signé avec ta **clé d'envoi** `upload.keystore` (envoyée aussi, avec son mot de passe dans `upload-key.txt`). **Garde ces deux fichiers précieusement** (gestionnaire de mots de passe + une copie hors de l'ordinateur). Google signe ensuite l'appli avec sa propre clé (Play App Signing) ; si tu perds la clé d'envoi, le support Google peut la remplacer, mais c'est long.

### Étapes
1. Play Console → **Créer une appli** : nom `MaTV`, langue par défaut Français, *Appli*, *Gratuite*.
2. **Configurer l'appli** (tableau de bord, « Configurer votre application ») :
   - *Règles de confidentialité* : `https://github.com/Torkor29/MaTVXiao/blob/main/mobile/PRIVACY.md`
   - *Accès à l'appli* : « Toutes les fonctionnalités sont disponibles sans restriction » + note : « Sans TV Android, touchez “Essayer sans TV” sur l'écran d'accueil : une TV de démonstration s'ouvre. »
   - *Annonces* : Non. *Classification du contenu* : questionnaire (catégorie Utilitaire, aucun contenu sensible) → PEGI 3.
   - *Public cible* : 13 ans et plus (ou 18+). *Appli d'actualités* : Non.
   - *Sécurité des données* : **aucune donnée collectée, aucune donnée partagée** (voir PRIVACY.md : tout reste sur le téléphone ou va à la TV de l'utilisateur).
   - *Applis gouvernementales / financières / santé* : Non.
3. **Fiche Play Store** : textes ci-dessous (section 3), icône 512 × 512 (`store/icon-512.png`), image de présentation 1024 × 500 (`store/feature-1024x500.png`), captures téléphone (`store/android/*.png`).
4. **Tests → Test fermé** : crée une piste, ajoute les e‑mails de tes 12 testeurs (une liste), envoie `MaTV-1.0.0.aab`, *Envoyer pour examen*. Partage le lien d'inscription aux testeurs ; ils installent depuis le Play Store.
5. Après 14 jours : **Production → Demander l'accès à la production**, réponds aux questions, puis publie la version.

### Les versions suivantes
Monte `versionCode` (et `versionName`) dans `android/app/build.gradle`. Puis :
- soit sur ton ordinateur : `cd mobile/android && ./gradlew bundleRelease`, avec `upload.keystore` et `keystore.properties` à côté de `build.gradle` (`storeFile=upload.keystore`, `storePassword=…`, `keyAlias=matv-upload`, `keyPassword=…`) ;
- soit par GitHub : ajoute les secrets (*Settings → Secrets and variables → Actions*) `ANDROID_UPLOAD_KEYSTORE` (le fichier en base64 : `base64 -i upload.keystore`), `ANDROID_UPLOAD_PASSWORD`, `ANDROID_UPLOAD_ALIAS` (`matv-upload`). Chaque build « Mobile apps » produit alors l'AAB signé (artefact `android`).

---

## 2. App Store (iPhone)

### Ce qu'il te faut
- L'**Apple Developer Program** (99 $ par an) : https://developer.apple.com/programs/ , avec ton identifiant Apple. Particulier : validation en 1 à 2 jours.
- **Pas besoin de Mac** : GitHub compile l'appli et l'envoie sur TestFlight. Il lui faut une clé d'API App Store Connect (étape 2).

### Étapes
1. **App Store Connect** (https://appstoreconnect.apple.com) → *Apps* → **+** → *Nouvelle app* : plateforme iOS, nom `MaTV – Ta TV, à ton goût` (le nom doit être libre sur l'App Store ; sinon une variante), langue Français, **identifiant de lot `io.matv.app`** (s'il n'apparaît pas dans la liste : developer.apple.com → *Certificates, IDs & Profiles* → *Identifiers* → **+** → *App IDs* → `io.matv.app`, sans option particulière), SKU `matv-ios`.
2. **Clé d'API** : App Store Connect → *Utilisateurs et accès* → *Intégrations* → *App Store Connect API* → **+** : nom `GitHub`, accès **Admin**. Télécharge le fichier `AuthKey_XXXX.p8` (une seule fois possible). Note l'**ID de la clé** et l'**ID de l'émetteur** (Issuer ID) affiché en haut.
3. Ton **Team ID** : developer.apple.com → *Account* → *Membership details* (10 caractères).
4. Sur GitHub, dépôt MaTVXiao → *Settings → Secrets and variables → Actions* → *New repository secret*, quatre fois :
   - `APPLE_TEAM_ID` : le Team ID
   - `ASC_KEY_ID` : l'ID de la clé
   - `ASC_ISSUER_ID` : l'Issuer ID
   - `ASC_KEY_P8` : tout le contenu du fichier `.p8` (ouvre-le avec un éditeur de texte, copie tout, lignes BEGIN/END comprises)
5. GitHub → *Actions* → **Mobile apps** → *Run workflow* → coche **« Envoyer l'app iPhone sur TestFlight »** → *Run*. Environ 20 minutes plus tard, la version est dans App Store Connect → *TestFlight*.
6. Sur ton iPhone : appli **TestFlight** (App Store), tu es testeur interne d'office → installe MaTV et essaie-la avec ta TV.
7. **Fiche App Store** (App Store Connect → ton app → *Distribution*) :
   - textes, mots-clés, URL d'assistance et de confidentialité : section 3 ;
   - captures **iPhone 6,9"** : `store/ios/*.png` (GitHub les produit aussi à chaque build, artefact `ios`) ;
   - *Confidentialité de l'app* → **Données non collectées** ;
   - *Classification par âge* : 4+ ; *Catégorie* : Utilitaires (secondaire : Divertissement) ; *Prix* : Gratuit ;
   - *Chiffrement* : déjà déclaré dans l'appli (`ITSAppUsesNonExemptEncryption = NO` : seulement le chiffrement standard d'Apple).
8. **Version** : choisis le build envoyé par TestFlight, remplis les *Notes pour la vérification* (section 3), puis **Soumettre pour vérification**. Réponse en général sous 48 h.

### Les versions suivantes
Monte `MARKETING_VERSION` dans `ios/project.yml` (le numéro de build suit tout seul), puis relance *Run workflow* avec la case cochée.

---

## 3. Textes des fiches

**Nom** : MaTV – Ta TV, à ton goût
**Sous-titre (App Store, 30 car.)** : Installe et pilote ta TV
**Description courte (Google Play, 80 car.)** : Un écran d'accueil sans pub pour ta TV Android, installé et piloté depuis ton téléphone.

**Description** :
> MaTV transforme ta TV Android ou Google TV : un écran d'accueil épuré, sans publicité, avec tes applis, des suggestions de films et séries selon ton humeur, et de magnifiques fonds d'écran en 4K.
>
> L'appli fait tout depuis ton téléphone, sur le même Wi‑Fi :
> • Elle trouve tes TV toute seule, avec leur nom.
> • Elle te guide pour préparer la TV (une seule fois, une minute avec la télécommande).
> • Elle installe MaTV et le tient à jour.
> • Elle devient ta télécommande : pavé tactile, volume, texte à taper, applis à ouvrir d'un geste.
> • Elle gère tes fonds d'écran : des milliers de photos et de tableaux libres de droits, tes propres photos, des collections, l'écran de veille.
> • Elle règle les suggestions selon ton humeur : rire, action, frissons, émotion…
>
> Rien ne passe par nos serveurs : ton téléphone parle directement à ta TV. Pas de compte, pas de pub, pas de traçage.
>
> Pas de TV sous la main ? Touche « Essayer sans TV » pour découvrir l'appli avec une TV de démonstration.
>
> Compatible avec les TV Android et Google TV (Xiaomi, TCL, Sony, Philips, Hisense…), Android 8 ou plus récent.

**Mots-clés (App Store, 100 car.)** : tv,android tv,google tv,télécommande,fond d'écran,xiaomi,tcl,accueil,écran de veille,wifi
**URL d'assistance** : https://github.com/Torkor29/MaTVXiao/issues
**URL de confidentialité** : https://github.com/Torkor29/MaTVXiao/blob/main/mobile/PRIVACY.md

**Notes pour la vérification (Apple et Google)** :
> MaTV installs and controls a home screen app on Android TV / Google TV sets on the user's own Wi‑Fi. Reviewers without an Android TV can try every control screen with the built-in demo: on the first screen, tap "Pas de TV sous la main ? Essayer sans TV". The demo TV runs inside the app; nothing is sent anywhere. With a real TV, the app connects to it over the local network (Android's ADB, after the user allows it on the TV), installs the MaTV app on the TV (never on the phone), and then talks to the web server MaTV runs on the TV. No account, no data collection.

---

## 4. Ce qui peut coincer à la validation

- **Apple et l'installation sur la TV** : les règles d'Apple interdisent qu'une appli télécharge du code *pour elle-même*. Ici les fichiers vont sur la TV et ne s'exécutent jamais sur l'iPhone, d'où les notes ci-dessus et la mise en avant du pilotage. Si un vérificateur bloque quand même, la réponse type : « The downloaded APK files are installed on the user's Android TV over the local network; no code is downloaded or executed on the iOS device. »
- **Google Play** : même chose pour l'installation sur un autre appareil ; des applis du même genre sont sur le Play Store. Ne parle pas de « contourner » quoi que ce soit dans les fiches.
- **MaTV Live** : décris-le comme un lecteur vidéo pour des abonnements que l'utilisateur possède ; ne cite ni chaînes ni contenus. TizenTube (YouTube sans pub) n'est **pas** dans l'appli des stores (les vérificateurs le refuseraient) : il reste dans l'installeur pour ordinateur.
- **Réseau local** : au premier lancement, l'iPhone demande « MaTV souhaite trouver des appareils sur votre réseau local » : il faut répondre *Autoriser*.
