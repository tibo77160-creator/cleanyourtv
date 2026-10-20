# Plume Launcher 1.3 : ce qu'il faut garder

Copie de référence du launcher Plume (github.com/aradhyas8/Plume_Launcher, version 1.3, commit 3c439a0 du 20/10/2026), pour pouvoir reprendre ses idées dans notre launcher le jour où on le souhaite.

Ce dossier contient, à l'identique, les fichiers essentiels de Plume (code Kotlin, manifeste, configuration du service Home, README et licence GPL-3.0). Les images et le reste du projet sont dans le dépôt d'origine, et une archive complète est gardée en local (philips-tv-tune/plume-reference).

Ce dossier est une copie séparée sous GPL-3.0 (voir LICENSE ici) ; il ne fait pas partie du launcher compilé, qui reste sous MIT.

## Licence : à lire avant de reprendre du code

Plume est sous GPL-3.0, notre launcher sous MIT. On peut s'inspirer des idées librement. Si on copie ou traduit du code de Plume dans notre launcher, celui-ci doit passer en GPL-3.0 (code ouvert, mention de l'auteur d'origine). Pour un usage perso, aucun souci ; il faut juste le faire proprement : garder le fichier LICENSE de Plume et citer « aradhyas8 » dans les fichiers repris.

## Les éléments importants, et où les trouver

Tout le code est dans `app/src/main/java/dev/plume/` (copié dans ce dossier). Les numéros de ligne renvoient à `MainActivity.kt`.

### 1. Rangée « Continuer à regarder » toutes applis confondues

Le point fort de Plume. C'est ce qui manquait à notre launcher.

- `queryWatchNext()` (l. 1880), `readPrograms()` (l. 1938), `continueChannels()` (l. 1922).
- Deux sources fusionnées :
  1. la liste système « À suivre » (`TvContract.WatchNextPrograms.CONTENT_URI`), triée par `last_engagement_time_utc_millis` ;
  2. les chaînes d'accueil nommées « Continue Watching » publiées par une appli (c'est le cas de Stremio).
- Colonnes utilisées : `title`, `season_display_number`, `episode_display_number`, `duration_millis`, `last_playback_position_millis` (barre de progression et « temps restant »), `thumbnail_uri` / `poster_art_uri` (image), `intent_uri` (reprise directe), `package_name`.
- Ignore les lignes `browsable = 0` et les entrées démarrées depuis moins d'une seconde.
- Équilibrage : au plus 3 éléments par appli, pour qu'une seule appli n'écrase pas les autres. Maximum 20 éléments au total.
- Clic : ouvre `intent_uri` avec `Intent.parseUri(..., URI_INTENT_SCHEME)`, sinon l'appli elle-même (`open()`, l. 2086).
- Permission nécessaire : `READ_TV_LISTINGS`, demandée à l'exécution. Notre launcher l'a déjà dans son manifeste.
- Réglage « Applis du Continuer à regarder » : liste des applis qui déclarent `WRITE_EPG_DATA` (`watchSources()`, l. 1900).
- Appui long sur une carte : « Retirer » (mémorisé par appli et titre).

### 2. Touche Home sur Google TV (OLED848)

- `HomeOverride.kt` (38 lignes) + `res/xml/home_override.xml`.
- Service d'accessibilité qui intercepte la touche Home avant le système :
  - appui court : ouvre le launcher ;
  - appui long (plus de 500 ms) : ouvre le panneau Google TV.
- Filet de sécurité : si l'accueil Google TV (`com.google.android.apps.tv.launcherx`) s'affiche quand même, il est aussitôt recouvert (sauf son « dashboard »).
- Pourquoi c'est mieux que notre méthode : on ne désactive pas l'accueil Google. Le désactiver peut faire redémarrer certaines Google TV en boucle.
- Le service ne peut pas lire l'écran (`canRetrieveWindowContent="false"`).
- Activation automatique si `WRITE_SECURE_SETTINGS` a été accordé par adb (`ensureHomeService()`, l. 309). Sinon, ouverture directe de l'écran Accessibilité (`turnOnHomeButton()`, l. 325).
- Launcher par défaut via `RoleManager.ROLE_HOME` (`makeDefaultHome()`, l. 355), la boîte de dialogue officielle d'Android. Ça suffit sur les Android TV classiques comme l'OLED706.

### 3. Entrées HDMI affichées comme des applis

- `consoleInputs()` (l. 1021) : `TvInputManager.tvInputList`, entrées de type HDMI qui ont un appareil CEC (`parentId`). Le nom vient du CEC, par exemple « PlayStation 5 ».
- Ouverture : `TvContract.buildChannelUriForPassthroughInput(parentId)`.
- Mise à jour automatique quand un appareil est branché ou débranché (`TvInputCallback`).

### 4. Mises à jour automatiques depuis GitHub

- `checkUpdate()` (l. 1693) : une fois par jour, dernière version sur `api.github.com/repos/.../releases/latest`.
- `installUpdate()` (l. 1719) : téléchargement de `Plume.apk` et installation via `PackageInstaller`, sans confirmation sur Android 12+.
- Prérequis : toujours signer avec la même clé. C'est ce qui nous a forcés à désinstaller notre launcher.

### 5. Fonds d'écran et thème

- Thème clair, sombre ou automatique, avec un fond par thème (`applyTheme()`, l. 388 ; `loadWallpaper()`, l. 438).
- Léger dégradé en bas pour garder les rangées lisibles (« Photo fade »).
- Envoi d'une photo depuis le téléphone : QR code et petit serveur web local sur la TV, actif seulement pendant que l'écran est ouvert (`WallpaperUpload.kt`, `Qr.kt`, `uploadWallpaper()`, l. 618).
- Dossier de photos en diaporama, sur la TV ou une clé USB (l. 543 à 590).

### 6. Rangée d'applis

- Logos monochromes ou en couleur (`res/drawable/logo_*.xml`, issus de Simple Icons, CC0) ; teinte d'accent extraite de l'icône (`accent()`, l. 1241).
- Réorganisation par appui long sur OK (`startEdit()` / `editKey()`, l. 1438 à 1500), tri par usage, masquage d'applis.
- Noms d'applis : toujours, au survol, ou jamais.
- Verrouillage de la disposition par code PIN à 4 chiffres (l. 820 à 945).

### 7. Économiseur d'écran « Galerie »

- `Gallery.kt` (DreamService) : peintures du domaine public (dossier `screensaver/`), téléchargées seulement si on le choisit, ou un dossier de tes photos.

### 8. Sobriété

- APK de 0,4 Mo, 50 à 70 Mo de mémoire vive, aucun service en arrière-plan.
- Connexions réseau limitées à GitHub (mises à jour), Open-Meteo et ipwho.is (météo), et aux images des affiches.

## Ce que notre launcher fait et que Plume ne fait pas

À garder si on fusionne un jour les deux (dépôt tibo77160-creator/cleanyourtv) :

- `EdgeGlowView.java` : bords gauche, haut et droit à la couleur de l'appli sélectionnée, repris par l'Ambilight.
- `BackdropView.java` : image plein écran par appli, avec les vidéos récentes de tes chaînes YouTube (flux RSS publics, sans clé) et les tendances TMDB par plateforme.
- `WeatherView.java` : tiroir météo détaillé (8 heures, lever et coucher du soleil).
- `Theme.java` : jour/nuit calculé sur le lever et le coucher du soleil à Melun.
- `Extras.java` : garde-fou anti-plantage (mode sans échec après 3 plantages en 5 minutes).
- Le script `philips_tune.py` : allègement des applis Philips et animations accélérées. Il est indépendant du launcher et toujours utile avec Plume.

## Plan si on reprend un jour

1. Partir de Plume (le plus abouti), sous un autre nom de paquet pour coexister avec l'officiel.
2. Y ajouter `EdgeGlowView` et `BackdropView` (le Java fonctionne tel quel dans un projet Kotlin).
3. Ajouter une clé de signature fixe dans les secrets GitHub du dépôt (à faire soi-même), puis rediriger la mise à jour automatique vers notre dépôt.
