package site.yuqi.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.yuqi.admin.domain.ContentEventOutbox;
import site.yuqi.admin.domain.OutboxEventType;
import site.yuqi.admin.domain.OutboxStatus;
import site.yuqi.admin.repo.ContentEventOutboxRepository;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock ContentEventOutboxRepository repository;

    private OutboxService service;

    @BeforeEach
    void setUp() {
        service = new OutboxService(repository);
    }

    @Test
    void retryRequeuesFailedEventAndClearsError() {
        UUID id = UUID.randomUUID();
        ContentEventOutbox event = event(id, OutboxStatus.FAILED);
        event.setLastError("broker unavailable");
        when(repository.findById(id)).thenReturn(Optional.of(event));

        ContentEventOutbox retried = service.retry(id);

        assertThat(retried.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(retried.getLastError()).isNull();
        assertThat(retried.getNextRetryAt()).isNotNull();
    }

    @Test
    void retryRejectsAlreadySentEvent() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(event(id, OutboxStatus.SENT)));

        assertThatThrownBy(() -> service.retry(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only FAILED, DLQ, or leased PROCESSING");
    }

    @Test
    void replayRequeuesSentEventAndClearsDeliveryState() {
        UUID id = UUID.randomUUID();
        ContentEventOutbox event = event(id, OutboxStatus.SENT);
        event.setSentAt(Instant.now());
        event.setLastError("old error");
        when(repository.findById(id)).thenReturn(Optional.of(event));

        ContentEventOutbox replayed = service.replay(id);

        assertThat(replayed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(replayed.getSentAt()).isNull();
        assertThat(replayed.getLastError()).isNull();
        assertThat(replayed.getNextRetryAt()).isNotNull();
    }

    @Test
    void replayRejectsInFlightEvent() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(event(id, OutboxStatus.PROCESSING)));

        assertThatThrownBy(() -> service.replay(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already pending or processing");
    }

    @Test
    void claimDelegatesToAtomicRepositoryClaimAndLoadsOnlyClaimedRows() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<UUID> ids = List.of(first, second);
        List<ContentEventOutbox> events = List.of(
                event(first, OutboxStatus.PROCESSING),
                event(second, OutboxStatus.PROCESSING));
        when(repository.claimReadyIds(any(Instant.class), any(Instant.class), eq(2))).thenReturn(ids);
        when(repository.findAllById(ids)).thenReturn(events);

        assertThat(service.claimReadyOutboxEvents(2, 60)).containsExactlyElementsOf(events);

        verify(repository).claimReadyIds(any(Instant.class), any(Instant.class), eq(2));
        verify(repository).findAllById(ids);
    }

    private static ContentEventOutbox event(UUID id, OutboxStatus status) {
        return ContentEventOutbox.builder()
                .id(id)
                .eventType(OutboxEventType.CONTENT_PUBLISHED)
                .sourceType("BLOG")
                .sourceIdText(UUID.randomUUID().toString())
                .sourceVersion(1)
                .topic("ARTICLE")
                .payload(Map.of("id", id.toString()))
                .idempotencyKey("key-" + id)
                .status(status)
                .build();
    }
}
