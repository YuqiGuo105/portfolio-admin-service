package site.yuqi.media.media;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The exact FFmpeg command lines. Decode, scale, encode, segment: one process does all four for
 * one rendition.
 */
public final class VideoCommands {
    private static final String INPUT_FORMATS = "mov,matroska,webm";

    public record Size(int width, int height) {
    }

    private VideoCommands() {
    }

    /** Output size for a rendition: the short side is the rendition's, the long side keeps the aspect ratio, both even. */
    public static Size outputSize(VideoInfo source, int shortSide) {
        double scale = (double) shortSide / source.shortSide();
        boolean landscape = source.width() >= source.height();
        int width = landscape ? even(source.width() * scale) : shortSide;
        int height = landscape ? shortSide : even(source.height() * scale);
        return new Size(width, height);
    }

    /**
     * One HLS rendition: fMP4 segments of {@link Ladder#SEGMENT_SECONDS} seconds, a keyframe forced
     * every {@link Ladder#KEYFRAME_SECONDS} seconds by timestamp. Because the keyframes are placed
     * by time and not by scene, every rendition cuts at the same instants and a player can switch
     * between them at any segment boundary.
     */
    public static List<String> hlsRendition(String ffmpeg, Path source, Path outDir, VideoInfo info,
                                            Ladder.Rung rung, String preset) {
        Size size = outputSize(info, rung.shortSide());
        List<String> cmd = input(ffmpeg, source);
        cmd.addAll(List.of("-map", "0:v:0"));
        if (info.hasAudio()) cmd.addAll(List.of("-map", "0:a:0"));
        cmd.addAll(List.of("-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1"));
        cmd.addAll(List.of("-vf", filters(info, size)));
        cmd.addAll(List.of(
                "-c:v", "libx264", "-threads", "2", "-preset", preset,
                "-profile:v", rung.h264Profile(), "-level:v", h264Level(size, info.outputFps()),
                "-b:v", rung.videoKbps() + "k", "-maxrate", rung.maxrateKbps() + "k",
                "-bufsize", rung.bufsizeKbps() + "k",
                "-force_key_frames", "expr:gte(t,n_forced*" + Ladder.KEYFRAME_SECONDS + ")",
                "-sc_threshold", "0"));
        if (info.hasAudio()) {
            cmd.addAll(List.of("-c:a", "aac", "-b:a", rung.audioKbps() + "k", "-ac", "2", "-ar", "48000"));
        }
        cmd.addAll(List.of(
                "-max_muxing_queue_size", "1024",
                "-f", "hls", "-hls_time", Integer.toString(Ladder.SEGMENT_SECONDS),
                "-hls_playlist_type", "vod", "-hls_segment_type", "fmp4",
                "-hls_flags", "independent_segments", "-start_number", "1",
                "-hls_fmp4_init_filename", "init.mp4",
                "-hls_segment_filename", outDir.resolve("seg_%05d.m4s").toString(),
                outDir.resolve("index.m3u8").toString()));
        return cmd;
    }

    /** A single progressive MP4 without sound, for short clips that autoplay in a loop. */
    public static List<String> loopMp4(String ffmpeg, Path source, Path target, VideoInfo info, String preset) {
        Size size = outputSize(info, Math.min(720, info.shortSide() - info.shortSide() % 2));
        List<String> cmd = input(ffmpeg, source);
        cmd.addAll(List.of("-map", "0:v:0", "-an", "-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1"));
        cmd.addAll(List.of("-vf", filters(info, size)));
        cmd.addAll(List.of(
                "-c:v", "libx264", "-threads", "2", "-preset", preset, "-profile:v", "main",
                "-level:v", h264Level(size, info.outputFps()),
                "-crf", "23", "-maxrate", "3000k", "-bufsize", "6000k",
                "-movflags", "+faststart", target.toString()));
        return cmd;
    }

    /** One still frame, taken a little way in so it is not the black frame many clips open with. */
    public static List<String> posterFrame(String ffmpeg, Path source, Path target, VideoInfo info) {
        double at = Math.min(3.0, info.durationSeconds() * 0.1);
        Size size = outputSize(info, Math.min(1080, info.shortSide() - info.shortSide() % 2));
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
                "-protocol_whitelist", "file", "-format_whitelist", INPUT_FORMATS,
                "-ss", String.format(Locale.ROOT, "%.3f", at),
                "-i", source.toString()));
        cmd.addAll(List.of("-map", "0:v:0", "-frames:v", "1", "-map_metadata", "-1",
                "-vf", filters(info, size).replace(",format=yuv420p", ""), target.toString()));
        return cmd;
    }

    public static List<String> probe(String ffprobe, Path source) {
        return List.of(ffprobe, "-v", "error", "-protocol_whitelist", "file",
                "-format_whitelist", INPUT_FORMATS,
                "-print_format", "json", "-show_format", "-show_streams", source.toString());
    }

    /** Reject playlist demuxers before they can follow network or local-file references. */
    private static List<String> input(String ffmpeg, Path source) {
        return new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
                "-protocol_whitelist", "file", "-format_whitelist", INPUT_FORMATS, "-i", source.toString()));
    }

    private static String filters(VideoInfo info, Size size) {
        StringBuilder chain = new StringBuilder();
        if (info.fps() > 30.5) chain.append("fps=30,");
        if (info.hdr()) {
            // HDR (PQ/HLG) to SDR BT.709, so the 8-bit H.264 output is not washed out.
            chain.append("zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,")
                    .append("tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,");
        }
        chain.append("scale=").append(size.width()).append(':').append(size.height()).append(":flags=lanczos,");
        chain.append("format=yuv420p");
        return chain.toString();
    }

    /** Smallest H.264 level whose frame-size and macroblock-rate limits the output fits. */
    static String h264Level(Size size, double fps) {
        long macroblocks = (long) Math.ceil(size.width() / 16.0) * (long) Math.ceil(size.height() / 16.0);
        double perSecond = macroblocks * fps;
        Object[][] levels = {
                {"3.0", 1620L, 40500.0}, {"3.1", 3600L, 108000.0}, {"3.2", 5120L, 216000.0},
                {"4.0", 8192L, 245760.0}, {"4.2", 8704L, 522240.0}, {"5.0", 22080L, 589824.0},
                {"5.1", 36864L, 983040.0}};
        for (Object[] level : levels) {
            if (macroblocks <= (Long) level[1] && perSecond <= (Double) level[2]) return (String) level[0];
        }
        return "5.2";
    }

    private static int even(double value) {
        int rounded = (int) Math.round(value);
        return Math.max(2, rounded - rounded % 2);
    }
}
