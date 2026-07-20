package site.yuqi.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ProjectSubsystemRequest {
    @NotBlank
    @Size(max = 80)
    private String slug;

    @NotBlank
    @Size(max = 160)
    private String title;

    @Size(max = 160)
    private String eyebrow;

    @NotBlank
    @Size(max = 800)
    private String summary;

    @NotBlank
    @Size(max = 400)
    private String designIntent;

    @NotBlank
    private String maturity;

    private Integer sortOrder;
    private UUID linkedProjectId;
    private Boolean active;

    @NotNull
    private JsonNode diagramConfig;
}
