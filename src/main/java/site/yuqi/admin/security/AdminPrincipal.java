package site.yuqi.admin.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts an actor identifier from the current request. The auth filter
 * stores either the admin secret marker or the Supabase JWT email on the
 * request attribute {@link #ATTR}.
 */
public final class AdminPrincipal {
    public static final String ATTR = "site.yuqi.admin.principal";

    private AdminPrincipal() {}

    public static String from(HttpServletRequest req) {
        Object v = req.getAttribute(ATTR);
        return v == null ? "admin" : String.valueOf(v);
    }
}
