# 🎙️ VocalPDF

**Lecteur PDF à voix haute, gratuit, sans publicité et 100 % hors ligne.**
*Free, ad-free, 100% offline PDF read-aloud app for Android. — [English below](#-english)*

VocalPDF lit vos documents PDF à voix haute, phrase par phrase, en surlignant le passage en cours.
Tout se passe sur votre téléphone : l'application n'a **aucune permission Internet** et ne collecte **aucune donnée**.

---

## ✨ Fonctionnalités

- **Voix naturelles intégrées** en français et en anglais (voix neuronales Piper), sans rien installer ni télécharger.
  Pour les autres langues, VocalPDF utilise les voix du téléphone.
- **Lecture intelligente** : mots coupés recollés, en-têtes et numéros de page ignorés, abréviations respectées,
  titres et listes reconnus, rythme régulier avec des pauses naturelles.
- **Détection automatique de la langue** du document : un PDF anglais est lu par une voix anglaise.
- **Verrouillage par code PIN** (cadenas) pour éviter un lancement par accident, par exemple en réunion.
- **Discrétion** : pause automatique si le casque est débranché ou pendant un appel ; confirmation avant de lire
  à voix haute quand le téléphone est en mode silencieux.
- **Reprise de lecture** à la phrase près, historique local, « Ouvrir avec » / « Partager » depuis d'autres apps.
- **6 langues** : français, anglais, espagnol, allemand, italien, portugais.
- Thème clair / sombre, taille du texte réglable, lecture écran éteint avec commandes dans la notification.

## 🔒 Confidentialité

Aucune donnée personnelle n'est collectée, transmise ou partagée. Voir la [politique de confidentialité](PRIVACY.md).

## 🛠️ Compiler le projet

1. Ouvrir le dossier dans **Android Studio** (version récente) et laisser Gradle se synchroniser.
   À la première synchronisation, le moteur sherpa-onnx (~48 Mo) est téléchargé automatiquement.
2. Lancer **Build › Make Project**.
   Au **premier build**, les voix intégrées (~120 Mo) sont téléchargées automatiquement depuis leur source
   officielle (tâche `prepareVoices`, voir `buildSrc/src/main/java/vocalpdf/build/VocalPdfVoices.java`).
   Les builds suivants n'ont plus besoin de connexion.
3. Lancer l'application sur un téléphone ou un émulateur.

Tests unitaires : `./gradlew testDebugUnitTest`
(sous Linux ou macOS, si `./gradlew` est refusé : `chmod +x gradlew`)

## 🧩 Architecture

| Dossier | Rôle |
|---|---|
| `text/` | Nettoyage du texte, découpage en phrases et en groupes de souffle, détection de langue (Kotlin pur, testé) |
| `pdf/` | Lecture du PDF (chargé une fois, pages en cache, page suivante préparée à l'avance) |
| `tts/` | Voix intégrées (sherpa-onnx), voix Android, file de lecture, focus audio |
| `security/` | Verrouillage par code PIN (code haché et salé, délais après erreurs) |
| `ui/` | Interface Jetpack Compose / Material 3 |
| `service/` | Lecture en arrière-plan et notification |

## 📜 Licences

- Code de VocalPDF : **GNU GPL v3** (voir `LICENSE`).
- Composants tiers (moteur sherpa-onnx, espeak-ng, voix Piper) : voir [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## 🤝 Contribuer

Les signalements de bugs, traductions et améliorations sont les bienvenus via les *Issues* et *Pull requests*.

---

## 🇬🇧 English

**VocalPDF** reads your PDF documents aloud, sentence by sentence, highlighting the current passage.
Everything happens on your phone: the app has **no Internet permission** and collects **no data**.

**Features:** built-in natural French and English voices (Piper neural voices) plus your phone's voices for other
languages · smart text cleanup (hyphenation, headers, page numbers, abbreviations, headings, lists, natural pauses) ·
automatic document language detection · PIN lock to prevent accidental launches · auto-pause when headphones are
unplugged or during calls · confirmation before reading aloud in silent mode · resume where you left off, local history,
"Open with" / "Share" support · 6 languages (FR, EN, ES, DE, IT, PT) · light/dark theme, adjustable text size,
background playback with notification controls.

**Privacy:** no personal data is collected, sent or shared — see the [privacy policy](PRIVACY.md).

**Build:** open the folder in Android Studio and run *Build › Make Project*. On the first build, the built-in voices
(~120 MB) and the sherpa-onnx engine (~48 MB) are downloaded automatically from their official source
(`prepareVoices` task in `buildSrc/src/main/java/vocalpdf/build/VocalPdfVoices.java`); later builds work offline.

**License:** GNU GPL v3. Third-party components: see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
