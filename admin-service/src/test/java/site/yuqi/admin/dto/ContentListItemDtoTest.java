package site.yuqi.admin.dto;

import org.junit.jupiter.api.Test;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.SourceType;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ContentListItemDtoTest {
    @Test void preservesExperienceDatesWithoutInventingOrLeakingRawFields() {
        var content = NormalizedContent.builder().sourceType(SourceType.EXPERIENCE)
                .sourceId("current-role").title("Current employer")
                .raw(Map.of("date", "Dec 2024 - Current", "private_field", "not exported")).build();
        assertEquals("Dec 2024 - Current", ContentListItemDto.fromNormalized(content).getPeriod());
        content.setRaw(Map.of());
        assertNull(ContentListItemDto.fromNormalized(content).getPeriod());
        content.setSourceType(SourceType.PROJECT);
        content.setRaw(Map.of("date", "2025"));
        assertNull(ContentListItemDto.fromNormalized(content).getPeriod());
    }
}
