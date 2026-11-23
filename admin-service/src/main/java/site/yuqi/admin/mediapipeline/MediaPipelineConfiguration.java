package site.yuqi.admin.mediapipeline;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code portfolio.media-pipeline.*}. The beans themselves switch on with {@code enabled=true}. */
@Configuration
@EnableConfigurationProperties(MediaPipelineProperties.class)
public class MediaPipelineConfiguration {
}
