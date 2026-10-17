// Outils de compilation de VocalPDF : téléchargement des voix intégrées et du moteur sherpa-onnx.
// Écrit en Java pur : aucun plugin à télécharger, et l'analyse « lint » d'Android ne s'y perd pas.
plugins {
    java
}

dependencies {
    implementation(gradleApi())
}
