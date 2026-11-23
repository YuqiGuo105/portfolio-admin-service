package site.yuqi.media.support;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import site.yuqi.media.config.MediaProperties;

import java.util.Map;

/** MediaProperties as Spring would build them: the defaults, plus whatever a test overrides. */
public final class TestProperties {

    private TestProperties() {
    }

    public static MediaProperties defaults() {
        return with(Map.of());
    }

    public static MediaProperties with(Map<String, String> overrides) {
        return new Binder(new MapConfigurationPropertySource(overrides))
                .bindOrCreate("portfolio.media-pipeline", MediaProperties.class);
    }
}
