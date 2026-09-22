package com.machineroom.auth;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Requires a logged-in session for everything except the login page and static assets.
 * Also enforces a CSRF guard on state-changing API calls: they must carry
 * {@code X-Requested-With: XMLHttpRequest} (a custom header cannot be set cross-site by a form post).
 */
public class AuthFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String path = req.getRequestURI().substring(req.getContextPath().length());

        if (isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }

        UserPrincipal p = SessionUtil.principal(req);
        boolean api = path.startsWith("/api/");
        if (p == null) {
            if (api) {
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                resp.setContentType("application/json; charset=UTF-8");
                resp.getWriter().write("{\"error\":\"unauthenticated\"}");
            } else {
                resp.sendRedirect(req.getContextPath() + "/login");
            }
            return;
        }

        if (api && !"GET".equals(req.getMethod()) && !"HEAD".equals(req.getMethod())
                && !"XMLHttpRequest".equals(req.getHeader("X-Requested-With"))) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.setContentType("application/json; charset=UTF-8");
            resp.getWriter().write("{\"error\":\"missing X-Requested-With header\"}");
            return;
        }

        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("X-Frame-Options", "SAMEORIGIN");
        chain.doFilter(request, response);
    }

    private static boolean isPublic(String path) {
        return path.equals("/login") || path.equals("/logout") || path.equals("/error")
                || path.startsWith("/static/") || path.equals("/favicon.ico");
    }

    @Override
    public void destroy() {
    }
}
