package site.yuqi.admin.knowledge;

public record KnowledgeMutation(Document document, Policy policy, Preconditions preconditions) {
    public record Document(String title, String question, String content) {}
    public record Policy(String status, String answerVisibility) {}
    public record Preconditions(String revision) {}

    public KnowledgeMutation(String title, String question, String content, String status, String visibility, String revision) {
        this(new Document(title, question, content), new Policy(status, visibility), new Preconditions(revision));
    }
    public String title() { return document == null ? null : document.title(); }
    public String question() { return document == null ? null : document.question(); }
    public String content() { return document == null ? null : document.content(); }
    public String status() { return policy == null ? null : policy.status(); }
    public String answerVisibility() { return policy == null ? null : policy.answerVisibility(); }
    public String expectedRevision() { return preconditions == null ? null : preconditions.revision(); }

    public KnowledgeMutation validated() {
        String t = text(title(), 240, true);
        String q = text(question(), 1000, false);
        String c = text(content(), 20000, true);
        String s = status() == null ? "DRAFT" : status();
        String v = answerVisibility() == null ? "private" : answerVisibility();
        if (!java.util.Set.of("DRAFT", "ACTIVE", "ARCHIVED").contains(s))
            throw new IllegalArgumentException("status must be DRAFT, ACTIVE or ARCHIVED");
        if (!java.util.Set.of("private", "public").contains(v))
            throw new IllegalArgumentException("answerVisibility must be private or public");
        if ("ACTIVE".equals(s) && !"public".equals(v))
            throw new IllegalArgumentException("Active knowledge requires explicit public-answer approval");
        return new KnowledgeMutation(t, q, c, s, v, expectedRevision());
    }

    private static String text(String value, int max, boolean required) {
        String result = value == null ? "" : value.strip();
        if ((required && result.isEmpty()) || result.length() > max || result.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid knowledge text: required fields must be non-empty and within length limits");
        return result;
    }
}
