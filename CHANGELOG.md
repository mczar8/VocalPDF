# Historique des versions

## 1.1.0 (versionCode 5)

### Voix naturelles intégrées
- Deux voix neuronales livrées avec l'application, sans téléchargement ni connexion :
  français « Siwis » et anglais « LJSpeech » (modèles Piper, moteur sherpa-onnx 1.13.8).
- Choisies automatiquement pour les documents en français et en anglais.
- Rythme régulier : les longues phrases sont lues en groupes de souffle, avec des pauses mesurées
  (respiration, virgule, fin de phrase) ; voix française calibrée pour un débit naturel.
- Bascule automatique sur une voix du téléphone, avec un message, si une voix intégrée ne peut pas démarrer.

### Lecture plus intelligente
- Nettoyage du texte PDF : mots coupés recollés, ligatures, caractères invisibles.
- En-têtes, pieds de page et numéros de page supprimés automatiquement.
- Découpage en phrases respectant les abréviations (M., Dr, p. 12, e.g., z.B.…), titres et listes reconnus.
- Texte parlé optimisé : URL raccourcies, renvois « [12] » et pointillés de sommaire ignorés.
- Détection de la langue du document (FR, EN, ES, DE, IT, PT) et choix automatique de la voix.
- Pages blanches ou scannées sautées pendant l'écoute.

### Sécurité et discrétion
- Verrouillage par code PIN à 4 chiffres (cadenas), demandé à chaque ouverture.
- Pause automatique quand le casque est débranché, pendant un appel ou si une autre app joue du son.
- Confirmation avant de lire à voix haute en mode silencieux sans casque.

### Interface
- Menus repensés : barre du haut allégée, menu ⋮, panneau Paramètres en sections.
- Aller à la page, thème et taille du texte mémorisés, « Ouvrir avec » / « Partager vers VocalPDF ».
- Application traduite en 6 langues avec choix de la langue dans l'app.
- « À propos » complété : présentation, licence libre GPL v3, crédits des voix et du moteur,
  numéro de version et de build.

### Supprimé
- Textes d'exemple (l'historique des vrais PDF est conservé).
- Voix Cloud payantes et tout ce qui en dépendait : l'application n'a plus aucune permission Internet.
