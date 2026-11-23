package site.yuqi.admin.mediapipeline;

import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

final class ArticleMediaMarkup {
    private ArticleMediaMarkup() { }

    static String body(String html) { return html == null ? "" : html; }

    static List<Element> figures(String html) {
        return Parser.htmlParser().setTrackPosition(true).parseInput(body(html), "")
                .select("figure[data-media-id]");
    }

    static List<UUID> ids(String html) {
        var figures = figures(html);
        if (figures.size() > 50) throw new IllegalArgumentException("At most 50 media references per article");
        return figures.stream().map(el -> {
            String id = el.attr("data-media-id");
            if (!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                throw new IllegalArgumentException("Invalid article media UUID");
            return UUID.fromString(id);
        }).distinct().toList();
    }

    static String hash(String html) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body(html).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static String attach(String html, UUID id, String position) {
        if (!"START".equals(position) && !"END".equals(position)) throw new IllegalArgumentException("position must be START or END");
        if (ids(html).contains(id)) return body(html);
        if (ids(html).size() >= 50) throw new IllegalArgumentException("Article media limit reached");
        String figure = "<figure class=\"media-asset\" data-media-id=\"" + id + "\" data-media-kind=\"VIDEO\"></figure>";
        return "START".equals(position) ? figure + body(html) : body(html) + figure;
    }

    static String detach(String html, UUID id) {
        ids(html);
        StringBuilder result = new StringBuilder(body(html));
        var matches = figures(html).stream().filter(el -> el.attr("data-media-id").equalsIgnoreCase(id.toString()))
                .sorted(java.util.Comparator.comparingInt((Element el) -> el.sourceRange().start().pos()).reversed()).toList();
        // Edit only the original figure ranges. Re-serializing the article would alter Mermaid/code entities.
        int nextStart = result.length();
        for (Element el : matches) {
            if (!el.sourceRange().isTracked() || !el.endSourceRange().isTracked() || el.endSourceRange().isImplicit())
                throw new IllegalArgumentException("Media figure must have an explicit closing tag");
            if (el.endSourceRange().end().pos() > nextStart)
                throw new IllegalArgumentException("Nested media figures must be corrected before removal");
            result.delete(el.sourceRange().start().pos(), el.endSourceRange().end().pos());
            nextStart = el.sourceRange().start().pos();
        }
        return result.toString();
    }
}
