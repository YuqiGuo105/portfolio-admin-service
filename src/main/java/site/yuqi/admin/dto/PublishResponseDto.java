package site.yuqi.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import site.yuqi.admin.domain.ContentEventOutbox;
import site.yuqi.admin.domain.ContentVersion;
import site.yuqi.admin.domain.IndexingJob;
import site.yuqi.admin.service.PublishResult;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishResponseDto {
    private ContentVersion version;
    private ContentEventOutbox outboxEvent;
    private IndexingJob ragJob;
    private IndexingJob searchJob;

    public static PublishResponseDto from(PublishResult r) {
        return PublishResponseDto.builder()
                .version(r.getVersion())
                .outboxEvent(r.getOutboxEvent())
                .ragJob(r.getRagJob())
                .searchJob(r.getSearchJob())
                .build();
    }
}
