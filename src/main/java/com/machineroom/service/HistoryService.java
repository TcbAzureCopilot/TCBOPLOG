package com.machineroom.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.machineroom.auth.UserPrincipal;
import com.machineroom.dao.AuditDao;
import com.machineroom.dao.DailyLogDao;
import com.machineroom.dao.DailyLogDao.DateSummary;
import com.machineroom.dao.DailyLogDao.HistoryRow;
import com.machineroom.dao.DailyLogDao.Stats;
import com.machineroom.db.Db;
import com.machineroom.db.Jdbc;
import com.machineroom.model.DailyLog;
import com.machineroom.model.ReportStatus;

/** Date lists, history table, statistics, export / import. */
public final class HistoryService {

    private HistoryService() {
    }

    /**
     * Dates for the selector, filtered by role like the original UI:
     * operators see drafts + today, deputies see submitted, chiefs see reviewed.
     * {@code current} (the date the user is looking at) is always included.
     */
    public static List<DateSummary> dates(UserPrincipal p, LocalDate current) {
        LocalDate today = LogicalDate.today();
        if (p.isOperator()) LogService.getOrCreate(today, p);       // today always exists for operators
        List<DateSummary> all = Db.tx(c -> DailyLogDao.dateSummaries(c));
        List<DateSummary> out = new ArrayList<>();
        for (DateSummary s : all) {
            boolean keep;
            if (p.isOperator()) keep = s.date.equals(today) || s.status == ReportStatus.DRAFT;
            else if (p.isDeputy()) keep = s.status == ReportStatus.SUBMITTED;
            else keep = s.status == ReportStatus.REVIEWED;
            if (keep || s.date.equals(current)) out.add(s);
        }
        return out;
    }

    /** Number of logs waiting for this reviewer (marquee). */
    public static int pendingCount(UserPrincipal p) {
        if (!p.isReviewer()) return 0;
        List<DateSummary> all = Db.tx(c -> DailyLogDao.dateSummaries(c));
        int n = 0;
        for (DateSummary s : all) {
            if (s.status == ReportStatus.SUBMITTED || s.status == ReportStatus.REVIEWED) n++;
        }
        return n;
    }

    public static List<HistoryRow> history(final Integer year, final Integer month, final ReportStatus status) {
        return Db.tx(c -> DailyLogDao.history(c, year, month, status));
    }

    public static Stats stats() {
        return Db.tx(c -> DailyLogDao.stats(c));
    }

    public static List<DailyLog> export(final List<LocalDate> dates) {
        return Db.tx(c -> {
            List<DailyLog> out = new ArrayList<>();
            for (LocalDate d : dates) {
                DailyLog l = DailyLogDao.load(c, d, false);
                if (l != null) out.add(l);
            }
            return out;
        });
    }

    /** Result of an import: which dates were created and which already existed. */
    public static final class ImportResult {
        public final List<LocalDate> created = new ArrayList<>();
        public final List<LocalDate> skipped = new ArrayList<>();
    }

    /** Inserts logs whose date does not exist yet; existing dates are never overwritten. */
    public static ImportResult importLogs(final UserPrincipal p, final List<DailyLog> logs) {
        if (!p.isAdmin()) throw ServiceException.forbidden("只有管理者可以匯入");
        return Db.tx(c -> {
            ImportResult r = new ImportResult();
            for (DailyLog l : logs) {
                if (DailyLogDao.exists(c, l.date)) {
                    r.skipped.add(l.date);
                    continue;
                }
                l.createdAt = Jdbc.now();
                l.createdBy = p.getUsername();
                l.updatedAt = l.createdAt;
                l.updatedBy = p.getUsername();
                DailyLogDao.insert(c, l);
                r.created.add(l.date);
            }
            AuditDao.write(c, p.getUsername(), p.getActiveRole().name(), p.getClientIp(), "IMPORT", null,
                    "created=" + r.created + " skipped=" + r.skipped);
            return r;
        });
    }
}
