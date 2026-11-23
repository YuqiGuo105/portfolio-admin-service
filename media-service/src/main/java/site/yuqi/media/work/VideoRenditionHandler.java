package site.yuqi.media.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import site.yuqi.media.config.MediaProperties;
import site.yuqi.media.db.JobFlow;
import site.yuqi.media.db.MediaRepository;
import site.yuqi.media.media.HlsPlaylist;
import site.yuqi.media.media.Ladder;
import site.yuqi.media.media.Mp4Codec;
import site.yuqi.media.media.ProcessRunner;
import site.yuqi.media.media.VideoCommands;
import site.yuqi.media.media.VideoInfo;
import site.yuqi.media.model.Asset;
import site.yuqi.media.model.Job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Steps 5 and 6 for one rendition: transcode, check the result on local disk, then upload it.
 *
 * <p>Everything goes under this attempt's own prefix, segments first and the playlist last. If an
 * older attempt of the same job is still running somewhere, it writes to a different prefix and
 * cannot disturb this one; only the attempt the database accepts is ever referenced.
 */
@Component
public class VideoRenditionHandler implements JobHandler {

    private final MediaRepository repository;
    private final JobFlow flow;
    private final SourceCache sources;
    private final Outputs outputs;
    private final MediaProperties.Tools tools;
    private final ObjectMapper mapper;

    public VideoRenditionHandler(MediaRepository repository, JobFlow flow, SourceCache sources, Outputs outputs,
                                 MediaProperties properties, ObjectMapper mapper) {
        this.repository = repository;
        this.flow = flow;
        this.sources = sources;
        this.outputs = outputs;
        this.tools = properties.tools();
        this.mapper = mapper;
    }

    @Override
    public String jobType() {
        return Job.VIDEO_RENDITION;
    }

    @Override
    public void handle(JobContext context) throws Exception {
        Job job = context.job();
        Asset asset = repository.findAsset(job.assetId()).orElseThrow();
        if (asset.sourceVersion() != job.sourceVersion()) {
            flow.completeOutput(job, context.owner(), "{\"skipped\":\"superseded\"}");
            return;
        }
        VideoInfo info = mapper.treeToValue(mapper.readTree(asset.probeJson()), VideoInfo.class);
        Ladder.Rung rung = mapper.readValue(job.specJson(), Ladder.Rung.class);

        Path source = sources.acquire(asset);
        try {
            Path out = Files.createDirectories(context.workDir().resolve("out"));
            ProcessRunner.Result result = ProcessRunner.run(
                    VideoCommands.hlsRendition(tools.ffmpeg(), source, out, info, rung, tools.x264Preset()),
                    context.workDir(), Duration.ofSeconds(tools.jobTimeoutSeconds()), context.cancelled());
            if (!result.ok()) {
                throw new IOException("ffmpeg exited with " + result.exitCode() + ": " + result.stderrTail());
            }

            HlsPlaylist playlist = HlsPlaylist.read(out.resolve("index.m3u8"));
            requireComplete(playlist, out, info);

            ObjectNode output = describe(job, out, playlist, info, rung);
            if (context.leaseLost()) return;

            // Segments first, playlist last: a playlist never points at something not yet uploaded.
            String prefix = job.outputPrefix();
            outputs.put(prefix + "/" + playlist.initUri(), out.resolve(playlist.initUri()));
            for (HlsPlaylist.Segment segment : playlist.segments()) {
                if (context.leaseLost()) return;
                outputs.put(prefix + "/" + segment.uri(), out.resolve(segment.uri()));
            }
            outputs.put(prefix + "/index.m3u8", out.resolve("index.m3u8"));

            flow.completeOutput(job, context.owner(), mapper.writeValueAsString(output));
        } finally {
            sources.release(asset);
        }
    }

    /** A rendition is only usable when it is whole: ended playlist, every file present, full length. */
    private static void requireComplete(HlsPlaylist playlist, Path dir, VideoInfo info) throws IOException {
        if (!playlist.ended()) throw new IOException("rendition playlist has no end marker");
        if (playlist.initUri() == null || playlist.segments().isEmpty()) {
            throw new IOException("rendition playlist lists no segments");
        }
        requireFile(dir.resolve(playlist.initUri()));
        for (HlsPlaylist.Segment segment : playlist.segments()) requireFile(dir.resolve(segment.uri()));
        double tolerance = Math.max(1.5, info.durationSeconds() * 0.03);
        if (Math.abs(playlist.totalSeconds() - info.durationSeconds()) > tolerance) {
            throw new IOException(String.format("rendition is %.2f s long but the source is %.2f s",
                    playlist.totalSeconds(), info.durationSeconds()));
        }
    }

    private static void requireFile(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            throw new IOException("expected output " + file.getFileName() + " is missing or empty");
        }
    }

    /** What the publisher needs to list this rendition in the master playlist and to re-check it. */
    private ObjectNode describe(Job job, Path dir, HlsPlaylist playlist, VideoInfo info, Ladder.Rung rung)
            throws IOException {
        VideoCommands.Size size = VideoCommands.outputSize(info, rung.shortSide());
        String relative = "a" + job.attempt() + "/" + job.rendition();
        ObjectNode output = mapper.createObjectNode();
        ArrayNode files = output.putArray("files");
        long total = add(files, relative + "/" + playlist.initUri(), dir.resolve(playlist.initUri()));
        long mediaBytes = 0;
        double peak = 0;
        for (HlsPlaylist.Segment segment : playlist.segments()) {
            long bytes = add(files, relative + "/" + segment.uri(), dir.resolve(segment.uri()));
            mediaBytes += bytes;
            if (segment.seconds() >= 1.0) peak = Math.max(peak, bytes * 8 / segment.seconds());
        }
        total += mediaBytes + add(files, relative + "/index.m3u8", dir.resolve("index.m3u8"));
        long average = Math.round(mediaBytes * 8 / playlist.totalSeconds());

        String codecs = Mp4Codec.h264(dir.resolve(playlist.initUri())) + (info.hasAudio() ? "," + Mp4Codec.aac() : "");
        output.put("rendition", job.rendition())
                .put("playlist", relative + "/index.m3u8")
                .put("width", size.width()).put("height", size.height()).put("shortSide", rung.shortSide())
                .put("peakBitsPerSecond", Math.max(Math.round(peak), average))
                .put("averageBitsPerSecond", average)
                .put("frameRate", info.outputFps())
                .put("codecs", codecs)
                .put("segments", playlist.segments().size())
                .put("durationSeconds", playlist.totalSeconds())
                .put("bytes", total);
        return output;
    }

    private static long add(ArrayNode files, String path, Path file) throws IOException {
        long bytes = Files.size(file);
        files.addObject().put("path", path).put("bytes", bytes);
        return bytes;
    }
}
