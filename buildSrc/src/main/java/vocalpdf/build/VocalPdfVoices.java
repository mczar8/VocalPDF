package vocalpdf.build;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Voix intégrées de VocalPDF : téléchargement automatique, une seule fois.
 *
 * <p>Les voix (≈ 60 Mo chacune) et le moteur sherpa-onnx (≈ 48 Mo) sont trop gros pour GitHub.
 * Ils sont récupérés depuis leurs sources officielles puis réutilisés : aucune connexion n'est
 * nécessaire ensuite, et l'application reste 100 % hors ligne.
 *
 * <p>Utilisation dans app/build.gradle.kts : {@code vocalpdf.build.VocalPdfVoices.setup(project)}.
 * Tâche manuelle : {@code ./gradlew :app:prepareVoices}
 */
public final class VocalPdfVoices {

    public static final String SHERPA_ONNX_VERSION = "1.13.8";
    static final String RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download";

    /** Voix intégrées : nom du modèle Piper (doit correspondre à tts/EmbeddedVoices.kt). */
    static final List<String> MODELS = List.of("fr_FR-siwis-medium", "en_US-ljspeech-medium");

    /** Seuls les dictionnaires de prononciation utiles sont gardés (19 Mo -> 2 Mo). */
    static final List<String> KEPT_DICTIONARIES = List.of("fr_dict", "en_dict");

    private VocalPdfVoices() {
    }

    /** Fichier du moteur, relatif au module app (le même que dans dependencies { … }). */
    public static String engineAarPath() {
        return "libs/sherpa-onnx-" + SHERPA_ONNX_VERSION + ".aar";
    }

    public static void setup(Project project) {
        File aar = project.file(engineAarPath());

        // 1. Le moteur doit exister DÈS l'ouverture du projet : Android Studio lit les bibliothèques
        //    pendant la synchronisation, avant toute tâche (sinon « Null extracted folder for artifact »).
        ensureEngine(project, aar);

        // 2. Les voix sont nécessaires seulement à la compilation : tâche lancée avant chaque build.
        TaskProvider<PrepareVoicesTask> prepare = project.getTasks().register("prepareVoices", PrepareVoicesTask.class, task -> {
            task.setGroup("vocalpdf");
            task.setDescription("Télécharge les voix intégrées et le moteur sherpa-onnx (une seule fois).");
            task.getAarUrl().set(engineUrl());
            task.getModelsBaseUrl().set(RELEASES + "/tts-models");
            task.getModels().set(MODELS);
            task.getKeptDictionaries().set(KEPT_DICTIONARIES);
            task.getAarFile().set(aar);
            task.getAssetsDir().set(project.getLayout().getProjectDirectory().dir("src/main/assets"));
            task.getDownloadDir().set(project.getLayout().getProjectDirectory().dir(".voice-downloads"));
        });
        project.getTasks().matching(t -> t.getName().equals("preBuild")).configureEach(t -> t.dependsOn(prepare));
    }

    static String engineUrl() {
        return RELEASES + "/v" + SHERPA_ONNX_VERSION + "/sherpa-onnx-" + SHERPA_ONNX_VERSION + ".aar";
    }

    private static void ensureEngine(Project project, File aar) {
        if (aar.exists() && aar.length() > 0) return;
        project.getLogger().lifecycle("VocalPDF : téléchargement du moteur vocal sherpa-onnx (une seule fois, ≈ 48 Mo)…");
        try {
            download(engineUrl(), aar);
        } catch (IOException e) {
            throw new GradleException("VocalPDF : impossible de télécharger le moteur vocal depuis " + engineUrl()
                + ". Vérifiez la connexion Internet puis relancez la synchronisation (File › Sync Project with Gradle Files).", e);
        }
    }

    /** Téléchargement vers un fichier « .part », renommé seulement une fois complet. */
    static void download(String url, File target) throws IOException {
        if (target.exists() && target.length() > 0) return;
        File parent = target.getAbsoluteFile().getParentFile();
        Files.createDirectories(parent.toPath());
        File partial = new File(parent, target.getName() + ".part");
        try (InputStream input = URI.create(url).toURL().openStream()) {
            Files.copy(input, partial.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(partial.toPath());
            throw e;
        }
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
