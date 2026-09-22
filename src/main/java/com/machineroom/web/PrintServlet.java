package com.machineroom.web;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.machineroom.auth.SessionUtil;
import com.machineroom.auth.UserPrincipal;
import com.machineroom.dao.AuditDao;
import com.machineroom.model.DailyLog;
import com.machineroom.pdf.PrintHtml;
import com.machineroom.service.HistoryService;
import com.machineroom.service.LogicalDate;
import com.machineroom.service.ServiceException;

/** {@code GET /print/{date}} or {@code GET /print?dates=a,b,c} → printable HTML. */
public class PrintServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        UserPrincipal user = SessionUtil.require(req);
        List<DailyLog> logs;
        try {
            logs = load(req);
        } catch (ServiceException e) {
            resp.sendError(e.getStatus(), e.getMessage());
            return;
        }
        AuditDao.record(user, SessionUtil.clientIp(req), "PRINT", logs.size() == 1 ? logs.get(0).date : null, dates(logs));
        resp.setContentType("text/html; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(PrintHtml.render(logs));
    }

    /** Shared by PrintServlet and PdfServlet: resolves the requested dates to logs (404 when none exist). */
    static List<DailyLog> load(HttpServletRequest req) {
        List<LocalDate> dates;
        String path = req.getPathInfo();
        if (path != null && path.length() > 1) {
            dates = java.util.Collections.singletonList(LogicalDate.parse(path.substring(1)));
        } else {
            dates = ApiServlet.parseDates(req.getParameter("dates"));
        }
        if (dates.isEmpty()) throw ServiceException.badRequest("請指定日期");
        if (dates.size() > 366) throw ServiceException.badRequest("一次最多 366 天");
        List<DailyLog> logs = HistoryService.export(dates);
        if (logs.isEmpty()) throw ServiceException.notFound("找不到日誌");
        return logs;
    }

    static String dates(List<DailyLog> logs) {
        StringBuilder sb = new StringBuilder();
        for (DailyLog l : logs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(l.date);
        }
        return sb.toString();
    }
}
