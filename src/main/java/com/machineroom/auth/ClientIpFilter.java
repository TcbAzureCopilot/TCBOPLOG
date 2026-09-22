package com.machineroom.auth;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.machineroom.config.AppConfig;

/**
 * Determines the client IP (optionally honouring X-Forwarded-For behind IHS / a reverse proxy)
 * and enforces the optional {@code security.allowedClientIps} allow-list.
 */
public class ClientIpFilter implements Filter {

    private static final Logger LOG = Logger.getLogger(ClientIpFilter.class.getName());

    private volatile CidrMatcher matcher;
    private volatile List<String> matcherSource;

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        AppConfig cfg = AppConfig.get();

        String ip = req.getRemoteAddr();
        if (cfg.trustProxyHeader()) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.trim().isEmpty()) {
                ip = xff.split(",")[0].trim();
            }
        }
        req.setAttribute(SessionUtil.CLIENT_IP, ip);

        List<String> allowed = cfg.allowedClientIps();
        if (!allowed.isEmpty()) {
            CidrMatcher m = matcher;
            if (m == null || !allowed.equals(matcherSource)) {
                try {
                    m = new CidrMatcher(allowed);
                } catch (IllegalArgumentException e) {
                    LOG.severe("security.allowedClientIps is invalid, denying all: " + e.getMessage());
                    m = new CidrMatcher(java.util.Collections.singletonList("255.255.255.255/32"));
                }
                matcher = m;
                matcherSource = allowed;
            }
            if (!m.matches(ip)) {
                LOG.warning("Rejected client IP " + ip + " for " + req.getRequestURI());
                resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                resp.setContentType("text/plain; charset=UTF-8");
                resp.getWriter().write("403 Forbidden: 此 IP (" + ip + ") 不允許使用本系統");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
    }
}
