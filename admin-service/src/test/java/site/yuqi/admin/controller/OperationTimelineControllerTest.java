package site.yuqi.admin.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import site.yuqi.admin.operations.OperationEventPublisher;
import site.yuqi.admin.operations.OperationTimelineService;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationTimelineControllerTest {

    @Test
    void returnsRetryableServiceUnavailableWhenProjectionCannotBeReached() throws Exception {
        OperationTimelineService timeline = mock(OperationTimelineService.class);
        when(timeline.find("run-123", 25)).thenThrow(new IOException("backend unavailable"));
        OperationTimelineController controller = new OperationTimelineController(
                timeline, mock(OperationEventPublisher.class));

        ResponseEntity<?> response = controller.find("run-123", 25);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
        assertThat(response.getBody()).isInstanceOf(Map.class);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body.get("error")).isEqualTo("timeline_projection_unavailable");
        assertThat(body.get("retryable")).isEqualTo(true);
    }
}
