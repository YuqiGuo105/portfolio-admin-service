package site.yuqi.admin.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import site.yuqi.admin.domain.ContentEventOutbox;
import site.yuqi.admin.domain.ContentVersion;
import site.yuqi.admin.domain.IndexingJob;

@Getter
@Builder
@AllArgsConstructor
public class PublishResult {
    private final ContentVersion version;
    private final ContentEventOutbox outboxEvent;
    private final IndexingJob ragJob;
    private final IndexingJob searchJob;
}
