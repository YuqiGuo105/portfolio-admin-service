package site.yuqi.admin.mediapipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import site.yuqi.admin.security.InternalTokenFilter;

import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MediaRecoveryAuthenticationTest {
    private static final String INTERNAL = "test-internal-token-with-at-least-32-characters";
    private static final String RECOVERY = "test-recovery-token-with-at-least-32-characters";
    private final MediaOutboxRelay relay = mock(MediaOutboxRelay.class);
    private final MediaWakeDispatcher wakes = mock(MediaWakeDispatcher.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new MediaRecoveryController(relay, wakes, RECOVERY))
                .addFilters(new InternalTokenFilter(INTERNAL)).build();
        when(wakes.drainAndAwait()).thenReturn(Map.of("done", 0));
    }

    @Test
    void requiresBothTokensBeforeRecovering() throws Exception {
        mvc.perform(post("/api/internal/media/recover")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/internal/media/recover").header("X-Media-Recovery-Token", RECOVERY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/internal/media/recover").header("X-Internal-Token", INTERNAL))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/internal/media/recover").header("X-Internal-Token", INTERNAL)
                .header("X-Media-Recovery-Token", "wrong")).andExpect(status().isUnauthorized());
        verifyNoInteractions(relay, wakes);
        mvc.perform(post("/api/internal/media/recover").header("X-Internal-Token", INTERNAL)
                .header("X-Media-Recovery-Token", RECOVERY)).andExpect(status().isOk());
        verify(relay).drainOnce();
        verify(wakes).drainAndAwait();
    }
}
