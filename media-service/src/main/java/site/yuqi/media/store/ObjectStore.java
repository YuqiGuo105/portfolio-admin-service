package site.yuqi.media.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** The little the Media Service needs from object storage: read an original, write outputs, check they landed. */
public interface ObjectStore {

    record ObjectInfo(long size, String etag, String contentType) {
    }

    /** Empty when the object does not exist. */
    Optional<ObjectInfo> head(String bucket, String key) throws IOException;

    void download(String bucket, String key, Path target) throws IOException;

    void upload(String bucket, String key, Path file, String contentType, String cacheControl) throws IOException;

    void upload(String bucket, String key, byte[] body, String contentType, String cacheControl) throws IOException;
}
