package com.machineroom.auth;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.machineroom.config.AppConfig;
import com.machineroom.dao.AuditDao;
import com.machineroom.model.Shift;
import com.machineroom.web.Html;

/** GET: login page. POST: authenticate against AD (or dev list), create a fresh session. */
public class LoginServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(LoginServlet.class.getName());

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (SessionUtil.principal(req) != null) {
            redirect(resp, contextUrl(req, "/"));
            return;
        }
        AppConfig cfg = AppConfig.get();
        // bypass mode with an auto-login account: skip the form unless the user asked to choose (?select=1)
        if (cfg.isBypassAuth() && !cfg.bypassAutoLogin().isEmpty() && req.getParameter("select") == null
                && req.getParameter("error") == null) {
            try {
                establish(req, AuthService.login(cfg.bypassAutoLogin(), "", null, null, SessionUtil.clientIp(req)));
                redirect(resp, contextUrl(req, "/"));
                return;
            } catch (AuthException e) {
                req.setAttribute("error", Html.esc(e.getMessage()));
            }
        }
        req.setAttribute("appTitle", cfg.appTitle());
        req.setAttribute("orgName", cfg.orgName());
        req.setAttribute("devMode", cfg.isDevAuth());
        req.setAttribute("bypassMode", cfg.isBypassAuth());
        String err = req.getParameter("error");
        req.setAttribute("error", err == null ? null : Html.esc(err));
        resp.setHeader("Cache-Control", "no-store");
        req.getRequestDispatcher("/WEB-INF/views/login.jsp").forward(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String username = req.getParameter("username");
        String password = req.getParameter("password");
        Role role = Role.fromString(req.getParameter("role"));
        Shift shift = Shift.fromKey(req.getParameter("shift"));
        String ip = SessionUtil.clientIp(req);

        try {
            UserPrincipal p = AuthService.login(username, password, role, shift, ip);
            establish(req, p);
            redirect(resp, contextUrl(req, "/"));
        } catch (AuthException e) {
            LOG.warning("Login failed for '" + username + "' from " + ip + ": " + e.getMessage());
            AuditDao.record(username == null ? "?" : username, null, ip, "LOGIN_FAIL", null, e.getMessage());
            redirect(resp, contextUrl(req, "/login?error=" + URLEncoder.encode(e.getMessage(), "UTF-8")));
        }
    }

    private static void redirect(HttpServletResponse resp, String location) {
        resp.setStatus(HttpServletResponse.SC_FOUND);
        resp.setHeader("Location", location);
    }

    private static String contextUrl(HttpServletRequest req, String path) {
        String contextPath = req.getContextPath();
        if (contextPath == null || contextPath.length() == 0 || "/".equals(contextPath)) {
            return path;
        }
        return contextPath + path;
    }

    /** Replaces any existing session (fixation protection), stores the principal and audits the login. */
    private static void establish(HttpServletRequest req, UserPrincipal p) {
        HttpSession old = req.getSession(false);
        if (old != null) old.invalidate();
        HttpSession s = req.getSession(true);
        s.setAttribute(SessionUtil.PRINCIPAL, p);
        String ip = SessionUtil.clientIp(req);
        AuditDao.record(p, ip, "LOGIN", null, "role=" + p.getActiveRole() + " shift=" + p.getShift()
                + (AppConfig.get().isBypassAuth() ? " (bypass)" : ""));
        LOG.info("Login OK: " + p + " from " + ip);
    }
}
