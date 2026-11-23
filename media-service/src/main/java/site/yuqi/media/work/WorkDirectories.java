package site.yuqi.media.work;

import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import site.yuqi.media.config.MediaProperties;
import site.yuqi.media.model.Job;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Scratch space on local disk. Everything in it can be thrown away; a restart starts clean. */
@Component
public class WorkDirectories {

    private final Path root;

    public WorkDirectories(MediaProperties properties) {
        try {
            String configured = properties.worker().workDir();
            this.root = configured == null || configured.isBlank()
                    ? Files.createTempDirectory("media-service-")
                    : Files.createDirectories(Path.of(configured));
            FileSystemUtils.deleteRecursively(root.resolve("jobs"));
            FileSystemUtils.deleteRecursively(root.resolve("sources"));
            Files.createDirectories(root.resolve("jobs"));
            Files.createDirectories(root.resolve("sources"));
        } catch (IOException unusable) {
            throw new UncheckedIOException("media work directory is not usable", unusable);
        }
    }

    public Path sources() {
        return root.resolve("sources");
    }

    /** A fresh directory for one attempt of one job. */
    public Path forAttempt(Job job) throws IOException {
        Path dir = root.resolve("jobs").resolve(job.id() + "-a" + job.attempt());
        FileSystemUtils.deleteRecursively(dir);
        return Files.createDirectories(dir);
    }

    public void discard(Path attemptDir) {
        try {
            FileSystemUtils.deleteRecursively(attemptDir);
        } catch (IOException leftover) {
            // Scratch space: a leftover directory is cleared on the next start.
        }
    }
}
