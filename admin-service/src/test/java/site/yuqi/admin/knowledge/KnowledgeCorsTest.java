package site.yuqi.admin.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import site.yuqi.admin.config.WebConfig;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeCorsTest {
    private MockHttpServletResponse preflight(String origin) throws Exception {
        var config = new WebConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", "https://portfolio.example.test");
        var request = new MockHttpServletRequest("OPTIONS", "/api/admin/knowledge");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "authorization,content-type,idempotency-key");
        var response = new MockHttpServletResponse();
        config.corsFilterRegistration().getFilter().doFilter(request, response, (req, res) -> {});
        return response;
    }

    @Test void browserCanSendIdempotentKnowledgeMutation() throws Exception {
        var response = preflight("https://portfolio.example.test");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("idempotency-key");
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://portfolio.example.test");
    }

    @Test void unknownOriginsRemainBlocked() throws Exception {
        var response = preflight("https://untrusted.example.test");
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }
}
