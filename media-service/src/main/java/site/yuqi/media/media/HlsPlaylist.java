package site.yuqi.media.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads a rendition playlist FFmpeg wrote, and writes the master playlist that lists the renditions. */
public final class HlsPlaylist {

    public record Segment(String uri, double seconds) {
    }

    /** A rendition as the master playlist describes it. Paths are relative to the master. */
    public record Variant(String playlist, int width, int height, long peakBitsPerSecond,
                          long averageBitsPerSecond, double frameRate, String codecs) {
    }

    private final String initUri;
    private final List<Segment> segments;
    private final boolean ended;

    private HlsPlaylist(String initUri, List<Segment> segments, boolean ended) {
        this.initUri = initUri;
        this.segments = segments;
        this.ended = ended;
    }

    public static HlsPlaylist read(Path file) throws IOException {
        String initUri = null;
        List<Segment> segments = new ArrayList<>();
        boolean ended = false;
        double pending = -1;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.startsWith("#EXT-X-MAP:")) {
                int start = line.indexOf("URI=\"");
                if (start >= 0) initUri = line.substring(start + 5, line.indexOf('"', start + 5));
            } else if (line.startsWith("#EXTINF:")) {
                String value = line.substring(8);
                int comma = value.indexOf(',');
                pending = Double.parseDouble(comma >= 0 ? value.substring(0, comma) : value);
            } else if (line.equals("#EXT-X-ENDLIST")) {
                ended = true;
            } else if (!line.isEmpty() && !line.startsWith("#") && pending >= 0) {
                segments.add(new Segment(line, pending));
                pending = -1;
            }
        }
        return new HlsPlaylist(initUri, segments, ended);
    }

    public String initUri() {
        return initUri;
    }

    public List<Segment> segments() {
        return segments;
    }

    /** A VOD playlist is only complete once FFmpeg has written the end marker. */
    public boolean ended() {
        return ended;
    }

    public double totalSeconds() {
        return segments.stream().mapToDouble(Segment::seconds).sum();
    }

    /** The master playlist: one entry per rendition, lowest bitrate first so playback starts quickly. */
    public static String master(List<Variant> variants) {
        StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        variants.stream()
                .sorted((a, b) -> Long.compare(a.peakBitsPerSecond(), b.peakBitsPerSecond()))
                .forEach(variant -> out.append(String.format(Locale.ROOT,
                        "#EXT-X-STREAM-INF:BANDWIDTH=%d,AVERAGE-BANDWIDTH=%d,RESOLUTION=%dx%d,FRAME-RATE=%.3f,CODECS=\"%s\"%n%s%n",
                        variant.peakBitsPerSecond(), variant.averageBitsPerSecond(), variant.width(),
                        variant.height(), variant.frameRate(), variant.codecs(), variant.playlist())
                        .replace("\r\n", "\n")));
        return out.toString();
    }
}
