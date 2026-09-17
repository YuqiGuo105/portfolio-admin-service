package site.yuqi.admin.dto;

import org.jsoup.Jsoup;
import site.yuqi.admin.adapter.NormalizedContent;
import site.yuqi.admin.domain.SourceType;

/** Internal, authenticated batch projection. Public APIs must not serialize text. */
public record SearchProjection(int schemaVersion, String text, boolean requiresLogin, boolean truncated) {
    public static SearchProjection from(NormalizedContent content) {
        String body = Jsoup.parse(content.getContent() == null ? "" : content.getContent()).text();
        Object access = content.getRaw() == null ? null : content.getRaw().get("require_login");
        boolean restricted = content.getSourceType() == SourceType.LIFE_BLOG
                && !Boolean.FALSE.equals(access);
        int limit = 40000;
        return new SearchProjection(1, body.substring(0, Math.min(limit, body.length())), restricted, body.length() > limit);
    }
}
