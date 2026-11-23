package site.yuqi.media.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The decisions made without touching a file: which renditions, what size, what the playlists say. */
class MediaMathTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aSourceIsNeverScaledUp() {
        assertThat(names(Ladder.videoRungs("v1", 2160))).containsExactly("360p", "720p", "1080p");
        assertThat(names(Ladder.videoRungs("v1", 1080))).containsExactly("360p", "720p", "1080p");
        assertThat(names(Ladder.videoRungs("v1", 720))).containsExactly("360p", "720p");
        assertThat(names(Ladder.videoRungs("v1", 800))).as("close enough to 720p").containsExactly("360p", "720p");
        assertThat(names(Ladder.videoRungs("v1", 480))).as("keeps its own size on top").containsExactly("360p", "480p");
        assertThat(names(Ladder.videoRungs("v1", 241))).as("smaller than the ladder, odd size").containsExactly("240p");
    }

    @Test
    void bitratesBetweenTheStepsAreInterpolated() {
        Ladder.Rung between = Ladder.videoRungs("v1", 900).get(2);
        assertThat(between.name()).isEqualTo("900p");
        assertThat(between.videoKbps()).isEqualTo(3750);
        assertThat(Ladder.videoRungs("v1", 240).get(0).videoKbps()).isBetween(300, 800);
    }

    @Test
    void imageWidthsStopAtTheSourceWidth() {
        assertThat(Ladder.imageWidths("v1", 4032)).containsExactly(320, 640, 960, 1280, 1920, 2560);
        assertThat(Ladder.imageWidths("v1", 1500)).containsExactly(320, 640, 960, 1280, 1500);
        assertThat(Ladder.imageWidths("v1", 1300)).as("1300 is as good as 1280").containsExactly(320, 640, 960, 1280);
        assertThat(Ladder.imageWidths("v1", 200)).containsExactly(200);
    }

    @Test
    void anUnknownProfileIsRefused() {
        assertThat(Ladder.supports("v1")).isTrue();
        assertThatThrownBy(() -> Ladder.videoRungs("v9", 1080))
                .isInstanceOf(VideoInfo.UnsupportedMediaException.class).hasMessageContaining("v9");
    }

    @Test
    void outputSizesKeepTheShapeAndStayEven() {
        VideoInfo landscape = new VideoInfo(1920, 1080, 10, 30, true, "h264", "mov,mp4", false);
        VideoInfo portrait = new VideoInfo(1080, 1920, 10, 30, true, "h264", "mov,mp4", false);
        VideoInfo wide = new VideoInfo(3440, 1440, 10, 60, false, "h264", "mov,mp4", false);

        assertThat(VideoCommands.outputSize(landscape, 720)).isEqualTo(new VideoCommands.Size(1280, 720));
        assertThat(VideoCommands.outputSize(portrait, 720)).isEqualTo(new VideoCommands.Size(720, 1280));
        assertThat(VideoCommands.outputSize(wide, 360)).isEqualTo(new VideoCommands.Size(860, 360));
        assertThat(VideoCommands.h264Level(new VideoCommands.Size(640, 360), 30)).isEqualTo("3.0");
        assertThat(VideoCommands.h264Level(new VideoCommands.Size(1280, 720), 30)).isEqualTo("3.1");
        assertThat(VideoCommands.h264Level(new VideoCommands.Size(1920, 1080), 30)).isEqualTo("4.0");
        assertThat(VideoCommands.h264Level(new VideoCommands.Size(1720, 720), 30)).as("ultrawide needs more").isEqualTo("3.2");
    }

    @Test
    void everyRenditionCutsAtTheSameInstants() {
        VideoInfo info = new VideoInfo(1920, 1080, 10, 59.94, true, "hevc", "mov,mp4", true);
        List<String> low = VideoCommands.hlsRendition("ffmpeg", Path.of("in"), Path.of("out"), info,
                Ladder.videoRungs("v1", 1080).get(0), "veryfast");
        List<String> high = VideoCommands.hlsRendition("ffmpeg", Path.of("in"), Path.of("out"), info,
                Ladder.videoRungs("v1", 1080).get(2), "veryfast");

        for (List<String> command : List.of(low, high)) {
            assertThat(command).containsSubsequence("-force_key_frames", "expr:gte(t,n_forced*2)");
            assertThat(command).containsSubsequence("-hls_time", "6").containsSubsequence("-hls_segment_type", "fmp4");
            assertThat(command).containsSubsequence("-protocol_whitelist", "file").containsSubsequence("-map_metadata", "-1");
        }
        String filters = high.get(high.indexOf("-vf") + 1);
        assertThat(filters).startsWith("fps=30,zscale=").contains("tonemap").endsWith("scale=1920:1080:flags=lanczos,format=yuv420p");
        assertThat(high).containsSubsequence("-b:v", "5000k").containsSubsequence("-profile:v", "high");
        assertThat(low).containsSubsequence("-b:v", "800k").containsSubsequence("-b:a", "96k");
    }

    @Test
    void aSilentClipGetsNoAudioOptions() {
        VideoInfo silent = new VideoInfo(1280, 720, 5, 30, false, "h264", "mov,mp4", false);
        List<String> command = VideoCommands.hlsRendition("ffmpeg", Path.of("in"), Path.of("out"), silent,
                Ladder.videoRungs("v1", 720).get(1), "veryfast");
        assertThat(command).doesNotContain("-c:a", "0:a:0");
    }

    @Test
    void probeResultsDescribeTheDisplayedPicture() throws Exception {
        String phoneClip = """
                {"streams":[
                  {"codec_type":"audio","codec_name":"aac"},
                  {"codec_type":"video","codec_name":"hevc","width":1920,"height":1080,"avg_frame_rate":"30000/1001",
                   "color_transfer":"arib-std-b67","side_data_list":[{"rotation":-90}],"disposition":{"attached_pic":0}}],
                 "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"12.5"}}""";
        VideoInfo info = VideoInfo.parse(mapper.readTree(phoneClip));

        assertThat(info.width()).isEqualTo(1080);
        assertThat(info.height()).isEqualTo(1920);
        assertThat(info.durationSeconds()).isEqualTo(12.5);
        assertThat(info.fps()).isBetween(29.9, 30.0);
        assertThat(info.hasAudio()).isTrue();
        assertThat(info.hdr()).isTrue();
        assertThat(mapper.treeToValue(mapper.valueToTree(info), VideoInfo.class)).isEqualTo(info);
    }

    @Test
    void filesThatAreNotVideosAreRefusedForGood() throws Exception {
        var playlist = mapper.readTree("{\"streams\":[{\"codec_type\":\"video\",\"width\":1,\"height\":1}],\"format\":{\"format_name\":\"hls\"}}");
        var audioOnly = mapper.readTree("{\"streams\":[{\"codec_type\":\"audio\"}],\"format\":{\"format_name\":\"mov,mp4\",\"duration\":\"3\"}}");
        var coverArt = mapper.readTree("{\"streams\":[{\"codec_type\":\"video\",\"width\":500,\"height\":500,\"disposition\":{\"attached_pic\":1}}],\"format\":{\"format_name\":\"mov,mp4\",\"duration\":\"3\"}}");

        assertThatThrownBy(() -> VideoInfo.parse(playlist)).isInstanceOf(VideoInfo.UnsupportedMediaException.class).hasMessageContaining("hls");
        assertThatThrownBy(() -> VideoInfo.parse(audioOnly)).hasMessageContaining("no video stream");
        assertThatThrownBy(() -> VideoInfo.parse(coverArt)).hasMessageContaining("no video stream");
    }

    @Test
    void playlistsAreReadAndTheMasterListsLowestBitrateFirst() throws Exception {
        Path file = Files.createTempFile("rendition", ".m3u8");
        Files.writeString(file, """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-TARGETDURATION:6
                #EXT-X-MAP:URI="init.mp4"
                #EXTINF:6.000000,
                seg_00001.m4s
                #EXTINF:2.500000,
                seg_00002.m4s
                #EXT-X-ENDLIST
                """);
        HlsPlaylist playlist = HlsPlaylist.read(file);
        assertThat(playlist.initUri()).isEqualTo("init.mp4");
        assertThat(playlist.segments()).extracting(HlsPlaylist.Segment::uri).containsExactly("seg_00001.m4s", "seg_00002.m4s");
        assertThat(playlist.totalSeconds()).isEqualTo(8.5);
        assertThat(playlist.ended()).isTrue();

        Files.writeString(file, "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6.0,\nseg_00001.m4s\n");
        assertThat(HlsPlaylist.read(file).ended()).as("still being written").isFalse();

        String master = HlsPlaylist.master(List.of(
                new HlsPlaylist.Variant("a1/720p/index.m3u8", 1280, 720, 2_650_000, 2_600_000, 30, "avc1.4d401f,mp4a.40.2"),
                new HlsPlaylist.Variant("a2/360p/index.m3u8", 640, 360, 900_000, 880_000, 30, "avc1.4d401e,mp4a.40.2")));
        assertThat(master).isEqualTo("""
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-INDEPENDENT-SEGMENTS
                #EXT-X-STREAM-INF:BANDWIDTH=900000,AVERAGE-BANDWIDTH=880000,RESOLUTION=640x360,FRAME-RATE=30.000,CODECS="avc1.4d401e,mp4a.40.2"
                a2/360p/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2650000,AVERAGE-BANDWIDTH=2600000,RESOLUTION=1280x720,FRAME-RATE=30.000,CODECS="avc1.4d401f,mp4a.40.2"
                a1/720p/index.m3u8
                """);
    }

    private static List<String> names(List<Ladder.Rung> rungs) {
        return rungs.stream().map(Ladder.Rung::name).toList();
    }
}
