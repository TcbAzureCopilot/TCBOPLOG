package com.machineroom.service;

import java.time.LocalDate;
import java.util.List;

import com.machineroom.dao.DefDao;
import com.machineroom.db.Jdbc;
import com.machineroom.model.CheckDef;
import com.machineroom.model.CheckItem;
import com.machineroom.model.DailyLog;
import com.machineroom.model.DayType;
import com.machineroom.model.EquipDef;
import com.machineroom.model.EquipItem;
import com.machineroom.model.EquipType;
import com.machineroom.model.ScheduleType;
import com.machineroom.model.Shift;
import com.machineroom.model.Task;
import com.machineroom.model.TaskDef;

/** Builds an empty daily log from the current definitions (the original {@code makeEmptyReport}). */
public final class LogFactory {

    private LogFactory() {
    }

    public static DailyLog newLog(LocalDate date, DayType dayType, List<TaskDef> taskDefs,
                                  List<EquipDef> equipDefs, List<CheckDef> checkDefs, String createdBy) {
        DailyLog l = new DailyLog();
        l.date = date;
        l.weekday = LogicalDate.weekday(date);
        l.solarDay = LogicalDate.solarDay(date);
        l.dayType = dayType != null ? dayType : DayType.defaultFor(date);
        l.createdAt = Jdbc.now();
        l.createdBy = createdBy;
        l.updatedAt = l.createdAt;
        l.updatedBy = createdBy;

        int seq = 0;
        for (EquipDef d : equipDefs) {
            if (!d.enabled) continue;
            l.equip.add(newEquip(d, ++seq));
        }
        for (Shift s : Shift.values()) {
            int cs = 0;
            for (CheckDef d : checkDefs) {
                if (!d.enabled || d.shift != s) continue;
                l.checks.get(s).add(newCheck(d, ++cs));
            }
        }
        int ts = 0;
        for (TaskDef d : taskDefs) {
            if (!d.enabled) continue;
            l.tasks.add(newTask(d, ++ts, date, l.dayType));
        }
        return l;
    }

    public static EquipItem newEquip(EquipDef d, int seq) {
        EquipItem e = new EquipItem();
        e.defId = d.id;
        e.seq = seq;
        e.defName = d.name;
        e.type = d.type;
        e.shift = d.shift;
        e.time = d.time == null ? "" : d.time;
        if (EquipType.STATUS.key().equals(d.type)) {
            e.statusOptions = d.statusOptions.isEmpty() ? DefDao.defaultStatusOptions() : d.statusOptions;
        }
        // IMS = special operation: exists in the log but must be switched on for the day
        e.enabled = !EquipType.IMS.key().equals(d.type);
        return e;
    }

    public static CheckItem newCheck(CheckDef d, int seq) {
        CheckItem c = new CheckItem();
        c.defId = d.id;
        c.seq = seq;
        c.name = d.name;
        c.type = d.type;
        c.shift = d.shift;
        c.time = d.time == null ? "" : d.time;
        c.holidaySkip = d.holidaySkip;
        for (String k : c.checkType().valueKeys()) c.values.put(k, "");
        return c;
    }

    public static Task newTask(TaskDef d, int seq, LocalDate date, DayType dayType) {
        Task t = new Task();
        t.code = d.code;
        t.seq = seq;
        t.name = d.name;
        t.schedule = d.schedule;
        t.plannedStart = d.plannedStart;
        t.shift = d.shift == null ? Shift.DAY.key() : d.shift;
        t.hasEnd = d.hasEnd;
        t.qtyLabel = d.qtyLabel;
        t.shouldExecute = ScheduleType.shouldExecute(d.schedule, date, dayType);
        if (d.isCross()) {
            // starts in the night shift, finished (and owned) by the day shift
            t.assignedShift = Shift.NIGHT;
            t.handoverEndShift = Shift.DAY;
        } else {
            Shift s = Shift.fromKey(d.shift);
            t.assignedShift = s == null ? Shift.DAY : s;
        }
        return t;
    }
}
