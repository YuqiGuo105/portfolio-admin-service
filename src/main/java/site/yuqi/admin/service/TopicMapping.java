package site.yuqi.admin.service;

import site.yuqi.admin.domain.SourceType;
import site.yuqi.admin.domain.Topic;

/**
 * SourceType -> Notification Topic. This is intentionally a separate concern
 * from {@link site.yuqi.admin.adapter.ContentAdapter} because the same content
 * might emit different topics over time without changing the adapter.
 */
public final class TopicMapping {

    private TopicMapping() {}

    public static Topic topicFor(SourceType sourceType) {
        return switch (sourceType) {
            case BLOG, LIFE_BLOG -> Topic.ARTICLE_UPDATES;
            case PROJECT -> Topic.FEATURE_UPDATES;
            case EXPERIENCE -> Topic.JOB_UPDATES;
        };
    }
}
