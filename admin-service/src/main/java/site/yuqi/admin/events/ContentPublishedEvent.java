package site.yuqi.admin.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Kafka event published to the notification service topics after a
 * content item is successfully published.
 *
 * <p>Topic is determined by the source type:
 * <ul>
 *   <li>BLOG / LIFE_BLOG → {@code content.notification.article-updates.v1}</li>
 *   <li>PROJECT          → {@code content.notification.feature-updates.v1}</li>
 *   <li>EXPERIENCE       → {@code content.notification.job-updates.v1}</li>
 * </ul>
 *
 * <p>The notification service uses this event to fan out to
 * subscribed users (email, push, in-app).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContentPublishedEvent {

    /** Unique event id — notification service uses this for deduplication. */
    private String eventId;

    /** Wall-clock time when the event was produced. */
    private Instant occurredAt;

    /** BLOG / PROJECT / LIFE_BLOG / EXPERIENCE */
    private String sourceType;

    /** Source-row id as text (UUID or bigint). */
    private String sourceId;

    /** Version number of this publication (increments on each re-publish). */
    private int sourceVersion;

    /**
     * Notification domain topic: ARTICLE_UPDATES / FEATURE_UPDATES / JOB_UPDATES.
     * Matches {@link site.yuqi.admin.domain.Topic}.
     */
    private String notificationTopic;

    /** Human-readable title — ready to use in notification body. */
    private String title;

    /** Short summary / description — used in notification preview. */
    private String summary;

    /** Canonical URL of the content on the Portfolio site. */
    private String url;

    /** Cover / hero image URL — used in rich-card notifications. */
    private String imageUrl;

    /** Content category, e.g. "Engineering". */
    private String category;

    /** Tags list, e.g. ["Java","Spring Boot"]. */
    private List<String> tags;

    /**
     * Outbox idempotency key so the notification service can
     * deduplicate re-deliveries: {@code CONTENT_PUBLISHED:<TYPE>:<id>:v<n>}
     */
    private String idempotencyKey;
}
