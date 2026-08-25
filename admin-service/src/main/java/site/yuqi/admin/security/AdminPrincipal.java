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
        return v == null ? "admin" : String.valueOf(v);
    }

    public static String roleFrom(HttpServletRequest req) {
        Object v = req.getAttribute(ROLE_ATTR);
        return v == null ? "" : String.valueOf(v);
    }

    public static boolean isOwner(HttpServletRequest req) {
        return Boolean.TRUE.equals(req.getAttribute(OWNER_ATTR));
    }
}
