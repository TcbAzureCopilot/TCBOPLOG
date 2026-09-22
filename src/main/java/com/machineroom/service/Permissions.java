package com.machineroom.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.machineroom.auth.UserPrincipal;
import com.machineroom.model.CheckItem;
import com.machineroom.model.DailyLog;
import com.machineroom.model.EquipItem;
import com.machineroom.model.ReportStatus;
import com.machineroom.model.Shift;
import com.machineroom.model.Signature;
import com.machineroom.model.Task;

/**
 * Server-side permission rules for one user looking at one daily log.
 * Mirrors the rules of the original single-page app (canEditTask, canReviewEquip, …) so
 * the browser only renders what the server allows.
 */
public final class Permissions {

    private final DailyLog log;
    private final UserPrincipal user;
    private final LocalDate today;
    private final LocalDateTime now;

    public Permissions(DailyLog log, UserPrincipal user, LocalDate today, LocalDateTime now) {
        this.log = log;
        this.user = user;
        this.today = today;
        this.now = now;
    }

    public DailyLog log() { return log; }
    public UserPrincipal user() { return user; }
    public LocalDate today() { return today; }
    public LocalDateTime now() { return now; }
    public Shift shift() { return user.getShift(); }
    private String me() { return user.getUsername(); }

    // ------------------------------------------------------------------ basics

    public boolean isOperator() { return user.isOperator(); }
    public boolean isReviewer() { return user.isReviewer(); }
    public boolean isDraft() { return log.status == ReportStatus.DRAFT; }
    public boolean isCurrentLogicalDay() { return log.date.equals(today); }
    public boolean shiftSubmitted(Shift s) { return log.isShiftSubmitted(s); }
    public boolean myShiftSubmitted() { return shift() != null && shiftSubmitted(shift()); }

    /** Operator, draft, and either the current logical day or the integration stage. */
    public boolean isEditableNow() {
        if (!isOperator() || !isDraft()) return false;
        return isCurrentLogicalDay() || log.allShiftsSubmitted();
    }

    /** All three shifts submitted, log still draft → any operator may fix everything before submitting. */
    public boolean isIntegrator() {
        return isOperator() && isDraft() && log.allShiftsSubmitted();
    }

    public String mode() {
        if (isReviewer()) return "review";
        if (isIntegrator()) return "integrate";
        if (isEditableNow()) return "fill";
        return "readonly";
    }

    // ------------------------------------------------------------------ header

    public boolean canChangeDayType() {
        return isOperator() && isDraft() && isCurrentLogicalDay() && !log.allShiftsSubmitted();
    }

    public boolean canEditBoot() {
        return isOperator() && isDraft() && shift() == Shift.NIGHT && !shiftSubmitted(Shift.NIGHT)
                && isCurrentLogicalDay() && log.bootOpSign == null;
    }

    public boolean canSignBoot() {
        return canEditBoot() && !isEmpty(log.bootUser) && !isEmpty(log.bootTime);
    }

    public boolean canReviewBoot() {
        return isOperator() && isDraft() && isCurrentLogicalDay() && log.bootOpSign != null
                && log.bootReviewerSign == null && !Signature.isBy(log.bootOpSign, me());
    }

    // ------------------------------------------------------------------ tasks

    public boolean canEditTask(Task t) {
        if (!isEditableNow() || !t.shouldExecute) return false;
        if (isIntegrator()) return true;
        if (myShiftSubmitted()) return false;
        if (!t.editableBy(shift())) return false;
        return t.opSign == null;        // "done" locks the row until the integration stage
    }

    public boolean canReviewTask(Task t) {
        if (!isOperator() || !isDraft()) return false;
        if (!isCurrentLogicalDay() && !log.allShiftsSubmitted()) return false;
        if (!t.shouldExecute || !t.done || t.reviewerSign != null) return false;
        return !Signature.isBy(t.opSign, me());
    }

    public boolean canHandoverTask(Task t) {
        return canEditTask(t) && !t.done && !t.isCross();
    }

    public boolean canForceTask(Task t) {
        return isOperator() && isDraft() && !t.shouldExecute && isCurrentLogicalDay() && !myShiftSubmitted();
    }

    public boolean isDelayed(Task t) {
        if (!t.shouldExecute || t.done || !isCurrentLogicalDay()) return false;
        if (!LogicalDate.isValidTime(t.plannedStart) || isEmpty(t.plannedStart)) return false;
        LocalDate d = LogicalDate.isNextDayTime(t.plannedStart) ? log.date.plusDays(1) : log.date;
        LocalDateTime planned = LocalDateTime.of(d, LocalTime.parse(t.plannedStart, LogicalDate.HM));
        return now.isAfter(planned);
    }

    // ------------------------------------------------------------------ equipment

    public boolean canEditEquip(EquipItem e) {
        if (!isEditableNow() || !e.isActive()) return false;
        if (isIntegrator()) return true;
        if (shiftSubmitted(e.shift) || e.shift != shift()) return false;
        return e.opSign == null;
    }

    public boolean canSignEquip(EquipItem e) {
        return canEditEquip(e) && e.opSign == null && e.isFilled();
    }

    public boolean canUnsignEquip(EquipItem e) {
        return isEditableNow() && e.opSign != null && Signature.isBy(e.opSign, me())
                && e.reviewerSign == null && !shiftSubmitted(e.shift);
    }

    public boolean canReviewEquip(EquipItem e) {
        if (!isOperator() || !isDraft() || !e.isActive()) return false;
        if (!isCurrentLogicalDay() && !isIntegrator()) return false;
        return e.opSign != null && e.reviewerSign == null && !Signature.isBy(e.opSign, me());
    }

    public boolean canUnreviewEquip(EquipItem e) {
        return isEditableNow() && e.reviewerSign != null && Signature.isBy(e.reviewerSign, me()) && !shiftSubmitted(e.shift);
    }

    public boolean canToggleIms(EquipItem e) {
        return e.isIms() && isOperator() && isDraft() && isCurrentLogicalDay() && !shiftSubmitted(e.shift);
    }

    // ------------------------------------------------------------------ shift checks

    public boolean canEditCheck(CheckItem c) {
        if (!isEditableNow()) return false;
        if (isIntegrator()) return true;
        if (shiftSubmitted(c.shift) || c.shift != shift()) return false;
        return c.opSign == null;
    }

    public boolean canSignCheck(CheckItem c) {
        return canEditCheck(c) && c.opSign == null && c.isFilled();
    }

    public boolean canUnsignCheck(CheckItem c) {
        return isEditableNow() && c.opSign != null && Signature.isBy(c.opSign, me())
                && c.reviewerSign == null && !shiftSubmitted(c.shift);
    }

    public boolean canReviewCheck(CheckItem c) {
        if (!isOperator() || !isDraft()) return false;
        if (!isCurrentLogicalDay() && !isIntegrator()) return false;
        return c.opSign != null && c.reviewerSign == null && !Signature.isBy(c.opSign, me());
    }

    public boolean canUnreviewCheck(CheckItem c) {
        return isEditableNow() && c.reviewerSign != null && Signature.isBy(c.reviewerSign, me()) && !shiftSubmitted(c.shift);
    }

    // ------------------------------------------------------------------ job error / records

    /** Own shift; the day shift may also consolidate the night shift's figure the next morning. */
    public boolean canEditJobError(Shift s) {
        if (!isEditableNow()) return false;
        if (isIntegrator()) return true;
        return shift() == s || (s == Shift.NIGHT && shift() == Shift.DAY);
    }

    public boolean canManageRecords() {
        return isEditableNow();
    }

    // ------------------------------------------------------------------ workflow

    public boolean canSubmitShift() {
        return isOperator() && isDraft() && isCurrentLogicalDay() && shift() != null && !myShiftSubmitted();
    }

    public boolean canSubmitAll() {
        return isOperator() && isDraft() && log.allShiftsSubmitted();
    }

    public boolean canRecall() {
        return isOperator() && log.status == ReportStatus.SUBMITTED;
    }

    public boolean canApprove() {
        return (user.isDeputy() && log.status == ReportStatus.SUBMITTED)
                || (user.isChief() && log.status == ReportStatus.REVIEWED);
    }

    public boolean canReject() {
        return canApprove();
    }

    public boolean canUnlock() {
        return isReviewer() && log.status == ReportStatus.APPROVED;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
