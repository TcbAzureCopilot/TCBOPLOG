package com.machineroom.auth;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.machineroom.dao.AuditDao;

/** Invalidates the session. Accepts GET and POST. */
public class LogoutServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        UserPrincipal p = SessionUtil.principal(req);
        if (p != null) {
            AuditDao.record(p, SessionUtil.clientIp(req), "LOGOUT", null, null);
        }
        HttpSession s = req.getSession(false);
        if (s != null) s.invalidate();
        // ?select=1 keeps the form visible in bypass/auto-login mode so another account can be chosen
        resp.sendRedirect(req.getContextPath() + "/login?select=1");
    }
}
