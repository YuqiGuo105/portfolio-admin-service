package site.yuqi.media.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import site.yuqi.media.config.MediaProperties;
import site.yuqi.media.db.JobFlow;
import site.yuqi.media.db.MediaRepository;
import site.yuqi.media.media.ImageTool;
import site.yuqi.media.media.Ladder;
import site.yuqi.media.media.ProcessRunner;
import site.yuqi.media.media.VideoCommands;
import site.yuqi.media.media.VideoInfo;
import site.yuqi.media.model.Asset;
import site.yuqi.media.model.Job;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 4, the scheduler's part: look at the original and decide what has to be made.
 *
 * <p>For a video that is one job per rendition the source is large enough for, plus a poster. The
 * jobs and the PLAN result are written in one transaction, so a crash here leaves either nothing
 * (and the PLAN is retried) or the complete set.
 */
@Component
public class PlanHandler implements JobHandler {

    private final MediaRepository repository;
    private final JobFlow flow;
    private final SourceCache sources;
    private final MediaProperties properties;
    private final ImageTool images;
    private final ObjectMapper mapper;

    public PlanHandler(MediaRepository repository, JobFlow flow, SourceCache sources,
                       MediaProperties properties, ImageTool images, ObjectMapper mapper) {
        this.repository = repository;
        this.flow = flow;
        this.sources = sources;
        this.properties = properties;
        this.images = images;
        this.mapper = mapper;
    }

    @Override
    public String jobType() {
        return Job.PLAN;
    }

    @Override
    public void handle(JobContext context) throws Exception {
        Job job = context.job();
        Asset asset = repository.findAsset(job.assetId()).orElse(null);
        if (asset == null || asset.sourceVersion() != job.sourceVersion()
                || !Asset.PROCESSING.equals(asset.status())) {
            flow.completePlan(job, context.owner(), "{}", List.of(), "{\"skipped\":\"superseded\"}");
            return;
        }
        if (!Ladder.supports(job.profileVersion())) {
            throw new VideoInfo.UnsupportedMediaException(
                    "encoding profile '" + job.profileVersion() + "' is not known to this service");
        }

        Path source = sources.acquire(asset);
        try {
            ObjectNode probe = mapper.createObjectNode();
            List<JobFlow.NewJob> jobs = asset.isVideo()
                    ? planVideo(context, source, probe)
                    : planImage(context, source, probe);
            ObjectNode output = mapper.createObjectNode().put("jobs", jobs.size());
            flow.completePlan(job, context.owner(), mapper.writeValueAsString(probe), jobs,
                    mapper.writeValueAsString(output));
        } finally {
            sources.release(asset);
        }
    }

    private List<JobFlow.NewJob> planVideo(JobContext context, Path source, ObjectNode probe) throws Exception {
        MediaProperties.Tools tools = properties.tools();
        ProcessRunner.Result result = ProcessRunner.run(VideoCommands.probe(tools.ffprobe(), source),
                context.workDir(), Duration.ofSeconds(120), context.cancelled());
        if (!result.ok()) {
            // FFmpeg prefixes its message with the local path; the uploader only needs the reason.
            throw new VideoInfo.UnsupportedMediaException("not a readable video: "
                    + lastLine(result.stderrTail()).replace(source + ": ", ""));
        }
        VideoInfo info = VideoInfo.parse(mapper.readTree(result.stdout()));

        MediaProperties.Limits limits = properties.limits();
        if (info.durationSeconds() > limits.maxVideoSeconds()) {
            throw new VideoInfo.UnsupportedMediaException("the video is " + Math.round(info.durationSeconds())
                    + " s long; the limit is " + limits.maxVideoSeconds() + " s");
        }
        if (info.shortSide() > limits.maxVideoShortSide()) {
            throw new VideoInfo.UnsupportedMediaException("the video is " + info.width() + "x" + info.height()
                    + "; that is larger than this service accepts");
        }
        probe.put("kind", Asset.VIDEO).setAll((ObjectNode) mapper.valueToTree(info));

        List<JobFlow.NewJob> jobs = new ArrayList<>();
        for (Ladder.Rung rung : Ladder.videoRungs(context.job().profileVersion(), info.shortSide())) {
            jobs.add(new JobFlow.NewJob(Job.VIDEO_RENDITION, rung.name(), mapper.writeValueAsString(rung)));
        }
        jobs.add(new JobFlow.NewJob(Job.VIDEO_POSTER, "poster", "{}"));
        if (info.durationSeconds() <= limits.loopMaxSeconds()) {
            jobs.add(new JobFlow.NewJob(Job.VIDEO_MP4, "mp4", "{}"));
        }
        return jobs;
    }

    private List<JobFlow.NewJob> planImage(JobContext context, Path source, ObjectNode probe) throws Exception {
        ImageTool.ImageInfo info = images.info(source, context.workDir(), context.cancelled());
        if (info.pixels() > properties.limits().maxImagePixels()) {
            throw new VideoInfo.UnsupportedMediaException("the image is " + info.width() + "x" + info.height()
                    + "; that is larger than this service accepts");
        }
        probe.put("kind", Asset.IMAGE).put("width", info.width()).put("height", info.height())
                .put("alpha", info.alpha());
        return List.of(new JobFlow.NewJob(Job.IMAGE_VARIANTS, "image", "{}"));
    }

    private static String lastLine(String text) {
        String[] lines = text == null ? new String[0] : text.strip().split("\n");
        String line = lines.length == 0 ? "" : lines[lines.length - 1].strip();
        return line.length() > 300 ? line.substring(0, 300) : line;
    }
}
