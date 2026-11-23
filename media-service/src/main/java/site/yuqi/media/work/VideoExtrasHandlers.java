package site.yuqi.media.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import site.yuqi.media.config.MediaProperties;
import site.yuqi.media.db.JobFlow;
import site.yuqi.media.db.MediaRepository;
import site.yuqi.media.media.ImageTool;
import site.yuqi.media.media.ProcessRunner;
import site.yuqi.media.media.VideoCommands;
import site.yuqi.media.media.VideoInfo;
import site.yuqi.media.model.Asset;
import site.yuqi.media.model.Job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** The two small video outputs next to the HLS renditions: a poster image, and an MP4 for short loops. */
@Configuration
public class VideoExtrasHandlers {

    @Bean
    public ImageTool imageTool(MediaProperties properties) {
        return new ImageTool(properties.tools().vips(), properties.tools().vipsheader(), Duration.ofSeconds(300));
    }

    /** One still frame as a JPEG no larger than 1280 px, shown before playback starts. */
    @Bean
    public JobHandler videoPosterHandler(MediaRepository repository, JobFlow flow, SourceCache sources,
                                         Outputs outputs, MediaProperties properties, ImageTool images,
                                         ObjectMapper mapper) {
        return new SingleFileHandler(Job.VIDEO_POSTER, repository, flow, sources, mapper) {
            @Override
            ObjectNode produce(JobContext context, Path source, VideoInfo info) throws IOException {
                Path frame = context.workDir().resolve("frame.png");
                run(context, VideoCommands.posterFrame(properties.tools().ffmpeg(), source, frame, info), 300);
                Path poster = context.workDir().resolve("poster.jpg");
                ImageTool.ImageInfo size = images.fit(frame, poster, 1280, ImageTool.Format.JPEG,
                        context.workDir(), context.cancelled());
                return upload(context, outputs, poster, "poster.jpg", size.width(), size.height());
            }
        };
    }

    /** A silent progressive MP4, at most 720p, for clips short enough to autoplay in a loop. */
    @Bean
    public JobHandler videoMp4Handler(MediaRepository repository, JobFlow flow, SourceCache sources,
                                      Outputs outputs, MediaProperties properties, ObjectMapper mapper) {
        return new SingleFileHandler(Job.VIDEO_MP4, repository, flow, sources, mapper) {
            @Override
            ObjectNode produce(JobContext context, Path source, VideoInfo info) throws IOException {
                Path loop = context.workDir().resolve("loop.mp4");
                run(context, VideoCommands.loopMp4(properties.tools().ffmpeg(), source, loop, info,
                        properties.tools().x264Preset()), properties.tools().jobTimeoutSeconds());
                VideoCommands.Size size = VideoCommands.outputSize(info,
                        Math.min(720, info.shortSide() - info.shortSide() % 2));
                return upload(context, outputs, loop, "loop.mp4", size.width(), size.height());
            }
        };
    }

    /** Shared shape: fetch the source, make one file under this attempt's prefix, report it. */
    abstract static class SingleFileHandler implements JobHandler {
        private final String jobType;
        private final MediaRepository repository;
        private final JobFlow flow;
        private final SourceCache sources;
        final ObjectMapper mapper;

        SingleFileHandler(String jobType, MediaRepository repository, JobFlow flow, SourceCache sources,
                          ObjectMapper mapper) {
            this.jobType = jobType;
            this.repository = repository;
            this.flow = flow;
            this.sources = sources;
            this.mapper = mapper;
        }

        abstract ObjectNode produce(JobContext context, Path source, VideoInfo info) throws IOException;

        @Override
        public String jobType() {
            return jobType;
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
            Path source = sources.acquire(asset);
            try {
                ObjectNode output = produce(context, source, info);
                if (context.leaseLost()) return;
                flow.completeOutput(job, context.owner(), mapper.writeValueAsString(output));
            } finally {
                sources.release(asset);
            }
        }

        void run(JobContext context, List<String> command, int timeoutSeconds) throws IOException {
            ProcessRunner.Result result = ProcessRunner.run(command, context.workDir(),
                    Duration.ofSeconds(timeoutSeconds), context.cancelled());
            if (!result.ok()) {
                throw new IOException(command.get(0) + " exited with " + result.exitCode() + ": " + result.stderrTail());
            }
        }

        ObjectNode upload(JobContext context, Outputs outputs, Path file, String name, int width, int height)
                throws IOException {
            if (!Files.isRegularFile(file) || Files.size(file) == 0) {
                throw new IOException("expected output " + name + " is missing or empty");
            }
            Job job = context.job();
            long bytes = outputs.put(job.outputPrefix() + "/" + name, file);
            String relative = "a" + job.attempt() + "/" + job.rendition() + "/" + name;
            ObjectNode output = mapper.createObjectNode();
            output.putArray("files").addObject().put("path", relative).put("bytes", bytes);
            return output.put("path", relative).put("width", width).put("height", height).put("bytes", bytes);
        }
    }
}
