package site.yuqi.media.work;

import org.springframework.stereotype.Component;
import site.yuqi.media.config.MediaProperties;
import site.yuqi.media.store.ObjectStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Writes results to the public bucket. Every key contains the source version, the profile and the
 * attempt, so a key is written once and never changes: caches may keep it for a year.
 */
@Component
public class Outputs {

    public static final String IMMUTABLE = "public, max-age=31536000, immutable";

    private final ObjectStore store;
    private final String bucket;

    public Outputs(ObjectStore store, MediaProperties properties) {
        this.store = store;
        this.bucket = properties.storage().publicBucket();
    }

    /** Uploads {@code file} and returns its size in bytes. */
    public long put(String key, Path file) throws IOException {
        store.upload(bucket, key, file, contentType(key), IMMUTABLE);
        return Files.size(file);
    }

    public void put(String key, byte[] body) throws IOException {
        store.upload(bucket, key, body, contentType(key), IMMUTABLE);
    }

    /** Size of an object that must already be there, or empty when it is missing. */
    public Optional<Long> size(String key) throws IOException {
        return store.head(bucket, key).map(ObjectStore.ObjectInfo::size);
    }

    static String contentType(String key) {
        String name = key.substring(key.lastIndexOf('/') + 1).toLowerCase();
        if (name.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (name.endsWith(".m4s")) return "video/iso.segment";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".avif")) return "image/avif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }
}
