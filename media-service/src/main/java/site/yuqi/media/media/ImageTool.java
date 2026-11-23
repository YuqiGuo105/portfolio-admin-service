package site.yuqi.media.media;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * libvips, through its command-line tools. It resizes without loading the whole image into memory,
 * applies the camera's rotation flag, converts to sRGB and drops metadata such as GPS position.
 */
public final class ImageTool {

    /** Size as displayed, that is, after the EXIF orientation has been applied. */
    public record ImageInfo(int width, int height, boolean alpha) {
        public long pixels() {
            return (long) width * height;
        }
    }

    public enum Format {
        AVIF("avif", "image/avif", "[Q=55,effort=4,strip]"),
        WEBP("webp", "image/webp", "[Q=80,effort=4,strip]"),
        JPEG("jpg", "image/jpeg", "[Q=82,optimize-coding,interlace,strip,background=255 255 255]");

        public final String extension;
        public final String contentType;
        final String saveOptions;

        Format(String extension, String contentType, String saveOptions) {
            this.extension = extension;
            this.contentType = contentType;
            this.saveOptions = saveOptions;
        }
    }

    /** Loaders libvips itself marks as unsafe for untrusted input stay off. */
    private static final Map<String, String> ENVIRONMENT = Map.of("VIPS_BLOCK_UNTRUSTED", "1");

    private final String vips;
    private final String vipsheader;
    private final Duration timeout;

    public ImageTool(String vips, String vipsheader, Duration timeout) {
        this.vips = vips;
        this.vipsheader = vipsheader;
        this.timeout = timeout;
    }

    public ImageInfo info(Path image, Path workDir, BooleanSupplier cancelled) throws IOException {
        ProcessRunner.Result result = ProcessRunner.run(List.of(vipsheader, "-a", image.toString()),
                workDir, timeout, cancelled, ENVIRONMENT);
        if (!result.ok()) {
            throw new VideoInfo.UnsupportedMediaException("not an image this service can read: " + firstLine(result.stderrTail()));
        }
        int width = 0, height = 0, bands = 3, orientation = 1;
        for (String line : result.stdout().split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            switch (name) {
                case "width" -> width = parse(value);
                case "height" -> height = parse(value);
                case "bands" -> bands = parse(value);
                case "orientation" -> orientation = parse(value);
                default -> { }
            }
        }
        if (width <= 0 || height <= 0) throw new VideoInfo.UnsupportedMediaException("the image has no dimensions");
        boolean sideways = orientation >= 5 && orientation <= 8;
        return new ImageInfo(sideways ? height : width, sideways ? width : height, bands == 2 || bands == 4);
    }

    /** Writes one variant {@code width} pixels wide (never wider than the source) and returns its size. */
    public ImageInfo variant(Path source, Path target, int width, Format format, Path workDir,
                             BooleanSupplier cancelled) throws IOException {
        return resize(source, target, width, 10_000_000, format, workDir, cancelled);
    }

    /** Writes a variant that fits inside a {@code box} by {@code box} square. */
    public ImageInfo fit(Path source, Path target, int box, Format format, Path workDir,
                         BooleanSupplier cancelled) throws IOException {
        return resize(source, target, box, box, format, workDir, cancelled);
    }

    private ImageInfo resize(Path source, Path target, int width, int height, Format format, Path workDir,
                             BooleanSupplier cancelled) throws IOException {
        ProcessRunner.Result result = ProcessRunner.run(List.of(
                vips, "thumbnail", source.toString(), target + format.saveOptions, Integer.toString(width),
                "--height", Integer.toString(height), "--size", "down", "--export-profile", "srgb"),
                workDir, timeout, cancelled, ENVIRONMENT);
        if (!result.ok()) {
            throw new IOException("vips could not write " + target.getFileName() + ": " + firstLine(result.stderrTail()));
        }
        return info(target, workDir, cancelled);
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value.split("\\s+")[0]);
        } catch (RuntimeException notANumber) {
            return 0;
        }
    }

    private static String firstLine(String text) {
        String line = text == null ? "" : text.strip().lines().findFirst().orElse("");
        return line.length() > 300 ? line.substring(0, 300) : line;
    }
}
