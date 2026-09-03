package site.yuqi.admin.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts an actor identifier from the current request. The auth filter
 * stores either the admin secret marker or the Supabase JWT email on the
 * request attribute {@link #ATTR}.
 */
public final class AdminPrincipal {
    public static final String ATTR = "site.yuqi.admin.principal";
    public static final String ROLE_ATTR = "site.yuqi.admin.role";
    public static final String OWNER_ATTR = "site.yuqi.admin.owner";
    public static final String AUTH_SOURCE_ATTR = "site.yuqi.admin.auth_source";

    private AdminPrincipal() {}

    public static String from(HttpServletRequest req) {
        Object v = req.getAttribute(ATTR);
        StringBuilder actor = new StringBuilder(v == null ? "admin" : String.valueOf(v));
        append(actor, "tool", req.getHeader("X-MCP-Tool"));
        append(actor, "client", req.getHeader("X-MCP-Client"));
        append(actor, "model", req.getHeader("X-MCP-Model"));
        append(actor, "requestedBy", req.getHeader("X-MCP-Actor"));
        return actor.length() > 500 ? actor.substring(0, 500) : actor.toString();
    }

    private static void append(StringBuilder actor, String key, String raw) {
        if (raw == null || raw.isBlank()) return;
        String safe = raw.replaceAll("[^A-Za-z0-9@._:/ -]", "");
        if (!safe.isBlank()) actor.append('|').append(key).append('=').append(safe, 0, Math.min(100, safe.length()));
    }

    public static String roleFrom(HttpServletRequest req) {
        Object v = req.getAttribute(ROLE_ATTR);
        return v == null ? "" : String.valueOf(v);
    }

    public static boolean isOwner(HttpServletRequest req) {
        return Boolean.TRUE.equals(req.getAttribute(OWNER_ATTR));
    }
}
