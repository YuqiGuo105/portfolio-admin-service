package site.yuqi.admin.security;

import jakarta.servlet.http.HttpServletRequest;

final class RequestPaths {
    private RequestPaths() {}

    // Reject ambiguous paths instead of making security decisions on a different
    // representation than the servlet container or Spring MVC will route.
    static String canonical(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/") || path.contains("%")
                || path.contains(";") || path.contains("\\") || path.contains("//")
                || path.chars().anyMatch(c -> c <= 32 || c == 127)) return null;
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) return null;
        }
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && under(path, context)) {
            path = path.substring(context.length());
        }
        return path;
    }

    static boolean under(String path, String prefix) {
        return path != null && (path.equals(prefix) || path.startsWith(prefix + "/"));
    }
}
