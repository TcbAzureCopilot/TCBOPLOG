package com.machineroom.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.machineroom.auth.SessionUtil;
import com.machineroom.auth.UserPrincipal;
import com.machineroom.config.AppConfig;
import com.machineroom.dao.AuditDao;
import com.machineroom.model.DailyLog;
import com.machineroom.model.ReportStatus;
import com.machineroom.model.Shift;
import com.machineroom.service.DefService;
import com.machineroom.service.HistoryService;
import com.machineroom.service.LegacyImporter;
import com.machineroom.service.LogService;
import com.machineroom.service.LogicalDate;
import com.machineroom.service.Permissions;
import com.machineroom.service.ServiceException;

/** JSON API router for {@code /api/*} (see docs/API.md). */
public class ApiServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(ApiServlet.class.getName());
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private static final String DATE = "(\\d{4}-\\d{2}-\\d{2})";
    private static final String ID = "([^/]+)";

    /** One route: HTTP method + path pattern + handler. */
    private interface Handler {
        JsonElement handle(Ctx ctx) throws Exception;
    }

    private static final class Route {
        final String method;
        final Pattern pattern;
        final Handler handler;

        Route(String method, String regex, Handler handler) {
            this.method = method;
            this.pattern = Pattern.compile("^" + regex + "$");
            this.handler = handler;
        }
    }

    /** Per-request context handed to handlers. */
    static final class Ctx {
        final HttpServletRequest req;
        final HttpServletResponse resp;
        final UserPrincipal user;
        final Matcher m;
        JsonObject body;
        boolean handled;      // handler wrote the response itself (downloads)

        Ctx(HttpServletRequest req, HttpServletResponse resp, UserPrincipal user, Matcher m) {
            this.req = req;
            this.resp = resp;
            this.user = user;
            this.m = m;
        }

        String p(int i) {
            try {
                return URLDecoder.decode(m.group(i), "UTF-8");
            } catch (IOException e) {
                return m.group(i);
            }
        }

        LocalDate date(int i) {
            return LogicalDate.parse(m.group(i));
        }

        JsonObject body() throws IOException {
            if (body == null) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = req.getReader()) {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = r.read(buf)) > 0) {
                        sb.append(buf, 0, n);
                        if (sb.length() > 20_000_000) throw ServiceException.badRequest("內容過大");
                    }
                }
                String s = sb.toString().trim();
                if (s.isEmpty()) {
                    body = new JsonObject();
                } else {
                    try {
                        JsonElement e = JsonParser.parseString(s);
                        body = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
                    } catch (JsonParseException e) {
                        throw ServiceException.badRequest("JSON 格式錯誤");
                    }
                }
            }
            return body;
        }

        String bs(String key) throws IOException {
            return LogJson.str(body(), key);
        }

        String bse(String key) throws IOException {
            return LogJson.strOrEmpty(body(), key);
        }
    }

    private final List<Route> routes = new ArrayList<>();

    @Override
    public void init() {
        // ---- identity
        add("GET", "/me", ctx -> meJson(ctx.user));
        add("POST", "/me/shift", ctx -> {
            if (!ctx.user.isOperator()) throw ServiceException.forbidden("只有經辦可以切換班別");
            Shift s = Shift.fromKey(ctx.bs("shift"));
            if (s == null) throw ServiceException.badRequest("班別不正確");
            ctx.user.setShift(s);
            AuditDao.record(ctx.user, ctx.user.getClientIp(), "SHIFT", null, s.key());
            return meJson(ctx.user);
        });

        // ---- dates / log
        add("GET", "/dates", ctx -> {
            String cur = ctx.req.getParameter("current");
            LocalDate current = cur == null || cur.isEmpty() ? null : LogicalDate.parse(cur);
            return LogJson.datesJson(HistoryService.dates(ctx.user, current), LogicalDate.today());
        });
        add("GET", "/log/" + DATE, ctx -> {
            DailyLog log = LogService.getOrCreate(ctx.date(1), ctx.user);
            String since = ctx.req.getParameter("ifUpdatedAfter");
            if (since != null && !since.isEmpty() && since.equals(LogicalDate.fmtMs(log.updatedAt))) {
                JsonObject o = new JsonObject();
                o.addProperty("changed", false);
                return o;
            }
            return logJson(log, ctx.user);
        });
        add("POST", "/log/create", ctx -> logJson(LogService.create(LogicalDate.parse(ctx.bs("date")), ctx.user), ctx.user));

        // header
        add("POST", "/log/" + DATE + "/daytype", ctx -> logJson(LogService.changeDayType(ctx.date(1), ctx.user, ctx.bs("dayType"), ctx.bs("reason")), ctx.user));
        add("POST", "/log/" + DATE + "/boot", ctx -> logJson(LogService.setBoot(ctx.date(1), ctx.user, ctx.bs("bootUser"), ctx.bs("bootTime")), ctx.user));
        add("POST", "/log/" + DATE + "/boot/sign", ctx -> logJson(LogService.signBoot(ctx.date(1), ctx.user), ctx.user));
        add("POST", "/log/" + DATE + "/boot/review", ctx -> logJson(LogService.reviewBoot(ctx.date(1), ctx.user), ctx.user));

        // tasks
        add("POST", "/log/" + DATE + "/task/" + ID + "/field", ctx -> logJson(LogService.setTaskField(ctx.date(1), ctx.user, ctx.p(2), ctx.bs("field"), ctx.bse("value")), ctx.user));
        add("POST", "/log/" + DATE + "/task/" + ID + "/done", ctx -> logJson(LogService.setTaskDone(ctx.date(1), ctx.user, ctx.p(2), LogJson.bool(ctx.body(), "done", true)), ctx.user));
        add("POST", "/log/" + DATE + "/task/" + ID + "/abnormal", ctx -> logJson(LogService.setTaskAbnormal(ctx.date(1), ctx.user, ctx.p(2), LogJson.bool(ctx.body(), "abnormal", true)), ctx.user));
        add("POST", "/log/" + DATE + "/task/" + ID + "/review", ctx -> logJson(LogService.reviewTask(ctx.date(1), ctx.user, ctx.p(2)), ctx.user));
        add("POST", "/log/" + DATE + "/task/" + ID + "/handover", ctx -> logJson(LogService.handoverTask(ctx.date(1), ctx.user, ctx.p(2)), ctx.user));
        add("POST", "/log/" + DATE + "/task/" + ID + "/force", ctx -> logJson(LogService.forceTask(ctx.date(1), ctx.user, ctx.p(2), ctx.bs("reason")), ctx.user));
        add("POST", "/log/" + DATE + "/task/" + ID + "/quick", ctx -> logJson(LogService.quickTime(ctx.date(1), ctx.user, ctx.p(2)), ctx.user));

        // equipment
        add("POST", "/log/" + DATE + "/equip/" + ID + "/field", ctx -> logJson(LogService.setEquipField(ctx.date(1), ctx.user, ctx.p(2), ctx.bs("field"), ctx.bse("value")), ctx.user));
        add("POST", "/log/" + DATE + "/equip/" + ID + "/sign", ctx -> logJson(LogService.signEquip(ctx.date(1), ctx.user, ctx.p(2)), ctx.user));
        add("POST", "/log/" + DATE + "/equip/" + ID + "/unsign", ctx -> logJson(LogService.unsignEquip(ctx.date(1), ctx.user, ctx.p(2)), ctx.user));
        add("POST", "/log/" + DATE + "/equip/" + ID + "/review", ctx -> logJson(LogService.reviewEquip(ctx.date(1), ctx.user, ctx.p(2)), ctx.user));
        add("POST", "/log/" + DATE + "/equip/" + ID + "/unreview", ctx -> logJson(LogService.unreviewEquip(ctx.date(1), ctx.user, ctx.p(2)), ctx.user));
        add("POST", "/log/" + DATE + "/equip/" + ID + "/toggle", ctx -> logJson(LogService.toggleEquip(ctx.date(1), ctx.user, ctx.p(2), ctx.bs("reason")), ctx.user));

        // checks
        add("POST", "/log/" + DATE + "/check/" + ID + "/" + ID + "/field", ctx -> logJson(LogService.setCheckField(ctx.date(1), ctx.user, ctx.p(2), ctx.p(3), ctx.bs("field"), ctx.bs("key"), ctx.bse("value")), ctx.user));
        add("POST", "/log/" + DATE + "/check/" + ID + "/" + ID + "/sign", ctx -> logJson(LogService.signCheck(ctx.date(1), ctx.user, ctx.p(2), ctx.p(3)), ctx.user));
        add("POST", "/log/" + DATE + "/check/" + ID + "/" + ID + "/unsign", ctx -> logJson(LogService.unsignCheck(ctx.date(1), ctx.user, ctx.p(2), ctx.p(3)), ctx.user));
        add("POST", "/log/" + DATE + "/check/" + ID + "/" + ID + "/review", ctx -> logJson(LogService.reviewCheck(ctx.date(1), ctx.user, ctx.p(2), ctx.p(3)), ctx.user));
        add("POST", "/log/" + DATE + "/check/" + ID + "/" + ID + "/unreview", ctx -> logJson(LogService.unreviewCheck(ctx.date(1), ctx.user, ctx.p(2), ctx.p(3)), ctx.user));

        // job error / records
        add("POST", "/log/" + DATE + "/joberror", ctx -> logJson(LogService.setJobError(ctx.date(1), ctx.user, ctx.bs("shift"), LogJson.intVal(ctx.body(), "value", 0)), ctx.user));
        add("POST", "/log/" + DATE + "/record", ctx -> logJson(LogService.addRecord(ctx.date(1), ctx.user, LogJson.recordFrom(ctx.body(), true)), ctx.user));
        add("PUT", "/log/" + DATE + "/record/(\\d+)", ctx -> logJson(LogService.updateRecord(ctx.date(1), ctx.user, Integer.parseInt(ctx.m.group(2)), LogJson.recordFrom(ctx.body(), false)), ctx.user));
        add("DELETE", "/log/" + DATE + "/record/(\\d+)", ctx -> logJson(LogService.deleteRecord(ctx.date(1), ctx.user, Integer.parseInt(ctx.m.group(2))), ctx.user));

        // workflow
        add("POST", "/log/" + DATE + "/submit-shift", ctx -> logJson(LogService.submitShift(ctx.date(1), ctx.user, ctx.bs("shift")), ctx.user));
        add("POST", "/log/" + DATE + "/submit-all", ctx -> logJson(LogService.submitAll(ctx.date(1), ctx.user), ctx.user));
        add("POST", "/log/" + DATE + "/recall", ctx -> logJson(LogService.recall(ctx.date(1), ctx.user), ctx.user));
        add("POST", "/log/" + DATE + "/approve", ctx -> logJson(LogService.approve(ctx.date(1), ctx.user, ctx.bs("comment")), ctx.user));
        add("POST", "/log/" + DATE + "/reject", ctx -> logJson(LogService.reject(ctx.date(1), ctx.user, ctx.bs("reason")), ctx.user));
        add("POST", "/log/" + DATE + "/unlock", ctx -> logJson(LogService.unlock(ctx.date(1), ctx.user, ctx.bs("reason")), ctx.user));

        // ---- definitions
        add("GET", "/defs", ctx -> LogJson.defsJson(DefService.all()));
        add("POST", "/defs/task", ctx -> LogJson.defsJson(DefService.createTask(ctx.user, LogJson.taskDefFrom(ctx.body()))));
        add("PUT", "/defs/task/" + ID, ctx -> LogJson.defsJson(DefService.updateTask(ctx.user, ctx.p(1), LogJson.taskDefFrom(ctx.body()))));
        add("DELETE", "/defs/task/" + ID, ctx -> LogJson.defsJson(DefService.deleteTask(ctx.user, ctx.p(1))));
        add("POST", "/defs/equip", ctx -> LogJson.defsJson(DefService.createEquip(ctx.user, LogJson.equipDefFrom(ctx.body()))));
        add("PUT", "/defs/equip/" + ID, ctx -> LogJson.defsJson(DefService.updateEquip(ctx.user, ctx.p(1), LogJson.equipDefFrom(ctx.body()))));
        add("POST", "/defs/equip/" + ID + "/toggle", ctx -> LogJson.defsJson(DefService.toggleEquip(ctx.user, ctx.p(1))));
        add("POST", "/defs/check", ctx -> LogJson.defsJson(DefService.createCheck(ctx.user, LogJson.checkDefFrom(ctx.body()))));
        add("PUT", "/defs/check/" + ID, ctx -> LogJson.defsJson(DefService.updateCheck(ctx.user, ctx.p(1), LogJson.checkDefFrom(ctx.body()))));
        add("POST", "/defs/check/" + ID + "/toggle", ctx -> LogJson.defsJson(DefService.toggleCheck(ctx.user, ctx.p(1))));
        add("POST", "/defs/apply-today", ctx -> logJson(DefService.applyToday(ctx.user, ctx.bs("kind")), ctx.user));

        // ---- history / stats / export / import
        add("GET", "/history", ctx -> {
            Integer year = intParam(ctx.req, "year"), month = intParam(ctx.req, "month");
            ReportStatus st = ReportStatus.fromKey(ctx.req.getParameter("status"));
            return LogJson.historyJson(HistoryService.history(year, month, st));
        });
        add("GET", "/stats", ctx -> LogJson.statsJson(HistoryService.stats()));
        add("GET", "/export", ctx -> {
            List<LocalDate> dates = parseDates(ctx.req.getParameter("dates"));
            if (dates.isEmpty()) throw ServiceException.badRequest("請指定 dates");
            List<DailyLog> logs = HistoryService.export(dates);
            JsonObject out = new JsonObject();
            out.addProperty("exportedAt", LogicalDate.fmt(LocalDateTime.now()));
            out.addProperty("version", "3.0");
            out.addProperty("count", logs.size());
            JsonObject reports = new JsonObject();
            for (DailyLog l : logs) reports.add(l.date.toString(), LogJson.toJson(l, null, 0));
            out.add("reports", reports);
            String name = "機房日誌_" + dates.get(0) + "_to_" + dates.get(dates.size() - 1) + ".json";
            ctx.resp.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(name, "UTF-8").replace("+", "%20"));
            AuditDao.record(ctx.user, ctx.user.getClientIp(), "EXPORT", null, dates.toString());
            return out;
        });
        add("POST", "/import", ctx -> {
            JsonObject root = ctx.body();
            List<DailyLog> logs = new ArrayList<>();
            if (LegacyImporter.isLegacy(root)) {
                logs.addAll(LegacyImporter.convert(root));
            } else {
                JsonElement reports = root.get("reports");
                if (reports == null || !reports.isJsonObject()) throw ServiceException.badRequest("檔案格式錯誤：缺少 reports");
                for (java.util.Map.Entry<String, JsonElement> en : reports.getAsJsonObject().entrySet()) {
                    if (en.getValue().isJsonObject()) logs.add(LogJson.fromJson(en.getValue().getAsJsonObject()));
                }
            }
            HistoryService.ImportResult r = HistoryService.importLogs(ctx.user, logs);
            JsonObject o = new JsonObject();
            JsonArray created = new JsonArray(), skipped = new JsonArray();
            for (LocalDate d : r.created) created.add(d.toString());
            for (LocalDate d : r.skipped) skipped.add(d.toString());
            o.add("created", created);
            o.add("skipped", skipped);
            return o;
        });
    }

    private void add(String method, String regex, Handler h) {
        routes.add(new Route(method, regex, h));
    }

    // ------------------------------------------------------------------ dispatch

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
        String method = req.getMethod();
        UserPrincipal user = SessionUtil.principal(req);
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        try {
            if (user == null) throw new ServiceException(401, "unauthenticated");
            boolean pathMatched = false;
            for (Route r : routes) {
                Matcher m = r.pattern.matcher(path);
                if (!m.matches()) continue;
                pathMatched = true;
                if (!r.method.equals(method)) continue;
                Ctx ctx = new Ctx(req, resp, user, m);
                JsonElement out = r.handler.handle(ctx);
                if (!ctx.handled) {
                    resp.setStatus(200);
                    resp.getWriter().write(GSON.toJson(out));
                }
                return;
            }
            throw new ServiceException(pathMatched ? 405 : 404, pathMatched ? "method not allowed" : "not found: " + path);
        } catch (ServiceException e) {
            writeError(resp, e.getStatus(), e.getMessage(), e.getDetails());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "API failure " + method + " " + path, e);
            writeError(resp, 500, "系統錯誤：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), null);
        }
    }

    private static void writeError(HttpServletResponse resp, int status, String msg, List<String> details) throws IOException {
        resp.setStatus(status);
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        if (details != null && !details.isEmpty()) {
            JsonArray a = new JsonArray();
            for (String d : details) a.add(d);
            o.add("details", a);
        }
        resp.getWriter().write(GSON.toJson(o));
    }

    // ------------------------------------------------------------------ helpers

    static JsonObject logJson(DailyLog log, UserPrincipal user) {
        Permissions perm = new Permissions(log, user, LogicalDate.today(), LocalDateTime.now());
        int pending = user.isReviewer() ? HistoryService.pendingCount(user) : 0;
        return LogJson.toJson(log, perm, pending);
    }

    static JsonObject meJson(UserPrincipal u) {
        AppConfig cfg = AppConfig.get();
        JsonObject o = new JsonObject();
        o.addProperty("user", u.getUsername());
        o.addProperty("displayName", u.getDisplayName());
        o.addProperty("role", u.getActiveRole().name());
        JsonArray roles = new JsonArray();
        for (com.machineroom.auth.Role r : u.getRoles()) roles.add(r.name());
        o.add("roles", roles);
        o.addProperty("isAdmin", u.isAdmin());
        o.addProperty("shift", u.getShift() == null ? null : u.getShift().key());
        o.addProperty("todayLogical", LogicalDate.today().toString());
        o.addProperty("serverTime", LogicalDate.fmt(LocalDateTime.now()));
        o.addProperty("logicalDayCutoffHour", cfg.logicalDayCutoffHour());
        o.addProperty("pollSeconds", cfg.pollSeconds());
        o.addProperty("appTitle", cfg.appTitle());
        o.addProperty("orgName", cfg.orgName());
        LocalDate pending = LogService.pendingIntegration(u);
        o.addProperty("pendingIntegration", pending == null ? null : pending.toString());
        return o;
    }

    private static Integer intParam(HttpServletRequest req, String name) {
        String v = req.getParameter(name);
        if (v == null || v.trim().isEmpty()) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw ServiceException.badRequest(name + " 須為整數");
        }
    }

    static List<LocalDate> parseDates(String csv) {
        List<LocalDate> out = new ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split(",")) {
            if (!s.trim().isEmpty()) out.add(LogicalDate.parse(s));
        }
        java.util.Collections.sort(out);
        return out;
    }
}
