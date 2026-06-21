package site.yuqi.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContentMutationRequest {
    /** Adapter-specific payload. Unknown keys are ignored by the adapter. */
    private java.util.Map<String, Object> data;
    /** If true, immediately publish after the create/update. */
    private boolean publish;
    /** Optional human-readable change note attached to the version row. */
    private String changeNote;
}
