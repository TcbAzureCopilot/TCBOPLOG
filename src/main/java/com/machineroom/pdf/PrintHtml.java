package com.machineroom.pdf;

import java.time.LocalDateTime;
import java.util.List;

import com.machineroom.config.AppConfig;
import com.machineroom.model.CheckItem;
import com.machineroom.model.CheckType;
import com.machineroom.model.DailyLog;
import com.machineroom.model.EquipItem;
import com.machineroom.model.EquipType;
import com.machineroom.model.ImportantRecord;
import com.machineroom.model.ReviewComment;
import com.machineroom.model.Shift;
import com.machineroom.model.Signature;
import com.machineroom.model.Task;
import com.machineroom.service.LogicalDate;

import static com.machineroom.web.Html.esc;
import static com.machineroom.web.Html.orDash;

/** Server-rendered printable HTML (the original {@code buildFullHTML} / merged export), A4-friendly. */
public final class PrintHtml {

    private PrintHtml() {
    }

    private static final String CSS =
            "@page{size:A4;margin:15mm;}"
            + "body{font-family:\"Microsoft JhengHei\",\"Noto Sans CJK TC\",\"PingFang TC\",\"Segoe UI\",sans-serif;font-size:12px;color:#1f2937;max-width:1100px;margin:20px auto;padding:20px;}"
            + "h1{text-align:center;font-size:18px;border-bottom:2px solid #1e40af;padding-bottom:10px;margin-bottom:16px;color:#1e40af;}"
            + "h2{font-size:14px;color:#1e40af;margin-top:16px;margin-bottom:8px;padding:6px 10px;background:#eff6ff;border-left:4px solid #1e40af;}"
            + "table{width:100%;border-collapse:collapse;margin-bottom:12px;font-size:11px;}"
            + "th,td{border:1px solid #cbd5e1;padding:4px 6px;text-align:center;vertical-align:middle;}"
            + "th{background:#f1f5f9;font-weight:600;}td.l{text-align:left;}"
            + "tr.abn td{background:#fff1f2;}"
            + ".doc-info{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin-bottom:16px;font-size:12px;}"
            + ".doc-info > div{background:#f8fafc;padding:6px 10px;border-left:3px solid #1e40af;}"
            + ".doc-info .label{font-size:10px;color:#64748b;}.doc-info .value{font-weight:600;}"
            + ".check-grid{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px;}"
            + ".check-col{border:1px solid #cbd5e1;padding:8px;border-radius:4px;}"
            + ".check-col h4{font-size:12px;margin:0 0 6px;padding-bottom:4px;border-bottom:1px solid #e2e8f0;color:#1e40af;}"
            + ".ck-item{padding:5px 0;border-bottom:1px dashed #e2e8f0;font-size:10px;}"
            + ".ck-head{font-weight:600;color:#475569;margin-bottom:2px;}"
            + ".ck-sig{font-size:9px;color:#64748b;margin-top:3px;}"
            + ".sub-grid{display:grid;grid-template-columns:1fr 1fr;gap:2px;font-size:9px;}"
            + ".sig-stamp{display:inline-block;background:#ecfdf5;color:#065f46;border:1px solid #10b981;padding:1px 6px;border-radius:3px;font-size:10px;font-weight:600;}"
            + ".sig-empty{color:#cbd5e1;}"
            + ".stamps{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:20px;}"
            + ".stamp-box{border:2px solid #1e40af;border-radius:6px;padding:16px 10px;text-align:center;min-height:80px;}"
            + ".stamp-label{font-size:11px;color:#64748b;margin-bottom:6px;font-weight:600;}"
            + ".stamp-time{font-size:9px;color:#94a3b8;margin-top:4px;}"
            + ".jobe-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:6px;margin-bottom:8px;}"
            + ".jobe-card{border:1px solid #cbd5e1;padding:8px;text-align:center;border-radius:4px;}"
            + ".jobe-card.has-err{background:#fee2e2;border-color:#ef4444;}.jobe-card.total{background:#eff6ff;border-color:#1e40af;}"
            + ".jobe-card .num{font-size:16px;font-weight:700;color:#1e40af;}"
            + ".footer-info{text-align:right;font-size:10px;color:#94a3b8;margin-top:16px;}"
            + ".page-break{page-break-after:always;margin-bottom:40px;padding-bottom:20px;border-bottom:2px dashed #cbd5e1;}"
            + ".page-break:last-child{page-break-after:auto;border-bottom:none;}"
            + ".day-banner{background:#1e40af;color:#fff;padding:10px 16px;border-radius:6px;margin:20px 0 16px;font-size:14px;font-weight:600;}"
            + ".toc{background:#f8fafc;padding:14px;border-radius:8px;margin-bottom:20px;}.toc h3{color:#1e40af;margin:0 0 8px;font-size:14px;}"
            + ".toc-list{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:6px;font-size:12px;}"
            + ".toc-list a{color:#1e40af;text-decoration:none;padding:4px 8px;background:#fff;border-radius:4px;border:1px solid #e2e8f0;}"
            + ".print-btn{position:fixed;top:20px;right:20px;background:#1e40af;color:#fff;border:none;padding:10px 20px;border-radius:6px;cursor:pointer;font-size:14px;box-shadow:0 2px 8px rgba(0,0,0,0.15);}"
            + "@media print{body{margin:0;padding:0;max-width:none;}.no-print{display:none;}}";

    /** Complete HTML document for one or more logs. */
    public static String render(List<DailyLog> logs) {
        AppConfig cfg = AppConfig.get();
        boolean merged = logs.size() > 1;
        StringBuilder sb = new StringBuilder(32 * 1024);
        String title = merged
                ? "機房操作日誌合併 - " + logs.get(0).date + " 至 " + logs.get(logs.size() - 1).date
                : "機房操作日誌 - " + logs.get(0).date;
        sb.append("<!DOCTYPE html><html lang=\"zh-TW\"><head><meta charset=\"UTF-8\"><title>").append(esc(title))
          .append("</title><style>").append(CSS).append("</style></head><body>")
          .append("<button class=\"print-btn no-print\" onclick=\"window.print()\">🖨️ 列印 / 存成 PDF</button>")
          .append("<h1>").append(esc(cfg.orgName())).append(" 機房操作日誌").append(merged ? "（合併）" : "").append("</h1>");
        if (merged) {
            sb.append("<div style=\"text-align:center;color:#64748b;font-size:12px;margin-bottom:20px\">共 ").append(logs.size())
              .append(" 份｜").append(logs.get(0).date).append(" 至 ").append(logs.get(logs.size() - 1).date)
              .append("｜匯出時間：").append(LocalDateTime.now().format(LogicalDate.HUMAN)).append("</div>")
              .append("<div class=\"toc no-print\"><h3>📑 目錄</h3><div class=\"toc-list\">");
            for (DailyLog l : logs) {
                sb.append("<a href=\"#day-").append(l.date).append("\">").append(l.date).append("（星期").append(esc(l.weekday)).append("）</a>");
            }
            sb.append("</div></div>");
            int i = 0;
            for (DailyLog l : logs) {
                sb.append("<div class=\"page-break\"><div class=\"day-banner\" id=\"day-").append(l.date).append("\">📅 第 ")
                  .append(++i).append(" 份｜").append(l.date).append("</div>");
                body(sb, l);
                sb.append("</div>");
            }
        } else {
            body(sb, logs.get(0));
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void body(StringBuilder sb, DailyLog l) {
        sb.append("<div class=\"doc-info\">")
          .append(info("📅 邏輯日", l.date + " 07:00 起"))
          .append(info("星期", "星期" + esc(l.weekday)))
          .append(info("太陽日", String.valueOf(l.solarDay)))
          .append(info("日類型", esc(l.dayType.label()) + (l.dayTypeOverride ? " <span style=\"font-size:9px;color:#b91c1c\">(變更：" + esc(l.dayTypeReason) + ")</span>" : "")))
          .append(info("開機人", orDash(l.bootUser)))
          .append(info("開機時間", orDash(l.bootTime)))
          .append(info("開機填寫", sig(l.bootOpSign)))
          .append(info("開機覆核", sig(l.bootReviewerSign)))
          .append("</div>");

        // 壹 系統設備
        sb.append("<h2>🖥️ 壹、系統設備檢查</h2><table><thead><tr><th>班別</th><th>項目</th><th style=\"width:60px\">時間</th><th>狀態</th><th style=\"width:80px\">填寫</th><th style=\"width:80px\">覆核</th></tr></thead><tbody>");
        for (EquipItem e : l.equip) {
            if (!e.isActive()) continue;
            sb.append("<tr><td>").append(e.shift.shortLabel()).append("</td><td class=\"l\">").append(esc(e.defName)).append("</td><td>")
              .append(orDash(e.time)).append("</td><td>").append(equipStatus(e)).append("</td><td>").append(sig(e.opSign))
              .append("</td><td>").append(sig(e.reviewerSign)).append("</td></tr>");
        }
        sb.append("</tbody></table>");

        // 貳 三班檢查
        sb.append("<h2>📋 貳、三班檢查表</h2><div class=\"check-grid\">");
        for (Shift s : Shift.values()) {
            sb.append("<div class=\"check-col\"><h4>").append(shiftIcon(s)).append(' ').append(s.label()).append("</h4>");
            for (CheckItem c : l.checks.get(s)) {
                sb.append("<div class=\"ck-item\"><div class=\"ck-head\">").append(esc(c.time)).append(' ').append(esc(c.name)).append("</div>")
                  .append(checkContent(c))
                  .append("<div class=\"ck-sig\">填寫：").append(sig(c.opSign)).append(" 覆核：").append(sig(c.reviewerSign)).append("</div></div>");
            }
            sb.append("</div>");
        }
        sb.append("</div>");

        // 參 批次
        sb.append("<h2>⚙️ 參、批次作業</h2><table><thead><tr><th style=\"width:35px\">序</th><th style=\"width:40px\">班</th><th>作業項目</th><th style=\"width:55px\">開始</th><th style=\"width:55px\">結束</th><th style=\"width:80px\">數量</th><th style=\"width:50px\">狀態</th><th>備註</th><th style=\"width:70px\">填寫</th><th style=\"width:70px\">覆核</th></tr></thead><tbody>");
        for (Task t : l.tasks) {
            if (!t.shouldExecute) continue;
            String extra = "";
            if (t.forced) extra += " <span style=\"font-size:9px;color:#9333ea\">⚡" + esc(t.forceReason) + "</span>";
            if (t.handoverFrom != null) extra += " <span style=\"font-size:9px;color:#6366f1\">↪從" + t.handoverFrom.label() + "交來</span>";
            sb.append("<tr").append(t.abnormal ? " class=\"abn\"" : "").append("><td>").append(esc(t.code)).append("</td><td>")
              .append(t.isCross() ? "大夜→早" : t.assignedShift.shortLabel()).append("</td><td class=\"l\">").append(esc(t.name)).append(extra).append("</td><td>")
              .append(orDash(t.startTime)).append("</td><td>").append(orDash(t.endTime)).append("</td><td>")
              .append(esc(t.qtyValue)).append(t.qtyValue.isEmpty() || t.qtyLabel == null ? "" : " <span style=\"font-size:9px;color:#64748b\">" + esc(t.qtyLabel) + "</span>").append("</td><td>")
              .append(t.abnormal ? "⚠ 異常" : t.done ? "✓" : "—").append("</td><td class=\"l\">").append(esc(t.remark)).append("</td><td>")
              .append(sig(t.opSign)).append("</td><td>").append(sig(t.reviewerSign)).append("</td></tr>");
        }
        sb.append("</tbody></table>");

        // 肆 JOB ERROR
        sb.append("<h2>🔢 肆、JOB ERROR 統計</h2><div class=\"jobe-grid\">");
        String[] cut = {"16:00", "00:00", "07:00"};
        int i = 0;
        for (Shift s : Shift.values()) {
            int v = l.jobError.get(s) == null ? 0 : l.jobError.get(s);
            sb.append("<div class=\"jobe-card").append(v > 0 ? " has-err" : "").append("\"><div>").append(s.shortLabel()).append(' ').append(cut[i++])
              .append("</div><div class=\"num\">").append(v).append("</div><div style=\"font-size:9px\">支</div></div>");
        }
        sb.append("<div class=\"jobe-card total\"><div>合計</div><div class=\"num\">").append(l.jobErrorTotal()).append("</div><div style=\"font-size:9px\">支</div></div></div>");

        // 伍 重要記錄
        sb.append("<h2>⚠️ 伍、重要記錄事項</h2><table><thead><tr><th style=\"width:55px\">時間</th><th style=\"width:90px\">項目</th><th>狀況描述</th><th style=\"width:70px\">通知SP</th><th style=\"width:70px\">通知AP</th><th style=\"width:55px\">復原</th><th style=\"width:80px\">事件單號</th><th style=\"width:60px\">OP</th></tr></thead><tbody>");
        if (l.records.isEmpty()) {
            sb.append("<tr><td colspan=\"8\" style=\"text-align:center;color:#9ca3af\">無</td></tr>");
        }
        for (ImportantRecord r : l.records) {
            sb.append("<tr><td>").append(orDash(r.time)).append("</td><td>").append(esc(r.taskCode)).append("</td><td class=\"l\">").append(esc(r.description))
              .append("</td><td>").append(orDash(r.notifySP)).append("</td><td>").append(orDash(r.notifyAP)).append("</td><td>")
              .append(orDash(r.recoverTime)).append("</td><td>").append(orDash(r.ticket)).append("</td><td>").append(esc(r.op)).append("</td></tr>");
        }
        sb.append("</tbody></table>");

        // 陸 簽核
        sb.append("<h2>📝 陸、簽核</h2><div class=\"stamps\">")
          .append(stamp("經辦", l.approvalOperator)).append(stamp("副科長", l.approvalDeputy)).append(stamp("科長", l.approvalChief))
          .append("</div>");
        if (!l.reviewComments.isEmpty()) {
            sb.append("<table style=\"margin-top:12px\"><thead><tr><th style=\"width:110px\">時間</th><th style=\"width:80px\">人員</th><th style=\"width:90px\">角色</th><th>審核意見</th></tr></thead><tbody>");
            for (ReviewComment rc : l.reviewComments) {
                sb.append("<tr><td>").append(rc.time == null ? "—" : rc.time.format(LogicalDate.HUMAN)).append("</td><td>").append(esc(rc.by))
                  .append("</td><td>").append(esc(rc.role)).append("</td><td class=\"l\">").append(esc(rc.text)).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }
        sb.append("<div class=\"footer-info\">匯出時間：").append(LocalDateTime.now().format(LogicalDate.HUMAN))
          .append(" ｜ 狀態：").append(esc(l.status.label())).append(" ｜ 版本：v").append(l.versions)
          .append(l.unlockReason.isEmpty() ? "" : " ｜ 解鎖原因：" + esc(l.unlockReason)).append("</div>");
    }

    // ------------------------------------------------------------------ fragments

    private static String info(String label, String valueHtml) {
        return "<div><div class=\"label\">" + label + "</div><div class=\"value\">" + valueHtml + "</div></div>";
    }

    public static String sig(Signature s) {
        if (s == null) return "<span class=\"sig-empty\">—</span>";
        return "<span class=\"sig-stamp\">" + esc(s.user) + "</span>";
    }

    private static String stamp(String label, Signature s) {
        return "<div class=\"stamp-box\"><div class=\"stamp-label\">" + label + "</div>" + sig(s)
                + "<div class=\"stamp-time\">" + (s == null || s.time == null ? "" : s.time.format(LogicalDate.HUMAN)) + "</div></div>";
    }

    private static String shiftIcon(Shift s) {
        switch (s) {
            case DAY: return "🌞";
            case EVENING: return "🌒";
            default: return "🌑";
        }
    }

    public static String equipStatus(EquipItem e) {
        if (EquipType.DMS.key().equals(e.type)) return e.count.isEmpty() ? "—" : esc(e.count) + " 台";
        if (EquipType.STATUS.key().equals(e.type)) return orDash(e.status);
        return orDash(e.notify);
    }

    private static String checkContent(CheckItem c) {
        CheckType t = c.checkType();
        switch (t) {
            case PORTAL: {
                StringBuilder sb = new StringBuilder("<div class=\"sub-grid\">");
                for (String k : t.valueKeys()) sb.append("<div>").append(esc(k)).append("：<b>").append(orDash(c.values.get(k))).append("</b></div>");
                return sb.append("</div>").toString();
            }
            case SMS:
                return "<div>SGLGMVS: <b>" + orDash(c.values.get("SGLGMVS")) + "%</b> ｜ SGLGIMS: <b>" + orDash(c.values.get("SGLGIMS"))
                        + "%</b> ｜ SGMQLOG: <b>" + orDash(c.values.get("SGMQLOG")) + "%</b></div>";
            case RMF:
                return "<div>PRDA CSA:<b>" + orDash(c.values.get("PRDA.CSA")) + "</b> ECSA:<b>" + orDash(c.values.get("PRDA.ECSA"))
                        + "</b> SQA:<b>" + orDash(c.values.get("PRDA.SQA")) + "</b> ESQA:<b>" + orDash(c.values.get("PRDA.ESQA")) + "</b></div>"
                        + "<div>PRDB CSA:<b>" + orDash(c.values.get("PRDB.CSA")) + "</b> ECSA:<b>" + orDash(c.values.get("PRDB.ECSA"))
                        + "</b> SQA:<b>" + orDash(c.values.get("PRDB.SQA")) + "</b> ESQA:<b>" + orDash(c.values.get("PRDB.ESQA")) + "</b></div>";
            case CABINET:
                return "<div>機櫃：<b>" + orDash(c.status) + "</b> ｜ 進出登記簿：<b>" + orDash(c.entryLog) + "</b></div>";
            default:
                return "<div>狀態：<b>" + orDash(c.status) + "</b></div>";
        }
    }

    /** Plain-text variant of the check content for the PDF. */
    public static String checkText(CheckItem c) {
        CheckType t = c.checkType();
        StringBuilder sb = new StringBuilder();
        switch (t) {
            case PORTAL:
            case SMS:
            case RMF:
                for (String k : t.valueKeys()) {
                    if (sb.length() > 0) sb.append("  ");
                    String v = c.values.get(k);
                    sb.append(k).append(':').append(v == null || v.isEmpty() ? "—" : v).append(t == CheckType.SMS ? "%" : "");
                }
                return sb.toString();
            case CABINET:
                return "機櫃:" + dash(c.status) + "  進出登記簿:" + dash(c.entryLog);
            default:
                return "狀態:" + dash(c.status);
        }
    }

    private static String dash(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }
}
