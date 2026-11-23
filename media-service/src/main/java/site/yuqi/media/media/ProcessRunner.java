package site.yuqi.media.media;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Runs FFmpeg, ffprobe and libvips as child processes. Arguments are passed as a list, never
 * through a shell, and the child is killed when it runs too long or the job loses its lease.
 */
public final class ProcessRunner {

    public record Result(int exitCode, String stdout, String stderrTail) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /** Thrown when the caller's lease is gone and the work was stopped on purpose. */
    public static final class CancelledException extends IOException {
        public CancelledException(String message) {
            super(message);
        }
    }

    private ProcessRunner() {
    }

    public static Result run(List<String> command, Path workDir, Duration timeout, BooleanSupplier cancelled)
            throws IOException {
        return run(command, workDir, timeout, cancelled, Map.of());
    }

    public static Result run(List<String> command, Path workDir, Duration timeout, BooleanSupplier cancelled,
                             Map<String, String> environment) throws IOException {
        Path stdout = Files.createTempFile(workDir, "out-", ".log");
        Path stderr = Files.createTempFile(workDir, "err-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        builder.environment().putAll(environment);
        Process process;
        try {
            process = builder.start();
        } catch (IOException missing) {
            throw new IOException("cannot start " + command.get(0) + ": " + missing.getMessage(), missing);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    kill(process);
                    throw new CancelledException(command.get(0) + " stopped: the job no longer holds its lease");
                }
                if (System.nanoTime() > deadline) {
                    kill(process);
                    throw new IOException(command.get(0) + " timed out after " + timeout.toSeconds() + " s");
                }
            }
            return new Result(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8), tail(stderr));
        } catch (InterruptedException interrupted) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new CancelledException(command.get(0) + " interrupted");
        } finally {
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** The last few kilobytes are where FFmpeg says what went wrong. */
    private static String tail(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            int size = (int) Math.min(length, 3000);
            byte[] bytes = new byte[size];
            raf.seek(length - size);
            raf.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8).strip();
        }
    }
}
