package site.yuqi.media.work;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import site.yuqi.media.config.MediaProperties;
import site.yuqi.media.model.Asset;
import site.yuqi.media.store.ObjectStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Local copies of originals. One source version is turned into several outputs by several jobs;
 * they share one download. A copy is safe to share because a source version never changes.
 */
@Component
public class SourceCache {

    private static final Logger log = LoggerFactory.getLogger(SourceCache.class);

    private static final class Entry {
        final Path file;
        int users;
        long lastUsed;
        long bytes = -1;

        Entry(Path file) {
            this.file = file;
        }
    }

    private final ObjectStore store;
    private final String bucket;
    private final Path directory;
    private final long budgetBytes;
    private final Map<String, Entry> entries = new HashMap<>();

    public SourceCache(ObjectStore store, MediaProperties properties, WorkDirectories directories) {
        this.store = store;
        this.bucket = properties.storage().originalsBucket();
        this.directory = directories.sources();
        this.budgetBytes = properties.worker().sourceCacheBytes();
    }

    /** A local file holding the asset's bound source. Pair every call with {@link #release}. */
    public Path acquire(Asset asset) throws IOException {
        String key = asset.id() + "-v" + asset.sourceVersion();
        Entry entry;
        synchronized (entries) {
            entry = entries.computeIfAbsent(key, k -> new Entry(directory.resolve(k)));
            entry.users++;
            entry.lastUsed = System.nanoTime();
        }
        try {
            synchronized (entry) {
                if (entry.bytes < 0) {
                    store.download(bucket, asset.sourceKey(), entry.file);
                    entry.bytes = Files.size(entry.file);
                    log.info("Fetched original {} ({} bytes)", asset.sourceKey(), entry.bytes);
                }
            }
            return entry.file;
        } catch (IOException | RuntimeException failed) {
            release(asset);
            throw failed;
        }
    }

    public void release(Asset asset) {
        synchronized (entries) {
            Entry entry = entries.get(asset.id() + "-v" + asset.sourceVersion());
            if (entry != null && entry.users > 0) entry.users--;
            evict();
        }
    }

    /** Drops idle copies, oldest first, until the cache fits its budget again. */
    private void evict() {
        long total = entries.values().stream().mapToLong(e -> Math.max(0, e.bytes)).sum();
        if (total <= budgetBytes) return;
        var idle = entries.entrySet().stream()
                .filter(e -> e.getValue().users == 0)
                .sorted(Comparator.comparingLong(e -> e.getValue().lastUsed))
                .toList();
        for (var candidate : idle) {
            if (total <= budgetBytes) break;
            try {
                Files.deleteIfExists(candidate.getValue().file);
                total -= Math.max(0, candidate.getValue().bytes);
                entries.remove(candidate.getKey());
            } catch (IOException stuck) {
                log.warn("Could not evict cached original {}: {}", candidate.getKey(), stuck.getMessage());
            }
        }
    }
}
