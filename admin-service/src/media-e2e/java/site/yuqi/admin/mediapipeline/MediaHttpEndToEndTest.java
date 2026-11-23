package site.yuqi.admin.mediapipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import site.yuqi.admin.security.AdminAuthFilter;
import site.yuqi.admin.security.InternalTokenFilter;
import site.yuqi.admin.security.SupabaseTokenVerifier;
import site.yuqi.admin.service.AdminUserService;
import site.yuqi.admin.events.CloudRunIdentityTokenProvider;
import site.yuqi.media.config.MediaProperties;
import site.yuqi.media.db.*;
import site.yuqi.media.intake.*;
import site.yuqi.media.store.S3ObjectStore;
import site.yuqi.media.web.InternalController;
import site.yuqi.media.work.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Opt-in only: real HTTP, S3 protocol, Kafka, PostgreSQL, FFmpeg, libvips and frontend upload code. */
@EnabledIfEnvironmentVariable(named=MediaTestSchema.ENV,matches=MediaTestSchema.LOCAL_ONLY)
@EnabledIfEnvironmentVariable(named="MEDIA_E2E_FRONTEND",matches=".+")
class MediaHttpEndToEndTest {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class,HibernateJpaAutoConfiguration.class,
            KafkaAutoConfiguration.class,FlywayAutoConfiguration.class})
    static class WebOnly { }

    @Test @Timeout(1800)
    void uploadThroughKafkaAndRecoverWakeThenPublishPlayableFiles() throws Exception {
        Path frontend=Path.of(System.getenv("MEDIA_E2E_FRONTEND")).toRealPath();
        Path output=Path.of(System.getenv("MEDIA_E2E_OUTPUT")).toAbsolutePath();
        Files.createDirectories(output);
        String catalog=System.getenv("MEDIA_E2E_CATALOG");
        ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
        int assetCount=catalog == null ? 2 : mapper.readTree(Files.readString(Path.of(catalog))).size();
        assertThat(assetCount).isBetween(1,20);
        if(catalog == null) {
            command(output,"ffmpeg","-hide_banner","-loglevel","error","-y","-f","lavfi","-i",
                "testsrc2=size=1920x1080:rate=30:duration=8","-f","lavfi","-i","sine=frequency=440:duration=8",
                "-c:v","libx264","-preset","ultrafast","-crf","18","-threads","2","-pix_fmt","yuv420p",
                "-c:a","aac","-shortest",output.resolve("clip.mp4").toString());
        command(output,"ffmpeg","-hide_banner","-loglevel","error","-y","-f","lavfi","-i",
                "testsrc2=size=1500x1000:rate=1","-frames:v","1",output.resolve("image.png").toString());
        }
        String workerToken=UUID.randomUUID().toString();
        String recoveryToken=UUID.randomUUID().toString();
        String internalToken=UUID.randomUUID().toString();
        String adminSecret=UUID.randomUUID().toString();
        Map<String,String> settings=new HashMap<>(Map.of(
                "portfolio.media-pipeline.storage.endpoint","http://127.0.0.1:19090",
                "portfolio.media-pipeline.storage.region","us-east-1",
                "portfolio.media-pipeline.storage.access-key","local-media-test",
                "portfolio.media-pipeline.storage.secret-key","local-media-test",
                "portfolio.media-pipeline.internal-token",workerToken,
                "portfolio.media-pipeline.worker.work-dir",output.resolve("work").toString(),
                "portfolio.media-pipeline.worker.poll-interval-ms","100",
                "portfolio.media-pipeline.worker.concurrency","1",
                "portfolio.media-pipeline.tools.x264-preset",catalog == null ? "ultrafast" : "fast"));
        var props=bind(settings,MediaProperties.class);
        var broker=new EmbeddedKafkaKraftBroker(1,1,"media.local.e2e");
        broker.brokerProperty("num.network.threads","2");
        broker.brokerProperty("num.io.threads","2");
        broker.afterPropertiesSet();
        try(var db=new MediaTestSchema()) {
            var repository=new MediaRepository(db.jdbc);
            var flow=new JobFlow(repository,db.transactions);
            var directories=new WorkDirectories(props);
            var storage=new S3ObjectStore(props);
            var sources=new SourceCache(storage,props,directories);
            var outputs=new Outputs(storage,props);
            var extras=new VideoExtrasHandlers();
            var images=extras.imageTool(props);
            var runner=new JobRunner(repository,flow,directories,props,List.of(
                    new PlanHandler(repository,flow,sources,props,images,mapper),
                    new VideoRenditionHandler(repository,flow,sources,outputs,props,mapper),
                    extras.videoPosterHandler(repository,flow,sources,outputs,props,images,mapper),
                    extras.videoMp4Handler(repository,flow,sources,outputs,props,mapper),
                    new ImageVariantsHandler(repository,flow,sources,outputs,images,mapper),
                    new PublishHandler(repository,flow,outputs,mapper)));
            var intake=new WorkIntake(repository,flow,runner);
            var kafkaSettings=new KafkaProperties();
            kafkaSettings.setBootstrapServers(List.of(broker.getBrokersAsString()));
            kafkaSettings.getConsumer().setGroupId("local-media-"+UUID.randomUUID());
            kafkaSettings.getConsumer().setAutoOffsetReset("earliest");
            kafkaSettings.getConsumer().setEnableAutoCommit(false);
            var consumerConfig=new KafkaConsumerConfig();
            var consumerFactory=consumerConfig.mediaWorkConsumerFactory(kafkaSettings,
                    new DefaultListableBeanFactory().getBeanProvider(SslBundles.class));
            var container=consumerConfig.mediaWorkListenerContainerFactory(consumerFactory,repository,mapper)
                    .createContainer("media.local.e2e");
            var listener=new KafkaWorkListener(intake);
            var received=new AtomicInteger();
            container.setupMessageListener((AcknowledgingMessageListener<String,WorkEvent>) (record,ack) -> {
                listener.onEvent(record,ack); received.incrementAndGet();
            });
            container.start();
            var wakesReceived=new AtomicInteger();
            Filter wakeFailure=(request,response,chain) -> {
                var http=(jakarta.servlet.http.HttpServletRequest)request;
                if (http.getRequestURI().contains("/worker/drain/") && workerToken.equals(http.getHeader("X-Internal-Token"))) {
                    if(wakesReceived.incrementAndGet()==1) {
                        ((jakarta.servlet.http.HttpServletResponse)response).setStatus(503); return;
                    }
                    if(!runner.isRunning()) runner.start();
                }
                chain.doFilter(request,response);
            };
            var producer=new DefaultKafkaProducerFactory<String,Object>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,broker.getBrokersAsString(),
                    ProducerConfig.ACKS_CONFIG,"all",
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,JsonSerializer.class,
                    JsonSerializer.ADD_TYPE_INFO_HEADERS,false));
            try(var worker=web(new InternalController(repository,intake,runner,props,false),wakeFailure)) {
                settings.put("portfolio.media-pipeline.enabled","true");
                settings.put("portfolio.media-pipeline.events.topic","media.local.e2e");
                settings.put("portfolio.media-pipeline.upload.part-size-bytes","5242880");
                settings.put("portfolio.media-pipeline.media-service.base-url",url(worker));
                settings.put("portfolio.media-pipeline.media-service.internal-token",workerToken);
                settings.put("portfolio.media-pipeline.media-service.identity-token","false");
                settings.put("portfolio.media-pipeline.media-service.wake-wait-ms","120000");
                var config=bind(settings,MediaPipelineProperties.class);
                var transport=new KafkaMediaEventTransport(new KafkaTemplate<>(producer),config);
                var store=new MediaAssetStore(db.jdbc);
                var relay=new MediaOutboxRelay(store,transport,mapper,config);
                var wake=new MediaServiceWakeClient(config,new CloudRunIdentityTokenProvider(mapper,"http://127.0.0.1:9/unused"));
                var dispatcher=new MediaWakeDispatcher(new MediaWakeStore(db.jdbc),wake,config);
                var uploads=new MediaUploadService(store,new OriginalsStore(config),config,relay,db.transactions,mapper);
                var presentations=new MediaPresentationService(db.jdbc,new MediaArticlePolicy(store),
                        mock(site.yuqi.admin.service.AuditLogService.class),mapper,db.transactions);
                var management=new MediaManagementService(store,uploads,
                        mock(site.yuqi.admin.service.AuditLogService.class),db.transactions);
                try(var admin=web(new MediaAdminController(uploads,management),
                        new MediaPresentationController(presentations),
                        new MediaRecoveryController(relay,dispatcher,recoveryToken),
                        new InternalTokenFilter(internalToken),
                        new AdminAuthFilter(adminSecret,mock(SupabaseTokenVerifier.class),"",mock(AdminUserService.class)))) {
                    var http=HttpClient.newHttpClient();
                    assertThat(http.send(HttpRequest.newBuilder(URI.create(url(admin)+"/api/admin/media")).build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
                    assertThat(http.send(HttpRequest.newBuilder(URI.create(url(admin)+"/api/internal/media/recover")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
                    ProcessBuilder clock=new ProcessBuilder("node",Path.of("../scripts/media-recovery-loop.mjs").toAbsolutePath().toString());
                    clock.environment().putAll(Map.of("MEDIA_RECOVERY_URL",url(admin)+"/api/internal/media/recover",
                            "MEDIA_RECOVERY_TOKEN",recoveryToken,"INTERNAL_API_TOKEN",internalToken,"MEDIA_RECOVERY_INTERVAL_MS","1000"));
                    Process scheduler=clock.redirectErrorStream(true).redirectOutput(output.resolve("recovery.log").toFile()).start();
                    try {
                        ProcessBuilder client=new ProcessBuilder("node",frontend.resolve("scripts/media-local-e2e.mjs").toString());
                        client.environment().putAll(Map.of("MEDIA_E2E_ADMIN_URL",url(admin),"MEDIA_E2E_ADMIN_SECRET",adminSecret,
                                "MEDIA_E2E_OUTPUT",output.toString()));
                        Process upload=client.redirectErrorStream(true).redirectOutput(output.resolve("upload.log").toFile()).start();
                        try { assertThat(upload.waitFor(catalog == null ? 270 : 1500,TimeUnit.SECONDS)).isTrue(); assertThat(upload.exitValue()).isZero(); }
                        finally { if(upload.isAlive()) upload.destroyForcibly(); }
                        assertThat(wakesReceived.get()).isGreaterThanOrEqualTo(assetCount + 1);
                        assertThat(received.get()).isEqualTo(assetCount);
                        assertThat(db.count("select count(*) from media_public")).isEqualTo(assetCount);
                        // The same processed asset can be published to the homepage without a separate MP4 upload.
                        UUID videoId=UUID.fromString(db.text("select id::text from media_assets where kind='VIDEO' and status='READY' limit 1"));
                        String placementUrl=url(admin)+"/api/admin/media/presentations/yuqi-intro";
                        var beforeResponse=http.send(HttpRequest.newBuilder(URI.create(placementUrl))
                                .header("X-Admin-Secret",adminSecret).build(),HttpResponse.BodyHandlers.ofString());
                        assertThat(beforeResponse.statusCode()).isEqualTo(200);
                        String revision=mapper.readTree(beforeResponse.body()).path("revision").asText();
                        String replacement=mapper.writeValueAsString(Map.of("id",videoId,"expectedRevision",revision,
                                "title","Local processed introduction","chapters",List.of(Map.of("seconds",0,"title","Start"))));
                        var replaceRequest=HttpRequest.newBuilder(URI.create(placementUrl)).header("X-Admin-Secret",adminSecret)
                                .header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(replacement)).build();
                        assertThat(http.send(replaceRequest,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
                        assertThat(db.text("select asset_id::text from media_demo_catalog where placement_key='yuqi-intro'"))
                                .isEqualTo(videoId.toString());
                        Files.writeString(output.resolve("presentation.json"),db.text("select row_to_json(p)::text from media_demo_catalog p where placement_key='yuqi-intro'"));
                        assertThat(db.count("select count(*) from media_event_outbox")).isEqualTo(assetCount);
                        assertThat(db.count("select max(wake_attempts) from media_event_outbox")).isGreaterThanOrEqualTo(2);
                        // Replay the real Kafka payload: one PLAN and one publication per asset remain.
                        var event=mapper.readValue(db.text("select payload::text from media_event_outbox limit 1"),MediaWorkEvent.class);
                        transport.send(event);
                        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                        while(received.get()<assetCount+1 && System.nanoTime()<deadline) Thread.sleep(50);
                        assertThat(received.get()).isEqualTo(assetCount+1);
                        assertThat(db.count("select count(*) from media_jobs where job_type='PLAN'")).isEqualTo(assetCount);
                        assertThat(db.count("select count(*) from media_public")).isEqualTo(assetCount);
                        dispatcher.drainAndAwait();
                        assertThat(new MediaWakeStore(db.jdbc).counts()).containsEntry("DONE",assetCount);
                        Files.writeString(output.resolve("verification.json"),mapper.writeValueAsString(Map.of(
                                "kafkaDeliveries",received.get(),"publishedAssets",assetCount,"wakeRequests",wakesReceived.get(),
                                "wakeRetries",db.count("select max(wake_attempts) from media_event_outbox"),
                                "duplicateCompletionSafe",true,"duplicateKafkaSafe",true,"unauthenticatedRejected",true)));
                    } finally { scheduler.destroy(); if(!scheduler.waitFor(10,TimeUnit.SECONDS)) scheduler.destroyForcibly(); }
                } finally { dispatcher.close(); relay.close(); }
            } finally { container.stop(); runner.stop(); producer.destroy(); }
        } finally { broker.destroy(); }
    }

    private static <T> T bind(Map<String,String> values,Class<T> type) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("portfolio.media-pipeline",type);
    }
    private static ServletWebServerApplicationContext web(Object... components) {
        var app=new SpringApplication(WebOnly.class);
        app.addInitializers(context -> {
            var beans=(GenericApplicationContext)context;
            for(int i=0;i<components.length;i++) {
                Object component=components[i];
                beans.registerBean("localComponent"+i,(Class)component.getClass(),() -> component);
            }
        });
        return (ServletWebServerApplicationContext)app.run("--spring.config.name=media-local-e2e",
                "--server.address=127.0.0.1","--server.port=0","--portfolio.media-pipeline.enabled=true",
                "--logging.level.root=WARN","--spring.main.banner-mode=off");
    }
    private static String url(ServletWebServerApplicationContext context) {
        return "http://127.0.0.1:"+context.getWebServer().getPort();
    }
    private static void command(Path cwd,String... args) throws Exception {
        Process process=new ProcessBuilder(args).directory(cwd.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(cwd.resolve("fixture.log").toFile())).start();
        try { assertThat(process.waitFor(60,TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero(); }
        finally { if(process.isAlive()) process.destroyForcibly(); }
    }
}
