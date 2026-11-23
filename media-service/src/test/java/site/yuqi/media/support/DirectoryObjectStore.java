package site.yuqi.media.support;

import site.yuqi.media.store.ObjectStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Object storage for tests: a directory per bucket. */
public final class DirectoryObjectStore implements ObjectStore {

    private final Path root;

    public DirectoryObjectStore(Path root) {
        this.root = root;
    }

    public Path path(String bucket, String key) {
        return root.resolve(bucket).resolve(key);
    }

    @Override
    public Optional<ObjectInfo> head(String bucket, String key) throws IOException {
        Path file = path(bucket, key);
        return Files.isRegularFile(file) ? Optional.of(new ObjectInfo(Files.size(file), "", "")) : Optional.empty();
    }

    @Override
    public void download(String bucket, String key, Path target) throws IOException {
        Files.copy(path(bucket, key), target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void upload(String bucket, String key, Path file, String contentType, String cacheControl) throws IOException {
        Path target = path(bucket, key);
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void upload(String bucket, String key, byte[] body, String contentType, String cacheControl) throws IOException {
        Path target = path(bucket, key);
        Files.createDirectories(target.getParent());
        Files.write(target, body);
    }
}
