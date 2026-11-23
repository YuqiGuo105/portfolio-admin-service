package site.yuqi.searchindexer.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import site.yuqi.searchindexer.enrich.SearchTermsGenerator;
import site.yuqi.searchindexer.events.ContentIndexEvent;
import site.yuqi.searchindexer.jobs.IndexingJobUpdater;
import site.yuqi.searchindexer.opensearch.OpenSearchIndexClient;
import site.yuqi.searchindexer.operations.OperationEventPublisher;
import site.yuqi.searchindexer.source.ContentFetcher;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** The topic is shared with the Media Service; its events must pass through here without a trace. */
class SearchIndexConsumerMediaEventTest {

    @Test
    void mediaWorkOnTheSharedTopicIsAcknowledgedAndLeftAlone() {
        IndexingJobUpdater jobs = mock(IndexingJobUpdater.class);
        ContentFetcher fetcher = mock(ContentFetcher.class);
        OpenSearchIndexClient openSearch = mock(OpenSearchIndexClient.class);
        SearchTermsGenerator searchTerms = mock(SearchTermsGenerator.class);
        OperationEventPublisher operations = mock(OperationEventPublisher.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        SearchIndexConsumer consumer = new SearchIndexConsumer(jobs, fetcher, openSearch, searchTerms, operations);

        ContentIndexEvent media = ContentIndexEvent.builder()
                .eventId("evt-media-1")
                .sourceType("MEDIA")
                .sourceId(UUID.randomUUID().toString())
                .sourceVersion(1)
                .jobType("MEDIA_PROCESS")
                .build();
        consumer.onEvent(new ConsumerRecord<>("content.search.index.v1", 0, 7L, "MEDIA:asset", media), ack);

        verify(ack).acknowledge();
        verifyNoInteractions(jobs, fetcher, openSearch, searchTerms, operations);
    }
}
