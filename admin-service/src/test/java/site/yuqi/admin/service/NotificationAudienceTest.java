package site.yuqi.admin.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationAudienceTest {
    @Test
    void defaultsToSubscribersAndFalseAlwaysSuppresses() {
        assertThat(NotificationAudience.resolve(null, null)).isEqualTo(NotificationAudience.ALL_SUBSCRIBERS);
        assertThat(NotificationAudience.resolve(false, "ADMINS_ONLY")).isEqualTo(NotificationAudience.NONE);
        assertThat(NotificationAudience.resolve(true, "admins_only")).isEqualTo(NotificationAudience.ADMINS_ONLY);
    }

    @Test
    void rejectsUnknownAudience() {
        assertThatThrownBy(() -> NotificationAudience.resolve(true, "everyone"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
