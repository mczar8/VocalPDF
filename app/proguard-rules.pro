# R8 / ProGuard rules for VocalPDF

# Conserver les informations de fichiers et de lignes pour la désobscurcissement dans la Google Play Console
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserver les exceptions et annotations
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Apache PDFBox Android (Extraction PDF & Polices)
-keep class com.tom_roush.pdfbox.** { *; }
-keep class org.apache.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.apache.fontbox.**

# Room DB & SQLite
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Jetpack Compose & Coroutines
-dontwarn kotlinx.coroutines.**

# sherpa-onnx : le code natif lit les champs des classes de configuration par leur nom (JNI)
-keep class com.k2fsa.sherpa.onnx.** { *; }

# pdfbox-android : décodeur JPEG2000 optionnel absent
-dontwarn com.gemalto.jp2.**
