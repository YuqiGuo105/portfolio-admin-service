package site.yuqi.media.media;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * What ffprobe found in an original. Width and height are the displayed size: a phone clip stored
 * sideways with a rotation flag is reported the way a viewer sees it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VideoInfo(
        int width,
        int height,
        double durationSeconds,
        double fps,
        boolean hasAudio,
        String videoCodec,
        String formatName,
        boolean hdr) {

    private static final Set<String> CONTAINERS = Set.of("mov", "mp4", "m4v", "matroska", "webm");
    private static final Set<String> HDR_TRANSFERS = Set.of("smpte2084", "arib-std-b67");

    public int shortSide() {
        return Math.min(width, height);
    }

    /** Frame rate of every output: the source's, capped at 30 so the bitrate ladder holds. */
    public double outputFps() {
        return fps > 30.5 ? 30.0 : fps;
    }

    /**
     * @throws UnsupportedMediaException when the file is not a video this pipeline accepts; retrying
     *                                   the same bytes cannot help
     */
    public static VideoInfo parse(JsonNode probe) {
        String formatName = probe.path("format").path("format_name").asText("");
        boolean knownContainer = false;
        for (String name : formatName.split(",")) knownContainer |= CONTAINERS.contains(name.trim());
        if (!knownContainer) {
            throw new UnsupportedMediaException("container '" + formatName + "' is not accepted (use MP4, MOV, WebM or MKV)");
        }

        JsonNode video = null;
        boolean audio = false;
        for (JsonNode stream : probe.path("streams")) {
            String type = stream.path("codec_type").asText();
            if ("audio".equals(type)) audio = true;
            boolean coverArt = stream.path("disposition").path("attached_pic").asInt(0) == 1;
            if ("video".equals(type) && !coverArt && video == null) video = stream;
        }
        if (video == null) throw new UnsupportedMediaException("the file has no video stream");

        int width = video.path("width").asInt(0);
        int height = video.path("height").asInt(0);
        if (width <= 0 || height <= 0) throw new UnsupportedMediaException("the video stream has no dimensions");
        if (Math.abs(rotation(video)) % 180 == 90) {
            int swap = width;
            width = height;
            height = swap;
        }

        double duration = video.path("duration").asDouble(0);
        if (duration <= 0) duration = probe.path("format").path("duration").asDouble(0);
        if (duration <= 0) throw new UnsupportedMediaException("the video has no duration");

        double fps = rate(video.path("avg_frame_rate").asText("0/0"));
        if (fps <= 0) fps = rate(video.path("r_frame_rate").asText("0/0"));
        if (fps <= 0) fps = 30.0;

        return new VideoInfo(width, height, duration, fps, audio, video.path("codec_name").asText(""),
                formatName, HDR_TRANSFERS.contains(video.path("color_transfer").asText("")));
    }

    private static int rotation(JsonNode video) {
        for (JsonNode side : video.path("side_data_list")) {
            if (side.has("rotation")) return (int) Math.round(side.path("rotation").asDouble(0));
        }
        return video.path("tags").path("rotate").asInt(0);
    }

    private static double rate(String fraction) {
        String[] parts = fraction.split("/");
        try {
            double numerator = Double.parseDouble(parts[0]);
            double denominator = parts.length > 1 ? Double.parseDouble(parts[1]) : 1;
            return denominator == 0 ? 0 : numerator / denominator;
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    /** The input itself is the problem. The job ends at once and the reason is shown to the uploader. */
    public static final class UnsupportedMediaException extends RuntimeException {
        public UnsupportedMediaException(String message) {
            super(message);
        }
    }
}
