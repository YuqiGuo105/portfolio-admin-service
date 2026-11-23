package site.yuqi.admin.mediapipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import site.yuqi.admin.events.CloudRunIdentityTokenProvider;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class MediaServiceWakeClientTest {
    @Test void onlyAnAuthenticatedTerminalResponseForTheRequestedAssetCompletesTheWake() throws Exception {
        var code=new AtomicInteger(202);
        var body=new AtomicReference<>("{}");
        var token=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/internal/worker/drain/",exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code.get(),bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var config=new Binder(new MapConfigurationPropertySource(Map.of(
                    "portfolio.media-pipeline.media-service.base-url","http://127.0.0.1:"+server.getAddress().getPort(),
                    "portfolio.media-pipeline.media-service.internal-token","local-test-only",
                    "portfolio.media-pipeline.media-service.identity-token","false",
                    "portfolio.media-pipeline.media-service.wake-wait-ms","1000")))
                    .bindOrCreate("portfolio.media-pipeline",MediaPipelineProperties.class);
            var client=new MediaServiceWakeClient(config,new CloudRunIdentityTokenProvider(new ObjectMapper(),"http://127.0.0.1:9/unused"));
            UUID asset=UUID.randomUUID();
            assertThat(client.wakeAndAwait(asset)).isFalse();
            assertThat(token.get()).isEqualTo("local-test-only");
            code.set(401); assertThat(client.wakeAndAwait(asset)).isFalse();
            code.set(200);
            body.set("not json"); assertThat(client.wakeAndAwait(asset)).isFalse();
            body.set("{\"assetId\":\""+asset+"\",\"status\":\"PROCESSING\"}");
            assertThat(client.wakeAndAwait(asset)).isFalse();
            body.set("{\"assetId\":\""+UUID.randomUUID()+"\",\"status\":\"READY\"}");
            assertThat(client.wakeAndAwait(asset)).isFalse();
            for(String status : new String[]{"READY","FAILED"}) {
                body.set("{\"assetId\":\""+asset+"\",\"status\":\""+status+"\"}");
                assertThat(client.wakeAndAwait(asset)).isTrue();
            }
        } finally { server.stop(0); }
    }
}
