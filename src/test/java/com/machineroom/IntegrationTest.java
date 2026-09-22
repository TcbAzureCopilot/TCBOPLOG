package com.machineroom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.machineroom.auth.Role;
import com.machineroom.auth.UserPrincipal;
import com.machineroom.dao.DailyLogDao;
import com.machineroom.db.Db;
import com.machineroom.db.SchemaInitializer;
import com.machineroom.model.DailyLog;
import com.machineroom.model.EquipItem;
import com.machineroom.model.ImportantRecord;
import com.machineroom.model.ReportStatus;
import com.machineroom.model.Shift;
import com.machineroom.model.Task;
import com.machineroom.pdf.PdfService;
import com.machineroom.pdf.PrintHtml;
import com.machineroom.service.DefService;
import com.machineroom.service.HistoryService;
import com.machineroom.service.LegacyImporter;
import com.machineroom.service.LogService;
import com.machineroom.service.LogicalDate;
import com.machineroom.service.ServiceException;
import com.machineroom.web.LogJson;

/**
 * End-to-end through DAO → service → JSON → PDF on an in-memory H2 database in DB2 mode,
 * using the same schema / seed scripts that are run on DB2.
 */
public class IntegrationTest {

    private static final UserPrincipal OP1 = op("op001", Shift.NIGHT);
    private static final UserPrincipal OP2 = op("op002", Shift.NIGHT);
    private static final UserPrincipal DEPUTY = new UserPrincipal("dep001", "陳副科", EnumSet.of(Role.DEPUTY), true, Role.DEPUTY, null, "127.0.0.1");
    private static final UserPrincipal CHIEF = new UserPrincipal("chief001", "林科長", EnumSet.of(Role.CHIEF), true, Role.CHIEF, null, "127.0.0.1");

    private static UserPrincipal op(String name, Shift s) {
        return new UserPrincipal(name, name, EnumSet.of(Role.OPERATOR), false, Role.OPERATOR, s, "127.0.0.1");
    }

    @BeforeClass
    public static void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mrlog;MODE=DB2;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        Db.setDataSource(ds);
        try (Connection c = Db.open()) {
            SchemaInitializer.execScript(c, "db/01_schema_db2.sql");
            SchemaInitializer.execScript(c, "db/02_seed_db2.sql");
            c.commit();
        }
    }

    @Test
    public void fullOperatorFlow() throws Exception {
        LocalDate today = LogicalDate.today();
        DailyLog log = LogService.getOrCreate(today, OP1);
        assertEquals(32, log.tasks.size());
        assertEquals(6, log.equip.size());                 // creditCard is disabled in the seed
        assertEquals(9, log.checks.get(Shift.DAY).size());
        assertEquals(ReportStatus.DRAFT, log.status);

        // night task A6: fill, done (=sign), colleague reviews, author may not review
        log = LogService.setTaskField(today, OP1, "A6", "startTime", "04:10");
        log = LogService.setTaskDone(today, OP1, "A6", true);
        Task a6 = log.task("A6");
        assertTrue(a6.done);
        assertEquals("op001", a6.opSign.user);
        try {
            LogService.reviewTask(today, OP1, "A6");
            fail("author must not review own task");
        } catch (ServiceException e) {
            assertEquals(403, e.getStatus());
        }
        log = LogService.reviewTask(today, OP2, "A6");
        assertEquals("op002", log.task("A6").reviewerSign.user);

        // day-shift operator cannot touch a night task
        try {
            LogService.setTaskField(today, op("op003", Shift.DAY), "A7", "startTime", "05:00");
            fail("wrong shift");
        } catch (ServiceException e) {
            assertEquals(403, e.getStatus());
        }

        // equipment dms3 (night): time + count → sign → review
        log = LogService.setEquipField(today, OP1, "dms3", "time", "02:00");
        try {
            LogService.signEquip(today, OP1, "dms3");
            fail("not filled yet");
        } catch (ServiceException e) {
            assertEquals(403, e.getStatus());
        }
        log = LogService.setEquipField(today, OP1, "dms3", "count", "12");
        log = LogService.signEquip(today, OP1, "dms3");
        log = LogService.reviewEquip(today, OP2, "dms3");
        EquipItem dms3 = log.equip("dms3");
        assertEquals("op001", dms3.opSign.user);
        assertEquals("op002", dms3.reviewerSign.user);

        // shift check portal: sub values + time → sign
        for (String k : Arrays.asList("網銀", "WWW", "金控官網", "EATM", "COEIP")) {
            log = LogService.setCheckField(today, OP1, "night", "ngt_portal", "value", k, "正常");
        }
        log = LogService.setCheckField(today, OP1, "night", "ngt_portal", "time", null, "01:00");
        log = LogService.signCheck(today, OP1, "night", "ngt_portal");
        assertNotNull(log.check(Shift.NIGHT, "ngt_portal").opSign);

        // job error + record
        log = LogService.setJobError(today, OP1, "night", 2);
        ImportantRecord r = new ImportantRecord();
        r.time = "03:00";
        r.taskCode = "JOB ERROR (大夜班)";
        r.description = "JOB ERROR 2 支";
        r.source = ImportantRecord.SRC_JOBERROR;
        r.shift = Shift.NIGHT;
        log = LogService.addRecord(today, OP1, r);
        assertEquals(1, log.records.size());
        int id = log.records.get(0).id;
        assertTrue(id > 0);
        ImportantRecord patch = new ImportantRecord();
        patch.time = null; patch.taskCode = null; patch.description = null; patch.notifySP = null;
        patch.notifyAP = null; patch.ticket = null;
        patch.recoverTime = "03:30";
        log = LogService.updateRecord(today, OP1, id, patch);
        assertEquals("03:30", log.record(id).recoverTime);

        // submitting the night shift fails while other night tasks are undone
        try {
            LogService.submitShift(today, OP1, "night");
            fail("validation");
        } catch (ServiceException e) {
            assertEquals(400, e.getStatus());
            assertFalse(e.getDetails().isEmpty());
        }

        // deputy cannot approve a draft
        try {
            LogService.approve(today, DEPUTY, "");
            fail("draft");
        } catch (ServiceException e) {
            assertEquals(403, e.getStatus());
        }

        // JSON with permissions renders and reloads
        JsonObject j = com.machineroom.web.ApiServletAccess.logJson(log, OP1);
        assertEquals(today.toString(), j.get("date").getAsString());
        assertTrue(j.getAsJsonObject("perm").get("canSubmitShift").getAsBoolean());
        assertEquals("fill", j.getAsJsonObject("perm").get("mode").getAsString());

        // export → import round trip on another date
        JsonObject exported = LogJson.toJson(log, null, 0);
        exported.addProperty("date", "2020-01-02");
        DailyLog copy = LogJson.fromJson(exported);
        assertEquals(32, copy.tasks.size());
        assertEquals("04:10", copy.task("A6").startTime);
        HistoryService.ImportResult ir = HistoryService.importLogs(CHIEF, Collections.singletonList(copy));
        assertEquals(1, ir.created.size());
        assertEquals(0, HistoryService.importLogs(CHIEF, Collections.singletonList(copy)).created.size());
        assertEquals("12", LogService.get(LocalDate.of(2020, 1, 2)).equip("dms3").count);

        // history / stats / dates
        assertTrue(HistoryService.history(2020, 1, null).size() >= 1);
        assertTrue(HistoryService.stats().days >= 2);
        assertTrue(HistoryService.dates(OP1, null).size() >= 1);

        // print + pdf
        String html = PrintHtml.render(Collections.singletonList(log));
        assertTrue(html.contains(today.toString()) && html.contains("op001"));
        byte[] pdf = PdfService.render(Arrays.asList(log, copy));
        assertTrue(pdf.length > 1000);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        Files.write(new File("target/test-sample.pdf").toPath(), pdf);      // for manual inspection
    }

    @Test
    public void reviewWorkflowOnBackfilledLog() {
        LocalDate d = LocalDate.of(2021, 5, 5);
        DailyLog log = LogService.create(d, CHIEF);
        // a past log with nothing submitted is read-only for operators
        try {
            LogService.setTaskField(d, OP1, "A6", "startTime", "01:00");
            fail("read only");
        } catch (ServiceException e) {
            assertEquals(403, e.getStatus());
        }
        // simulate the three shifts having been submitted, then integrate
        Db.tx(c -> {
            DailyLog l = DailyLogDao.load(c, d, true);
            for (Shift s : Shift.values()) {
                l.shiftStatus.put(s, DailyLog.SHIFT_SUBMITTED);
                DailyLogDao.saveShift(c, l, s);
            }
            return null;
        });
        log = LogService.setTaskField(d, OP1, "A6", "startTime", "01:00");   // integrator may edit
        log = LogService.submitAll(d, OP1);
        assertEquals(ReportStatus.SUBMITTED, log.status);
        log = LogService.reject(d, DEPUTY, "請補齊備註");
        assertEquals(ReportStatus.DRAFT, log.status);
        assertEquals(1, log.reviewComments.size());
        log = LogService.submitAll(d, OP2);
        log = LogService.approve(d, DEPUTY, "OK");
        assertEquals(ReportStatus.REVIEWED, log.status);
        log = LogService.approve(d, CHIEF, null);
        assertEquals(ReportStatus.APPROVED, log.status);
        assertEquals("chief001", log.approvalChief.user);
        log = LogService.unlock(d, CHIEF, "補正資料");
        assertEquals(ReportStatus.DRAFT, log.status);
        assertEquals(2, log.versions);
        assertNull(log.approvalChief);
        assertFalse(log.allShiftsSubmitted());
    }

    @Test
    public void definitionsAndApplyToday() {
        DefService.Defs defs = DefService.all();
        assertEquals(32, defs.tasks.size());
        com.machineroom.model.TaskDef t = new com.machineroom.model.TaskDef();
        t.code = "A33"; t.name = "新項目"; t.schedule = "daily"; t.plannedStart = "12:00"; t.shift = "day";
        defs = DefService.createTask(CHIEF, t);
        assertEquals(33, defs.tasks.size());
        try {
            DefService.createTask(OP1, t);
            fail("operators are not admins");
        } catch (ServiceException e) {
            assertEquals(403, e.getStatus());
        }
        DailyLog today = DefService.applyToday(CHIEF, "task");
        assertNotNull(today.task("A33"));
        assertEquals("04:10", today.task("A6").startTime);      // touched rows survive
        defs = DefService.deleteTask(CHIEF, "A33");
        assertEquals(32, defs.tasks.size());
    }

    @Test
    public void legacyImportOfOriginalDataFile() throws Exception {
        File f = new File("/Users/zuser6666chen/Downloads/MachineRoom/data/reports.db");
        Assume.assumeTrue("original data file not present", f.isFile());
        JsonObject root = JsonParser.parseString(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(LegacyImporter.isLegacy(root));
        List<DailyLog> logs = LegacyImporter.convert(root);
        assertEquals(5, logs.size());
        HistoryService.ImportResult r = HistoryService.importLogs(CHIEF, logs);
        assertEquals(5, r.created.size());
        DailyLog l = LogService.get(LocalDate.of(2026, 7, 3));
        assertEquals("ABC", l.bootUser);
        assertEquals("mick0513", l.bootOpSign.user);
        assertEquals(32, l.tasks.size());
        assertEquals(Shift.NIGHT, l.task("A9").assignedShift);
        assertEquals(Shift.DAY, l.task("A9").handoverEndShift);
        assertEquals(7, l.equip.size());
    }
}
