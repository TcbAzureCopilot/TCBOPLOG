package com.machineroom.web;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.machineroom.auth.SessionUtil;
import com.machineroom.auth.UserPrincipal;
import com.machineroom.dao.AuditDao;
import com.machineroom.model.DailyLog;
import com.machineroom.pdf.PdfService;
import com.machineroom.service.ServiceException;

/** {@code GET /pdf/{date}} or {@code GET /pdf?dates=a,b,c} → PDF download. */
public class PdfServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        UserPrincipal user = SessionUtil.require(req);
        List<DailyLog> logs;
        try {
            logs = PrintServlet.load(req);
        } catch (ServiceException e) {
            resp.sendError(e.getStatus(), e.getMessage());
            return;
        }
        byte[] pdf = PdfService.render(logs);
        String name = logs.size() == 1
                ? "機房操作日誌_" + logs.get(0).date + ".pdf"
                : "機房操作日誌_" + logs.get(0).date + "_to_" + logs.get(logs.size() - 1).date + ".pdf";
        String encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        AuditDao.record(user, SessionUtil.clientIp(req), "PDF", logs.size() == 1 ? logs.get(0).date : null, PrintServlet.dates(logs));
        resp.reset();                                   // drop the text charset added by EncodingFilter
        resp.setContentType("application/pdf");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Content-Disposition", "attachment; filename=\"log.pdf\"; filename*=UTF-8''" + encoded);
        resp.setContentLength(pdf.length);
        resp.getOutputStream().write(pdf);
        resp.getOutputStream().flush();
    }
}
