package site.yuqi.admin.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import site.yuqi.admin.adapter.ContentAdapterRegistry;
import site.yuqi.admin.domain.ContentVersion;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.domain.JobStatus;
import site.yuqi.admin.domain.JobType;
import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.domain.Topic;
import site.yuqi.admin.repo.AuditLogRepository;
import site.yuqi.admin.repo.ContentEventOutboxRepository;
import site.yuqi.admin.repo.ContentVersionRepository;
import site.yuqi.admin.repo.IndexingJobRepository;
import site.yuqi.admin.repo.source.BlogRepository;
import site.yuqi.admin.repo.source.ExperienceRepository;
import site.yuqi.admin.repo.source.LifeBlogRepository;
import site.yuqi.admin.repo.source.ProjectRepository;
import site.yuqi.admin.search.SearchIndexClient;
import site.yuqi.admin.service.AuditLogService;
import site.yuqi.admin.service.ContentService;
import site.yuqi.admin.service.IndexingJobService;
import site.yuqi.admin.service.OutboxService;
import site.yuqi.admin.service.VersionService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * E2E test: publish a blog post → assert three Kafka events arrive on the
 * correct topics (notification, search-index, rag-index).
 *
 * <p>All DB-touching beans are @MockBean so no real Postgres / Flyway is
 * needed. {@link EmbeddedKafkaBroker} provides a real in-process broker.
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {
            "content.notification.article-updates.v1",
            "content.notification.feature-updates.v1",
            "content.notification.job-updates.v1",
            "content.search.index.v1",
            "content.rag.index.v1"
        }
)
@TestPropertySource(properties = {
        // Point Kafka producer at the embedded broker
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        // Disable JPA / Flyway / OpenSearch so no real infra is needed
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "portfolio.opensearch.worker.enabled=false",
        // Notification topics
        "portfolio.kafka.topics.notification.article-updates=content.notification.article-updates.v1",
        "portfolio.kafka.topics.notification.feature-updates=content.notification.feature-updates.v1",
        "portfolio.kafka.topics.notification.job-updates=content.notification.job-updates.v1",
        "portfolio.kafka.topics.search-index=content.search.index.v1",
        "portfolio.kafka.topics.rag-index=content.rag.index.v1"
})
@DirtiesContext
class ContentPublishNotificationE2ETest {

    // ── Topics under test ────────────────────────────────────────────────────
    private static final String ARTICLE_TOPIC    = "content.notification.article-updates.v1";
    private static final String SEARCH_IDX_TOPIC = "content.search.index.v1";
    private static final String RAG_IDX_TOPIC    = "content.rag.index.v1";

    @Autowired EmbeddedKafkaBroker embeddedKafka;
    @Autowired ContentService contentService;
    @Autowired ObjectMapper objectMapper;

    // ── Mock all DB-touching beans ───────────────────────────────────────────
    @MockBean ContentAdapterRegistry adapters;
    @MockBean VersionService versionService;
    @MockBean OutboxService outboxService;
    @MockBean IndexingJobService indexingJobService;
    @MockBean AuditLogService auditLogService;
    @MockBean SearchIndexClient searchIndexClient;

    // Repos needed only so Spring doesn't fail to create beans
    @MockBean BlogRepository blogRepository;
    @MockBean ProjectRepository projectRepository;
    @MockBean LifeBlogRepository lifeBlogRepository;
    @MockBean ExperienceRepository experienceRepository;
    @MockBean ContentVersionRepository contentVersionRepository;
    @MockBean ContentEventOutboxRepository contentEventOutboxRepository;
    @MockBean IndexingJobRepository indexingJobRepository;
    @MockBean AuditLogRepository auditLogRepository;

    // ── Kafka listener queues ─────────────────────────────────────────────────
    private final BlockingQueue<ConsumerRecord<String, String>> articleQueue  = new LinkedBlockingQueue<>();
    private final BlockingQueue<ConsumerRecord<String, String>> searchQueue   = new LinkedBlockingQueue<>();
    private final BlockingQueue<ConsumerRecord<String, String>> ragQueue      = new LinkedBlockingQueue<>();

    private KafkaMessageListenerContainer<String, String> articleContainer;
    private KafkaMessageListenerContainer<String, String> searchContainer;
    private KafkaMessageListenerContainer<String, String> ragContainer;

    @BeforeEach
    void setUpConsumers() {
        articleContainer = startListener(ARTICLE_TOPIC,    "test-article-group",  articleQueue);
        searchContainer  = startListener(SEARCH_IDX_TOPIC, "test-search-group",   searchQueue);
        ragContainer     = startListener(RAG_IDX_TOPIC,    "test-rag-group",      ragQueue);
    }

    @AfterEach
    void tearDownConsumers() {
        if (articleContainer != null) articleContainer.stop();
        if (searchContainer  != null) searchContainer.stop();
        if (ragContainer     != null) ragContainer.stop();
        articleQueue.clear();
        searchQueue.clear();
        ragQueue.clear();
    }

    @BeforeEach
    void setUpMocks() {
        // Build a minimal blog NormalizedContent
        var blog = site.yuqi.admin.adapter.NormalizedContent.builder()
                .sourceType(SourceType.BLOG)
                .sourceId("blog-uuid-001")
                .title("Hello Kafka")
                .summary("Testing notification events")
                .url("/blog-single/blog-uuid-001")
                .imageUrl("/img/hello.jpg")
                .category("Engineering")
                .tags(List.of("Kafka", "Spring Boot"))
                .build();

        var adapter = org.mockito.Mockito.mock(site.yuqi.admin.adapter.ContentAdapter.class);
        when(adapter.get("blog-uuid-001")).thenReturn(Optional.of(blog));
        when(adapter.toSnapshot(any())).thenReturn(Map.of("title", "Hello Kafka"));
        when(adapters.get(SourceType.BLOG)).thenReturn(adapter);

        // Version mock
        ContentVersion version = ContentVersion.builder()
                .id(UUID.randomUUID())
                .sourceType("BLOG")
                .sourceIdText("blog-uuid-001")
                .version(1)
                .createdAt(Instant.now())
                .build();
        when(versionService.nextVersionFor(anyString(), anyString())).thenReturn(1);
        when(versionService.snapshot(any(), anyInt(), anyString(), anyString(), any()))
                .thenReturn(version);

        // Outbox mock
        when(outboxService.enqueuePublish(any(), anyInt(), any()))
                .thenReturn(site.yuqi.admin.domain.ContentEventOutbox.builder()
                        .id(UUID.randomUUID())
                        .status(site.yuqi.admin.domain.OutboxStatus.PENDING)
                        .idempotencyKey("CONTENT_PUBLISHED:BLOG:blog-uuid-001:v1")
                        .build());

        // Indexing job mocks
        IndexingJob searchJob = IndexingJob.builder()
                .id(UUID.randomUUID())
                .sourceType("BLOG")
                .sourceIdText("blog-uuid-001")
                .sourceVersion(1)
                .jobType(JobType.SEARCH_INDEX)
                .status(JobStatus.PENDING)
                .idempotencyKey("SEARCH_INDEX:BLOG:blog-uuid-001:v1")
                .build();
        IndexingJob ragJob = IndexingJob.builder()
                .id(UUID.randomUUID())
                .sourceType("BLOG")
                .sourceIdText("blog-uuid-001")
                .sourceVersion(1)
                .jobType(JobType.RAG_INDEX)
                .status(JobStatus.PENDING)
                .idempotencyKey("RAG_INDEX:BLOG:blog-uuid-001:v1")
                .build();

        when(indexingJobService.enqueue(
                org.mockito.ArgumentMatchers.eq(JobType.SEARCH_INDEX), any(), anyString(), anyInt(), anyString()))
                .thenReturn(searchJob);
        when(indexingJobService.enqueue(
                org.mockito.ArgumentMatchers.eq(JobType.RAG_INDEX), any(), anyString(), anyInt(), anyString()))
                .thenReturn(ragJob);
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("publish(BLOG) → notification event on article-updates topic")
    void publish_blog_shouldFireNotificationEvent() throws Exception {
        contentService.publish(SourceType.BLOG, "blog-uuid-001", "test-admin", "E2E test publish");

        ConsumerRecord<String, String> record = articleQueue.poll(5, TimeUnit.SECONDS);

        assertThat(record).as("notification event should arrive on article-updates topic").isNotNull();

        var event = objectMapper.readValue(record.value(), ContentPublishedEvent.class);
        assertThat(event.getSourceType()).isEqualTo("BLOG");
        assertThat(event.getSourceId()).isEqualTo("blog-uuid-001");
        assertThat(event.getSourceVersion()).isEqualTo(1);
        assertThat(event.getNotificationTopic()).isEqualTo(Topic.ARTICLE_UPDATES.name());
        assertThat(event.getTitle()).isEqualTo("Hello Kafka");
        assertThat(event.getSummary()).isEqualTo("Testing notification events");
        assertThat(event.getUrl()).isEqualTo("/blog-single/blog-uuid-001");
        assertThat(event.getCategory()).isEqualTo("Engineering");
        assertThat(event.getTags()).containsExactly("Kafka", "Spring Boot");
        assertThat(event.getIdempotencyKey()).startsWith("CONTENT_PUBLISHED:BLOG:");
        assertThat(event.getEventId()).isNotBlank();
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("publish(BLOG) → notification event partition-key is 'BLOG:blog-uuid-001'")
    void publish_blog_notificationPartitionKey() throws Exception {
        contentService.publish(SourceType.BLOG, "blog-uuid-001", "test-admin", "E2E partition key test");

        ConsumerRecord<String, String> record = articleQueue.poll(5, TimeUnit.SECONDS);
        assertThat(record).isNotNull();
        assertThat(record.key()).isEqualTo("BLOG:blog-uuid-001");
    }

    @Test
    @DisplayName("publish(BLOG) → search-index and rag-index Kafka events also arrive")
    void publish_blog_shouldFireAllThreeKafkaEvents() throws Exception {
        contentService.publish(SourceType.BLOG, "blog-uuid-001", "test-admin", "all-three test");

        // notification
        assertThat(articleQueue.poll(5, TimeUnit.SECONDS))
                .as("notification event").isNotNull();

        // search indexing
        ConsumerRecord<String, String> searchRecord = searchQueue.poll(5, TimeUnit.SECONDS);
        assertThat(searchRecord).as("search-index event").isNotNull();
        var searchEvent = objectMapper.readValue(searchRecord.value(), ContentIndexEvent.class);
        assertThat(searchEvent.getSourceType()).isEqualTo("BLOG");
        assertThat(searchEvent.getJobType()).isEqualTo("SEARCH_INDEX");

        // RAG indexing
        ConsumerRecord<String, String> ragRecord = ragQueue.poll(5, TimeUnit.SECONDS);
        assertThat(ragRecord).as("rag-index event").isNotNull();
        var ragEvent = objectMapper.readValue(ragRecord.value(), ContentIndexEvent.class);
        assertThat(ragEvent.getJobType()).isEqualTo("RAG_INDEX");
    }

    @Test
    @DisplayName("publish(BLOG) → no event arrives on feature-updates or job-updates topics")
    void publish_blog_shouldNotFireOnWrongTopics() throws Exception {
        contentService.publish(SourceType.BLOG, "blog-uuid-001", "test-admin", "wrong-topic test");

        // article-updates should have a record
        assertThat(articleQueue.poll(4, TimeUnit.SECONDS)).isNotNull();

        // feature-updates and job-updates should stay empty
        var featureQueue = new LinkedBlockingQueue<ConsumerRecord<String, String>>();
        var jobQueue     = new LinkedBlockingQueue<ConsumerRecord<String, String>>();
        var featureContainer = startListener("content.notification.feature-updates.v1",
                "test-feature-wrong-group", featureQueue);
        var jobContainer = startListener("content.notification.job-updates.v1",
                "test-job-wrong-group", jobQueue);
        try {
            assertThat(featureQueue.poll(2, TimeUnit.SECONDS))
                    .as("feature-updates should be empty for a BLOG publish").isNull();
            assertThat(jobQueue.poll(2, TimeUnit.SECONDS))
                    .as("job-updates should be empty for a BLOG publish").isNull();
        } finally {
            featureContainer.stop();
            jobContainer.stop();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private KafkaMessageListenerContainer<String, String> startListener(
            String topic, String groupId,
            BlockingQueue<ConsumerRecord<String, String>> queue) {

        Map<String, Object> cfg = KafkaTestUtils.consumerProps(groupId, "true", embeddedKafka);
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cfg.put(JsonDeserializer.TRUSTED_PACKAGES, "*");

        var factory = new DefaultKafkaConsumerFactory<String, String>(cfg);
        var containerProps = new ContainerProperties(topic);
        var container = new KafkaMessageListenerContainer<>(factory, containerProps);
        container.setupMessageListener((MessageListener<String, String>) queue::add);
        container.start();
        ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic());
        return container;
    }
}
