# Composants tiers intégrés à VocalPDF

Ces fichiers ne sont pas stockés dans le dépôt : ils sont téléchargés automatiquement depuis leur source officielle
au premier build (tâche `prepareVoices`, voir `buildSrc/src/main/java/vocalpdf/build/VocalPdfVoices.java`), puis intégrés à l'application.

## sherpa-onnx 1.13.8 (moteur de synthèse vocale)
- Fichier : `app/libs/sherpa-onnx-1.13.8.aar`
- Source : https://github.com/k2-fsa/sherpa-onnx
- Licence : Apache License 2.0 (inclut ONNX Runtime, licence MIT, et espeak-ng)

## Données de prononciation espeak-ng
- Dossier : `app/src/main/assets/espeak-ng-data/` (réduit aux dictionnaires français et anglais)
- Source : https://github.com/espeak-ng/espeak-ng
- Licence : GPL v3 (données de prononciation lues par le moteur, non modifiées)
- espeak-ng est aussi compilé dans les bibliothèques natives de sherpa-onnx (via piper-phonemize).
  Sa licence GPL v3 s'applique à l'application qui le distribue : c'est pourquoi VocalPDF est publié
  sous licence GPL v3, avec son code source.

## Voix française « Siwis » (Piper, qualité medium)
- Dossier : `app/src/main/assets/voices/fr_FR-siwis-medium/` (voir `MODEL_CARD`)
- Données : SIWIS French Speech Synthesis Database, Université d'Édimbourg, https://datashare.is.ed.ac.uk/handle/10283/2353
- Licence des données : Creative Commons Attribution 4.0 (CC BY 4.0), attribution affichée dans l'app (Paramètres › À propos)
- Remarque : ce modèle a été affiné à partir de la voix Piper anglaise « lessac », dont le jeu de données
  (Blizzard Challenge 2013) est sous licence de recherche. La voix « siwis » est diffusée publiquement par le
  projet Piper et largement réutilisée. VocalPDF est une application gratuite et sans but commercial.

## Voix anglaise « LJSpeech » (Piper, qualité medium)
- Dossier : `app/src/main/assets/voices/en_US-ljspeech-medium/` (voir `MODEL_CARD`)
- Données : LJ Speech Dataset, https://keithito.com/LJ-Speech-Dataset/
- Licence : domaine public ; modèle entraîné de zéro (sans voix de base)

Les modèles convertis pour sherpa-onnx proviennent de https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
