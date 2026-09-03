package site.yuqi.admin.dto;

import lombok.Data;

@Data
public class RollbackRequest {
    private int version;
    private String changeNote;
    private Boolean notifySubscribers = false;
    private String audience = "NONE";
}
