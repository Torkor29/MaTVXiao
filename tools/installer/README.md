# Tests de MaTV Installer (l'appli téléphone)

- `fake_tv.py` : une fausse TV (adbd) qui vérifie la signature RSA, le format de clé, le contrôle de flux et
  l'octet près de chaque installation. `python3 fake_tv.py 5555` (ou `--old` pour Android 8-10, `--deny`, `--maxdata 4096`).
- `test/` : projet Robolectric. `ShotsTest` dessine les écrans dans `shots/`, `ScanTest` cherche la fausse TV,
  `FlowTest` fait une vraie installation sur la fausse TV (téléchargement GitHub compris).
  `FlowTest` a besoin des certificats des APK dans `test/certs/` :
  `apksigner verify --print-certs-pem MaTV-Home.apk` → `Home.der` (idem `Live.der`).
  Lancer : `gradle testDebugUnitTest` (avec `sdk.dir` dans `local.properties`).
