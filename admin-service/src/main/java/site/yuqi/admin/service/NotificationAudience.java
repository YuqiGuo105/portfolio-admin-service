package site.yuqi.admin.service;

public enum NotificationAudience {
    ALL_SUBSCRIBERS,
    ADMINS_ONLY,
    NONE;

    public static NotificationAudience resolve(Boolean notifySubscribers, String audience) {
        if (Boolean.FALSE.equals(notifySubscribers)) return NONE;
        if (audience == null || audience.isBlank()) return ALL_SUBSCRIBERS;
        try {
            return valueOf(audience.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("audience must be ALL_SUBSCRIBERS, ADMINS_ONLY, or NONE");
        }
    }
}
