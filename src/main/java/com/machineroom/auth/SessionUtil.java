package com.machineroom.auth;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/** Session helpers. */
public final class SessionUtil {

    public static final String PRINCIPAL = "com.machineroom.principal";
    public static final String CLIENT_IP = "com.machineroom.clientIp";

    private SessionUtil() {
    }

    public static UserPrincipal principal(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return null;
        Object o = s.getAttribute(PRINCIPAL);
        return o instanceof UserPrincipal ? (UserPrincipal) o : null;
    }

    /** Principal or {@code null}; callers that require login use {@link #require(HttpServletRequest)}. */
    public static UserPrincipal require(HttpServletRequest req) {
        UserPrincipal p = principal(req);
        if (p == null) throw new IllegalStateException("unauthenticated");
        return p;
    }

    public static String clientIp(HttpServletRequest req) {
        Object ip = req.getAttribute(CLIENT_IP);
        return ip != null ? ip.toString() : req.getRemoteAddr();
    }
}
