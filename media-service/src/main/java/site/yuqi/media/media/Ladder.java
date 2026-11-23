package site.yuqi.media.media;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * The encoding profile: which renditions exist and how each is encoded.
 *
 * <p>A profile is immutable once released. Changing a number here means adding a new profile
 * version, so that outputs made with the old numbers stay where they are and stay valid.
 */
public final class Ladder {

    /** One rendition. {@code shortSide} is the smaller of width and height, so portrait clips fit too. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rung(String name, int shortSide, int videoKbps, int audioKbps, String h264Profile) {
        public int maxrateKbps() {
            return Math.round(videoKbps * 1.07f);
        }

        public int bufsizeKbps() {
            return videoKbps * 2;
        }
    }

    /** Segment length and keyframe spacing are the same for every rendition, so players can switch at any segment. */
    public static final int SEGMENT_SECONDS = 6;
    public static final int KEYFRAME_SECONDS = 2;

    public static final int[] IMAGE_WIDTHS = {320, 640, 960, 1280, 1920, 2560};
    public static final int IMAGE_FALLBACK_WIDTH = 1280;

    private static final List<Rung> V1 = List.of(
            new Rung("360p", 360, 800, 96, "main"),
            new Rung("720p", 720, 2500, 128, "main"),
            new Rung("1080p", 1080, 5000, 128, "high"));

    private Ladder() {
    }

    public static boolean supports(String profileVersion) {
        return "v1".equals(profileVersion);
    }

    /**
     * The renditions for one source. Nothing is ever scaled up: a 720p source gets 360p and 720p,
     * and a source that sits between two steps keeps its own size as the top rendition.
     */
    public static List<Rung> videoRungs(String profileVersion, int sourceShortSide) {
        requireSupported(profileVersion);
        int source = sourceShortSide - sourceShortSide % 2;
        List<Rung> rungs = new ArrayList<>();
        for (Rung rung : V1) {
            if (rung.shortSide() <= source) rungs.add(rung);
        }
        int top = rungs.isEmpty() ? 0 : rungs.get(rungs.size() - 1).shortSide();
        boolean belowLadderTop = source < V1.get(V1.size() - 1).shortSide();
        if (rungs.isEmpty() || (belowLadderTop && source >= top * 1.2)) {
            rungs.add(new Rung(source + "p", source, interpolateKbps(source), source < 480 ? 96 : 128, "main"));
        }
        return rungs;
    }

    /** Widths to produce for an image, never wider than the original. */
    public static List<Integer> imageWidths(String profileVersion, int sourceWidth) {
        requireSupported(profileVersion);
        List<Integer> widths = new ArrayList<>();
        for (int width : IMAGE_WIDTHS) {
            if (width <= sourceWidth) widths.add(width);
        }
        int largest = widths.isEmpty() ? 0 : widths.get(widths.size() - 1);
        int cap = IMAGE_WIDTHS[IMAGE_WIDTHS.length - 1];
        if (widths.isEmpty() || (sourceWidth < cap && sourceWidth >= largest * 1.1)) widths.add(sourceWidth);
        return widths;
    }

    private static int interpolateKbps(int shortSide) {
        Rung low = V1.get(0);
        if (shortSide <= low.shortSide()) {
            double area = (double) shortSide * shortSide / (low.shortSide() * low.shortSide());
            return Math.max(300, (int) Math.round(low.videoKbps() * area));
        }
        for (int i = 1; i < V1.size(); i++) {
            Rung high = V1.get(i);
            if (shortSide <= high.shortSide()) {
                double t = (double) (shortSide - low.shortSide()) / (high.shortSide() - low.shortSide());
                return (int) Math.round(low.videoKbps() + t * (high.videoKbps() - low.videoKbps()));
            }
            low = high;
        }
        return low.videoKbps();
    }

    private static void requireSupported(String profileVersion) {
        if (!supports(profileVersion)) {
            throw new VideoInfo.UnsupportedMediaException("encoding profile '" + profileVersion + "' is not known to this service");
        }
    }
}
