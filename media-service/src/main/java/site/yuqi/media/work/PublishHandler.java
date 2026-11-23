package site.yuqi.media.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import site.yuqi.media.db.JobFlow;
import site.yuqi.media.db.MediaRepository;
import site.yuqi.media.media.HlsPlaylist;
import site.yuqi.media.model.Asset;
import site.yuqi.media.model.Job;
import site.yuqi.media.store.S3Signer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Step 7: check, then publish.
 *
 * <p>The order is what keeps a visitor from ever loading half a video: every file each accepted
 * job reported is looked up in the bucket, the master playlist is written under a name derived
 * from its content, and only then does one UPDATE move the asset to READY and point it at that
 * playlist. Until that UPDATE commits, nothing public refers to any of these files.
 */
@Component
public class PublishHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(PublishHandler.class);
    /** Second master playlist without anything above 720p: the switch for saving bandwidth. */
    private static final int CAPPED_SHORT_SIDE = 720;

    private final MediaRepository repository;
    private final JobFlow flow;
    private final Outputs outputs;
    private final ObjectMapper mapper;

    public PublishHandler(MediaRepository repository, JobFlow flow, Outputs outputs, ObjectMapper mapper) {
        this.repository = repository;
        this.flow = flow;
        this.outputs = outputs;
        this.mapper = mapper;
    }

    @Override
    public String jobType() {
        return Job.PUBLISH;
    }

    @Override
    public void handle(JobContext context) throws Exception {
        Job job = context.job();
        Optional<Asset> found = repository.findAsset(job.assetId());
        if (found.isEmpty() || found.get().sourceVersion() != job.sourceVersion()
                || !Asset.PROCESSING.equals(found.get().status())) {
            // A newer upload took over while this one was processing. Its pointer is not ours to move.
            flow.publish(job, context.owner(), "{}");
            return;
        }
        Asset asset = found.get();

        List<JsonNode> results = new ArrayList<>();
        for (Job sibling : repository.jobsFor(job.assetId(), job.sourceVersion(), job.profileVersion())) {
            if (!Job.OUTPUT_TYPES.contains(sibling.jobType())) continue;
            if (!Job.SUCCEEDED.equals(sibling.status())) {
                throw new IOException("job " + sibling.rendition() + " is " + sibling.status() + ", cannot publish yet");
            }
            ObjectNode result = (ObjectNode) mapper.readTree(sibling.outputJson());
            result.put("jobType", sibling.jobType());
            results.add(result);
        }
        if (results.isEmpty()) throw new IOException("no outputs to publish");
        // Renditions are listed from the lowest bitrate up, in the manifest as in the master playlist.
        results.sort(Comparator.comparingLong(result -> result.path("peakBitsPerSecond").asLong(0)));

        String base = job.basePath();
        for (JsonNode result : results) verifyStored(base, result);

        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("version", 1).put("kind", asset.kind()).put("basePath", base);
        if (asset.isVideo()) describeVideo(manifest, base, mapper.readTree(asset.probeJson()), results);
        else describeImage(manifest, results);

        if (context.leaseLost()) return;
        boolean published = flow.publish(job, context.owner(), mapper.writeValueAsString(manifest));
        log.info("Asset {} v{} {}", job.assetId(), job.sourceVersion(),
                published ? "published" : "was not published (superseded or lease lost)");
    }

    /** Every file a job says it wrote must be in the bucket, with the size it had on the worker's disk. */
    private void verifyStored(String base, JsonNode result) throws IOException {
        for (JsonNode file : result.path("files")) {
            String key = base + "/" + file.path("path").asText();
            Optional<Long> size = outputs.size(key);
            if (size.isEmpty()) throw new IOException("output " + key + " is missing from the bucket");
            if (size.get() != file.path("bytes").asLong()) {
                throw new IOException("output " + key + " has " + size.get() + " bytes, expected "
                        + file.path("bytes").asLong());
            }
        }
    }

    private void describeVideo(ObjectNode manifest, String base, JsonNode probe, List<JsonNode> results)
            throws IOException {
        List<HlsPlaylist.Variant> all = new ArrayList<>();
        List<HlsPlaylist.Variant> capped = new ArrayList<>();
        ArrayNode renditions = manifest.putArray("renditions");
        for (JsonNode result : results) {
            switch (result.path("jobType").asText()) {
                case Job.VIDEO_RENDITION -> {
                    HlsPlaylist.Variant variant = new HlsPlaylist.Variant(result.path("playlist").asText(),
                            result.path("width").asInt(), result.path("height").asInt(),
                            result.path("peakBitsPerSecond").asLong(), result.path("averageBitsPerSecond").asLong(),
                            result.path("frameRate").asDouble(), result.path("codecs").asText());
                    all.add(variant);
                    if (result.path("shortSide").asInt() <= CAPPED_SHORT_SIDE) capped.add(variant);
                    renditions.addObject().put("name", result.path("rendition").asText())
                            .put("width", variant.width()).put("height", variant.height())
                            .put("bandwidth", variant.peakBitsPerSecond()).put("playlist", variant.playlist());
                }
                case Job.VIDEO_POSTER -> manifest.putObject("poster").put("path", result.path("path").asText())
                        .put("width", result.path("width").asInt()).put("height", result.path("height").asInt());
                case Job.VIDEO_MP4 -> manifest.putObject("mp4").put("path", result.path("path").asText())
                        .put("width", result.path("width").asInt()).put("height", result.path("height").asInt());
                default -> { }
            }
        }
        if (all.isEmpty()) throw new IOException("a video needs at least one rendition");

        manifest.put("hls", writeMaster(base, "master", all));
        if (!capped.isEmpty() && capped.size() < all.size()) {
            manifest.put("hlsCapped", writeMaster(base, "master-" + CAPPED_SHORT_SIDE, capped));
        }
        manifest.put("width", probe.path("width").asInt()).put("height", probe.path("height").asInt())
                .put("durationSeconds", probe.path("durationSeconds").asDouble())
                .put("hasAudio", probe.path("hasAudio").asBoolean());
    }

    /** The name carries a hash of the content, so a master playlist, like every other output, never changes. */
    private String writeMaster(String base, String prefix, List<HlsPlaylist.Variant> variants) throws IOException {
        String content = HlsPlaylist.master(variants);
        String name = prefix + "-" + S3Signer.sha256Hex(content).substring(0, 12) + ".m3u8";
        outputs.put(base + "/" + name, content.getBytes(StandardCharsets.UTF_8));
        return name;
    }

    private void describeImage(ObjectNode manifest, List<JsonNode> results) {
        JsonNode result = results.get(0);
        manifest.put("width", result.path("width").asInt()).put("height", result.path("height").asInt())
                .put("alpha", result.path("alpha").asBoolean());
        manifest.set("variants", result.path("variants"));
        manifest.set("fallback", result.path("fallback"));
    }
}
