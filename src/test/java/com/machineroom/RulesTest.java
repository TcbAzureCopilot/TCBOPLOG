package com.machineroom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;

import org.junit.Test;

import com.machineroom.auth.CidrMatcher;
import com.machineroom.auth.Role;
import com.machineroom.auth.UserPrincipal;
import com.machineroom.model.DailyLog;
import com.machineroom.model.DayType;
import com.machineroom.model.ReportStatus;
import com.machineroom.model.ScheduleType;
import com.machineroom.model.Shift;
import com.machineroom.model.Signature;
import com.machineroom.model.Task;
import com.machineroom.model.TaskDef;
import com.machineroom.service.LogFactory;
import com.machineroom.service.LogicalDate;
import com.machineroom.service.Permissions;

/** Pure-logic rules: logical day, schedules, IP allow-list, permission matrix. */
public class RulesTest {

    @Test
    public void logicalDayCutoff() {
        assertEquals(LocalDate.of(2026, 9, 20), LogicalDate.today(LocalDateTime.of(2026, 9, 21, 3, 30), 7));
        assertEquals(LocalDate.of(2026, 9, 21), LogicalDate.today(LocalDateTime.of(2026, 9, 21, 7, 0), 7));
        assertEquals("日", LogicalDate.weekday(LocalDate.of(2026, 9, 20)));
        assertEquals("一", LogicalDate.weekday(LocalDate.of(2026, 9, 21)));
        assertTrue(LogicalDate.isNextDayTime("04:00"));
        assertFalse(LogicalDate.isNextDayTime("17:00"));
        assertTrue(LogicalDate.isValidTime("23:59"));
        assertFalse(LogicalDate.isValidTime("24:00"));
        assertFalse(LogicalDate.isValidTime("9:00"));
    }

    @Test
    public void schedules() {
        LocalDate sunday = LocalDate.of(2026, 9, 20);     // holiday by default
        LocalDate monday = LocalDate.of(2026, 9, 21);
        LocalDate first = LocalDate.of(2026, 10, 1);      // Thursday
        assertTrue(ScheduleType.shouldExecute("daily", sunday, null));
        assertFalse(ScheduleType.shouldExecute("business_day", sunday, null));
        assertTrue(ScheduleType.shouldExecute("business_day", monday, null));
        assertTrue(ScheduleType.shouldExecute("weekly_sunday", sunday, null));
        assertFalse(ScheduleType.shouldExecute("weekly_sunday", monday, null));
        assertTrue(ScheduleType.shouldExecute("monthly_first", first, null));
        assertTrue(ScheduleType.shouldExecute("monthly_first_business", first, null));
        assertFalse(ScheduleType.shouldExecute("monthly_first_business", first, DayType.HOLIDAY));
        assertFalse(ScheduleType.shouldExecute("daily", monday, DayType.TYPHOON));
        // a Sunday overridden to business day runs business-day tasks
        assertTrue(ScheduleType.shouldExecute("business_day", sunday, DayType.BUSINESS));
    }

    @Test
    public void cidr() {
        CidrMatcher m = new CidrMatcher(Arrays.asList("10.1.0.0/16", "192.168.1.5", "fe80::/10"));
        assertTrue(m.matches("10.1.200.3"));
        assertFalse(m.matches("10.2.0.1"));
        assertTrue(m.matches("192.168.1.5"));
        assertFalse(m.matches("192.168.1.6"));
        assertTrue(m.matches("fe80::1"));
        assertTrue(m.matches("::ffff:10.1.2.3"));    // IPv4-mapped IPv6
    }

    private static DailyLog sampleLog(LocalDate date) {
        TaskDef night = new TaskDef();
        night.code = "A6"; night.name = "night task"; night.schedule = "daily"; night.plannedStart = "04:00"; night.shift = "night";
        TaskDef cross = new TaskDef();
        cross.code = "A9"; cross.name = "cross task"; cross.schedule = "daily"; cross.plannedStart = "05:00"; cross.shift = "cross";
        TaskDef biz = new TaskDef();
        biz.code = "A1"; biz.name = "biz"; biz.schedule = "business_day"; biz.plannedStart = "17:00"; biz.shift = "evening";
        return LogFactory.newLog(date, DayType.BUSINESS, Arrays.asList(night, cross, biz),
                java.util.Collections.<com.machineroom.model.EquipDef>emptyList(),
                java.util.Collections.<com.machineroom.model.CheckDef>emptyList(), "test");
    }

    private static UserPrincipal op(String name, Shift s) {
        return new UserPrincipal(name, name, EnumSet.of(Role.OPERATOR), false, Role.OPERATOR, s, "127.0.0.1");
    }

    @Test
    public void taskPermissions() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        DailyLog log = sampleLog(today);
        LocalDateTime now = LocalDateTime.of(2026, 9, 21, 22, 0);
        Task a6 = log.task("A6"), a9 = log.task("A9"), a1 = log.task("A1");

        Permissions night1 = new Permissions(log, op("n1", Shift.NIGHT), today, now);
        Permissions day1 = new Permissions(log, op("d1", Shift.DAY), today, now);
        Permissions eve1 = new Permissions(log, op("e1", Shift.EVENING), today, now);

        assertTrue(night1.canEditTask(a6));
        assertFalse(day1.canEditTask(a6));
        // cross task: night starts it, day finishes and owns it
        assertTrue(night1.canEditTask(a9));
        assertTrue(day1.canEditTask(a9));
        assertFalse(eve1.canEditTask(a9));
        assertTrue(a9.belongsTo(Shift.DAY));
        assertFalse(a9.belongsTo(Shift.NIGHT));
        assertFalse(night1.canHandoverTask(a9));
        assertTrue(eve1.canHandoverTask(a1));

        // done = signed → locked for the author, reviewable by a colleague of any shift, never by the author
        a6.done = true;
        a6.startTime = "04:10";
        a6.opSign = Signature.now("n1");
        assertFalse(night1.canEditTask(a6));
        assertFalse(night1.canReviewTask(a6));
        assertTrue(new Permissions(log, op("n2", Shift.NIGHT), today, now).canReviewTask(a6));
        assertTrue(day1.canReviewTask(a6));

        // shift submitted → locked
        log.shiftStatus.put(Shift.EVENING, DailyLog.SHIFT_SUBMITTED);
        assertFalse(eve1.canEditTask(a1));

        // delay: planned 17:00 today, now 22:00 → delayed while not done
        assertTrue(eve1.isDelayed(a1));
        assertFalse(night1.isDelayed(a6));    // planned 04:00 = next calendar day

        // past logical day, not integrated → read only
        Permissions past = new Permissions(log, op("n1", Shift.NIGHT), today.plusDays(1), now.plusDays(1));
        assertFalse(past.isEditableNow());
        assertEquals("readonly", past.mode());

        // all shifts submitted → integrator may edit everything
        for (Shift s : Shift.values()) log.shiftStatus.put(s, DailyLog.SHIFT_SUBMITTED);
        assertTrue(past.isIntegrator());
        assertTrue(past.canEditTask(a6));
        assertTrue(past.canSubmitAll());
        assertEquals("integrate", past.mode());
    }

    @Test
    public void workflowPermissions() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        DailyLog log = sampleLog(today);
        LocalDateTime now = LocalDateTime.of(2026, 9, 21, 10, 0);
        UserPrincipal deputy = new UserPrincipal("dep", "dep", EnumSet.of(Role.DEPUTY), true, Role.DEPUTY, null, "ip");
        UserPrincipal chief = new UserPrincipal("chief", "chief", EnumSet.of(Role.CHIEF), true, Role.CHIEF, null, "ip");

        assertFalse(new Permissions(log, deputy, today, now).canApprove());
        log.status = ReportStatus.SUBMITTED;
        assertTrue(new Permissions(log, deputy, today, now).canApprove());
        assertFalse(new Permissions(log, chief, today, now).canApprove());
        assertTrue(new Permissions(log, op("o", Shift.DAY), today, now).canRecall());
        log.status = ReportStatus.REVIEWED;
        assertTrue(new Permissions(log, chief, today, now).canApprove());
        assertFalse(new Permissions(log, deputy, today, now).canApprove());
        log.status = ReportStatus.APPROVED;
        assertTrue(new Permissions(log, deputy, today, now).canUnlock());
        assertTrue(new Permissions(log, chief, today, now).canUnlock());
        assertFalse(new Permissions(log, op("o", Shift.DAY), today, now).canUnlock());
        assertFalse(new Permissions(log, op("o", Shift.DAY), today, now).isEditableNow());
        assertEquals("review", new Permissions(log, chief, today, now).mode());
    }
}
