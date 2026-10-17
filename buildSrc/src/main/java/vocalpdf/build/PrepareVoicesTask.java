package vocalpdf.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.file.RelativePath;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Télécharge le moteur et les voix s'ils manquent ; ne fait rien sinon (cas habituel). */
@DisableCachingByDefault(because = "Fichiers téléchargés directement dans les sources du projet")
public abstract class PrepareVoicesTask extends DefaultTask {

    @Input public abstract Property<String> getAarUrl();
    @Input public abstract Property<String> getModelsBaseUrl();
    @Input public abstract ListProperty<String> getModels();
    @Input public abstract ListProperty<String> getKeptDictionaries();

    // Fichiers produits dans les sources du projet : vérifiés à la main (voir shouldRun)
    @Internal public abstract RegularFileProperty getAarFile();
    @Internal public abstract DirectoryProperty getAssetsDir();
    @Internal public abstract DirectoryProperty getDownloadDir();

    @Inject protected abstract FileSystemOperations getFs();
    @Inject protected abstract ArchiveOperations getArchives();

    /** Vrai s'il manque le moteur, une voix ou les données de prononciation. */
    public boolean shouldRun() {
        File assets = getAssetsDir().get().getAsFile();
        if (!getAarFile().get().getAsFile().exists()) return true;
        for (String model : getModels().get()) {
            if (!new File(assets, "voices/" + model + "/" + model + ".onnx").exists()) return true;
        }
        return !new File(assets, "espeak-ng-data/phontab").exists();
    }

    @TaskAction
    public void prepare() {
        if (!shouldRun()) {
            getLogger().info("VocalPDF : voix et moteur déjà présents.");
            return;
        }
        try {
            // 1. Moteur sherpa-onnx
            download(getAarUrl().get(), getAarFile().get().getAsFile());

            File assets = getAssetsDir().get().getAsFile();
            File downloads = getDownloadDir().get().getAsFile();
            Set<String> dictionaries = new HashSet<>(getKeptDictionaries().get());
            List<String> models = getModels().get();

            // 2. Modèles de voix (+ données de prononciation, prises dans la première archive)
            for (int index = 0; index < models.size(); index++) {
                String model = models.get(index);
                File voiceDir = new File(assets, "voices/" + model);
                File espeakDir = new File(assets, "espeak-ng-data");
                boolean needModel = !new File(voiceDir, model + ".onnx").exists();
                boolean needEspeak = index == 0 && !new File(espeakDir, "phontab").exists();
                if (!needModel && !needEspeak) continue;

                String root = "vits-piper-" + model;
                File archive = new File(downloads, root + ".tar.bz2");
                download(getModelsBaseUrl().get() + "/" + root + ".tar.bz2", archive);

                if (needModel) {
                    getFs().copy(spec -> {
                        spec.from(getArchives().tarTree(archive), from -> {
                            from.include(root + "/" + model + ".onnx", root + "/tokens.txt", root + "/MODEL_CARD");
                            from.eachFile(file -> file.setRelativePath(new RelativePath(true, file.getName())));
                        });
                        spec.setIncludeEmptyDirs(false);
                        spec.into(voiceDir);
                    });
                }
                if (needEspeak) {
                    getFs().copy(spec -> {
                        spec.from(getArchives().tarTree(archive), from -> {
                            from.include(root + "/espeak-ng-data/**");
                            from.exclude(element -> element.getName().endsWith("_dict") && !dictionaries.contains(element.getName()));
                            from.eachFile(file -> {
                                String[] segments = file.getRelativePath().getSegments();
                                file.setRelativePath(new RelativePath(!file.isDirectory(), Arrays.copyOfRange(segments, 2, segments.length)));
                            });
                        });
                        spec.setIncludeEmptyDirs(false);
                        spec.into(espeakDir);
                    });
                }
                if (!archive.delete()) archive.deleteOnExit(); // archive inutile une fois extraite (≈ 67 Mo)
            }
        } catch (IOException e) {
            throw new GradleException("VocalPDF : téléchargement des voix impossible. Vérifiez la connexion Internet.", e);
        }
    }

    private void download(String url, File target) throws IOException {
        if (target.exists() && target.length() > 0) return;
        getLogger().lifecycle("VocalPDF : téléchargement de " + url);
        VocalPdfVoices.download(url, target);
    }
}
