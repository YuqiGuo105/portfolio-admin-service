package site.yuqi.media.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the codec string a player needs (for example {@code avc1.4d401f}) out of an fMP4
 * initialisation segment, so the master playlist states what was really encoded.
 */
public final class Mp4Codec {

    private Mp4Codec() {
    }

    /** {@code avc1.PPCCLL}: profile, constraint flags and level, as stored in the avcC box. */
    public static String h264(Path initSegment) throws IOException {
        byte[] bytes = Files.readAllBytes(initSegment);
        for (int i = 0; i + 8 < bytes.length; i++) {
            if (bytes[i] == 'a' && bytes[i + 1] == 'v' && bytes[i + 2] == 'c' && bytes[i + 3] == 'C') {
                return String.format("avc1.%02x%02x%02x", bytes[i + 5] & 0xff, bytes[i + 6] & 0xff, bytes[i + 7] & 0xff);
            }
        }
        throw new IOException("no avcC box in " + initSegment.getFileName() + ": not an H.264 init segment");
    }

    /** FFmpeg's AAC encoder writes AAC-LC. */
    public static String aac() {
        return "mp4a.40.2";
    }
}
