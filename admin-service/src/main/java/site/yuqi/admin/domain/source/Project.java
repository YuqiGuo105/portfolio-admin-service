package site.yuqi.admin.domain.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps to public."Projects". The original schema uses a quoted PascalCase
 * column named "URL"; we expose it as {@code externalUrl}.
 */
@Entity
@Table(name = "\"Projects\"")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "title")
    private String title;

    @Column(name = "content")
    private String content;

    @Column(name = "summary")
    private String summary;

    @Column(name = "publication_status", nullable = false)
    private String publicationStatus;

    @Column(name = "featured", nullable = false)
    private boolean featured;

    @Column(name = "cover_variant", nullable = false)
    private String coverVariant;

    @Column(name = "experience_variant")
    private String experienceVariant;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "\"URL\"")
    private String externalUrl;

    @Column(name = "category")
    private String category;

    @Column(name = "year")
    private String year;

    @Column(name = "technology")
    private String technology;

    @Column(name = "num")
    private Integer num;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (publicationStatus == null) publicationStatus = "DRAFT";
        if (coverVariant == null) coverVariant = "IMAGE";
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
