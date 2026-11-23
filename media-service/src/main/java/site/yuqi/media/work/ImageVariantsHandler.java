package site.yuqi.media.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import site.yuqi.media.db.JobFlow;
import site.yuqi.media.db.MediaRepository;
import site.yuqi.media.media.ImageTool;
import site.yuqi.media.media.Ladder;
import site.yuqi.media.model.Asset;
import site.yuqi.media.model.Job;

import java.nio.file.Path;

/**
 * An image in every width a page may ask for, as AVIF and WebP, plus one JPEG any browser and any
 * mail client can show. The browser picks the smallest file that is sharp enough for its screen.
 */
@Component
public class ImageVariantsHandler implements JobHandler {

    private final MediaRepository repository;
    private final JobFlow flow;
    private final SourceCache sources;
    private final Outputs outputs;
    private final ImageTool images;
    private final ObjectMapper mapper;

    public ImageVariantsHandler(MediaRepository repository, JobFlow flow, SourceCache sources, Outputs outputs,
                                ImageTool images, ObjectMapper mapper) {
        this.repository = repository;
        this.flow = flow;
        this.sources = sources;
        this.outputs = outputs;
        this.images = images;
        this.mapper = mapper;
    }

    @Override
    public String jobType() {
        return Job.IMAGE_VARIANTS;
    }

    @Override
    public void handle(JobContext context) throws Exception {
        Job job = context.job();
        Asset asset = repository.findAsset(job.assetId()).orElseThrow();
        if (asset.sourceVersion() != job.sourceVersion()) {
            flow.completeOutput(job, context.owner(), "{\"skipped\":\"superseded\"}");
            return;
        }
        JsonNode probe = mapper.readTree(asset.probeJson());
        int sourceWidth = probe.path("width").asInt();

        Path source = sources.acquire(asset);
        try {
            ObjectNode output = mapper.createObjectNode();
            ArrayNode files = output.putArray("files");
            ArrayNode variants = output.putArray("variants");
            for (int width : Ladder.imageWidths(job.profileVersion(), sourceWidth)) {
                for (ImageTool.Format format : new ImageTool.Format[]{ImageTool.Format.AVIF, ImageTool.Format.WEBP}) {
                    if (context.leaseLost()) return;
                    variants.add(make(context, source, width, format, files));
                }
            }
            int fallbackWidth = Math.min(Ladder.IMAGE_FALLBACK_WIDTH, sourceWidth);
            output.set("fallback", make(context, source, fallbackWidth, ImageTool.Format.JPEG, files));
            output.put("width", sourceWidth).put("height", probe.path("height").asInt())
                    .put("alpha", probe.path("alpha").asBoolean());

            if (context.leaseLost()) return;
            flow.completeOutput(job, context.owner(), mapper.writeValueAsString(output));
        } finally {
            sources.release(asset);
        }
    }

    private ObjectNode make(JobContext context, Path source, int width, ImageTool.Format format, ArrayNode files)
            throws Exception {
        Job job = context.job();
        String name = "w" + width + "." + format.extension;
        Path file = context.workDir().resolve(name);
        ImageTool.ImageInfo made = images.variant(source, file, width, format, context.workDir(), context.cancelled());
        long bytes = outputs.put(job.outputPrefix() + "/" + name, file);
        String relative = "a" + job.attempt() + "/" + job.rendition() + "/" + name;
        files.addObject().put("path", relative).put("bytes", bytes);
        return mapper.createObjectNode().put("format", format.extension.equals("jpg") ? "jpeg" : format.extension)
                .put("width", made.width()).put("height", made.height()).put("path", relative).put("bytes", bytes);
    }
}
