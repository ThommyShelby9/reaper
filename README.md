# Reaper

Lecteur de musique Android pour vos fichiers audio locaux, avec écoute synchronisée à plusieurs (« jam »).
Écrit en Kotlin avec Jetpack Compose et Media3.

L'interface s'inspire d'un appareil audio : touches en relief, potentiomètres, écran en matrice de points
et un seul accent orange. Deux thèmes, « Appareil » (clair) et « Nuit » (sombre).

## Fonctionnalités

**Lecture**
- Lecture en arrière-plan (Media3), file d'attente, lecture aléatoire, répétition
- Fondu enchaîné réglable de 0 à 12 s, mode Mix calé sur le tempo et la tonalité
- Égaliseur et renforcement des graves, réglages gardés par sortie audio (haut-parleur, casque, Bluetooth)
- Visualiseur en temps réel, sans permission micro
- Lecteur plein écran sur l'écran de verrouillage, widget d'écran d'accueil

**Bibliothèque**
- Titres, albums, artistes, dossiers, genres et humeurs ; recherche insensible aux accents
- Favoris, listes automatiques (ajoutés récemment, plus écoutés, à redécouvrir…), mix du jour et découvertes de la semaine
- Analyse sur le téléphone, hors ligne : tempo, tonalité (roue de Camelot), énergie, humeur
- Pochettes et paroles : celles du fichier, des fichiers .lrc, ou téléchargées (Cover Art Archive, LRCLIB), avec recherche manuelle
- Paroles synchronisées, affichées une phrase à la fois au rythme du morceau
- Nettoyage de l'affichage des étiquettes (sites de téléchargement, numéros de piste), sans modifier les fichiers
- Bilan d'écoute (temps, artistes, habitudes horaires)

**Jam (Firebase)**
- Écoute synchronisée : chacun joue sa propre copie du morceau, calée sur l'hôte
- Invitation par code, QR code ou lien ; jams à proximité sur le Wi-Fi local
- File collaborative avec votes, vote pour passer, réactions, récapitulatif enregistrable

**Partage**
- Après une capture d'écran de l'app, carte du morceau au format story (pochette, paroles) à partager, en statut WhatsApp par exemple

## Compiler

Prérequis : Android SDK (compileSdk 35), JDK 21.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # tests unitaires
```

Android 8.0 (API 26) minimum.

## Firebase

La jam et la connexion Google utilisent le projet Firebase décrit par `app/google-services.json`.
Pour votre propre version :

1. Créez un projet Firebase avec une app Android `com.lecteur.player` (ou changez l'identifiant de l'app).
2. Remplacez `app/google-services.json` par le vôtre et ajoutez l'empreinte SHA-1 de votre clé de signature.
3. Activez la connexion Google et Firestore, puis déployez les règles : `firebase deploy --only firestore`.
4. Pour les liens d'invitation, adaptez `hosting/` (page d'invitation et `assetlinks.json`) et `.firebaserc`.

Tout le reste de l'app fonctionne sans compte ni réseau.

## Services en ligne

Les pochettes et paroles manquantes sont cherchées sur [MusicBrainz](https://musicbrainz.org),
[Cover Art Archive](https://coverartarchive.org) et [LRCLIB](https://lrclib.net), qui ne reçoivent que
l'artiste, le titre et l'album cherchés. Désactivable dans les réglages (ou limité au Wi-Fi).

## Licence

[MIT](LICENSE)

Polices embarquées (Doto, Archivo, Space Mono) : SIL Open Font License, de leurs auteurs respectifs.
