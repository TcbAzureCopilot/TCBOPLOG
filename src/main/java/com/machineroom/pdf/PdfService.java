package com.machineroom.pdf;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.machineroom.config.AppConfig;
import com.machineroom.model.CheckItem;
import com.machineroom.model.DailyLog;
import com.machineroom.model.EquipItem;
import com.machineroom.model.EquipType;
import com.machineroom.model.ImportantRecord;
import com.machineroom.model.ReviewComment;
import com.machineroom.model.Shift;
import com.machineroom.model.Signature;
import com.machineroom.model.Task;
import com.machineroom.service.LogicalDate;

/** Renders one or more daily logs to a PDF (OpenPDF), one day per page group, A4 portrait. */
public final class PdfService {

    private static final Color BLUE = new Color(0x1e, 0x40, 0xaf);
    private static final Color HEAD_BG = new Color(0xf1, 0xf5, 0xf9);
    private static final Color SECTION_BG = new Color(0xef, 0xf6, 0xff);
    private static final Color ABN_BG = new Color(0xff, 0xf1, 0xf2);
    private static final Color GRID = new Color(0xcb, 0xd5, 0xe1);
    private static final Color MUTED = new Color(0x64, 0x74, 0x8b);
    private static final Color GREEN = new Color(0x06, 0x5f, 0x46);

    private final BaseFont bf;
    private final Font fTitle;
    private final Font fH2;
    private final Font fBody;
    private final Font fBold;
    private final Font fSmall;
    private final Font fMuted;
    private final Font fSig;

    private PdfService() {
        bf = PdfFonts.base();
        fTitle = new Font(bf, 16, Font.BOLD, BLUE);
        fH2 = new Font(bf, 11, Font.BOLD, BLUE);
        fBody = new Font(bf, 8, Font.NORMAL, Color.BLACK);
        fBold = new Font(bf, 8, Font.BOLD, Color.BLACK);
        fSmall = new Font(bf, 7, Font.NORMAL, Color.BLACK);
        fMuted = new Font(bf, 7, Font.NORMAL, MUTED);
        fSig = new Font(bf, 7.5f, Font.BOLD, GREEN);
    }

    public static byte[] render(List<DailyLog> logs) {
        return new PdfService().build(logs);
    }

    private byte[] build(List<DailyLog> logs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        Document doc = new Document(PageSize.A4, 36, 36, 40, 40);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Footer());
            doc.addTitle(logs.size() == 1 ? "機房操作日誌 " + logs.get(0).date : "機房操作日誌 " + logs.get(0).date + " ~ " + logs.get(logs.size() - 1).date);
            doc.addCreator(AppConfig.get().appTitle());
            doc.open();
            boolean first = true;
            for (DailyLog l : logs) {
                if (!first) doc.newPage();
                first = false;
                day(doc, l, logs.size() > 1);
            }
            doc.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("PDF 產生失敗", e);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ one day

    private void day(Document doc, DailyLog l, boolean merged) throws DocumentException {
        Paragraph title = new Paragraph(AppConfig.get().orgName() + " 機房操作日誌" + (merged ? "　" + l.date : ""), fTitle);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(6);
        doc.add(title);
        doc.add(rule());

        PdfPTable info = new PdfPTable(new float[] {1, 1, 1, 1});
        info.setWidthPercentage(100);
        info.setSpacingBefore(6);
        info.setSpacingAfter(8);
        infoCell(info, "邏輯日", l.date + " 07:00 起");
        infoCell(info, "星期", "星期" + l.weekday);
        infoCell(info, "太陽日", String.valueOf(l.solarDay));
        infoCell(info, "日類型", l.dayType.label() + (l.dayTypeOverride ? "（變更：" + l.dayTypeReason + "）" : ""));
        infoCell(info, "開機人", dash(l.bootUser));
        infoCell(info, "開機時間", dash(l.bootTime));
        infoCell(info, "開機填寫", sigText(l.bootOpSign));
        infoCell(info, "開機覆核", sigText(l.bootReviewerSign));
        doc.add(info);

        // 壹
        doc.add(h2("壹、系統設備檢查"));
        PdfPTable eq = table(new float[] {8, 30, 10, 26, 13, 13}, "班別", "項目", "時間", "狀態", "填寫", "覆核");
        for (EquipItem e : l.equip) {
            if (!e.isActive()) continue;
            cell(eq, e.shift.shortLabel(), fBody, Element.ALIGN_CENTER, null);
            cell(eq, e.defName, fBody, Element.ALIGN_LEFT, null);
            cell(eq, dash(e.time), fBody, Element.ALIGN_CENTER, null);
            cell(eq, equipStatus(e), fBody, Element.ALIGN_CENTER, null);
            sigCell(eq, e.opSign);
            sigCell(eq, e.reviewerSign);
        }
        doc.add(eq);

        // 貳
        doc.add(h2("貳、三班檢查表"));
        PdfPTable ck = new PdfPTable(3);
        ck.setWidthPercentage(100);
        ck.setSpacingAfter(6);
        for (Shift s : Shift.values()) {
            PdfPTable col = new PdfPTable(1);
            col.setWidthPercentage(100);
            PdfPCell head = new PdfPCell(new Phrase(s.label(), new Font(bf, 8.5f, Font.BOLD, BLUE)));
            head.setBackgroundColor(HEAD_BG);
            head.setBorderColor(GRID);
            head.setPadding(3);
            col.addCell(head);
            for (CheckItem c : l.checks.get(s)) {
                Phrase ph = new Phrase();
                ph.add(new Chunk((c.time.isEmpty() ? "" : c.time + " ") + c.name + "\n", fBold));
                ph.add(new Chunk(PrintHtml.checkText(c) + "\n", fSmall));
                ph.add(new Chunk("填寫：" + sigText(c.opSign) + "   覆核：" + sigText(c.reviewerSign), fMuted));
                PdfPCell pc = new PdfPCell(ph);
                pc.setBorderColor(GRID);
                pc.setPadding(3);
                col.addCell(pc);
            }
            PdfPCell wrap = new PdfPCell(col);
            wrap.setBorderColor(GRID);
            wrap.setPadding(2);
            ck.addCell(wrap);
        }
        doc.add(ck);

        // 參
        doc.add(h2("參、批次作業"));
        PdfPTable tk = table(new float[] {6, 8, 30, 8, 8, 10, 8, 16, 9, 9}, "序", "班", "作業項目", "開始", "結束", "數量", "狀態", "備註", "填寫", "覆核");
        for (Task t : l.tasks) {
            if (!t.shouldExecute) continue;
            Color bg = t.abnormal ? ABN_BG : null;
            String name = t.name + (t.forced ? "\n⚡" + t.forceReason : "") + (t.handoverFrom != null ? "\n↪從" + t.handoverFrom.label() + "交來" : "");
            cell(tk, t.code, fBold, Element.ALIGN_CENTER, bg);
            cell(tk, t.isCross() ? "大夜→早" : t.assignedShift.shortLabel(), fBody, Element.ALIGN_CENTER, bg);
            cell(tk, name, fBody, Element.ALIGN_LEFT, bg);
            cell(tk, dash(t.startTime), fBody, Element.ALIGN_CENTER, bg);
            cell(tk, dash(t.endTime), fBody, Element.ALIGN_CENTER, bg);
            cell(tk, t.qtyValue.isEmpty() ? "" : t.qtyValue + (t.qtyLabel == null ? "" : " " + t.qtyLabel), fBody, Element.ALIGN_CENTER, bg);
            cell(tk, t.abnormal ? "⚠ 異常" : t.done ? "✓" : "—", fBody, Element.ALIGN_CENTER, bg);
            cell(tk, t.remark, fSmall, Element.ALIGN_LEFT, bg);
            sigCell(tk, t.opSign);
            sigCell(tk, t.reviewerSign);
        }
        doc.add(tk);

        // 肆
        doc.add(h2("肆、JOB ERROR 統計"));
        PdfPTable je = new PdfPTable(4);
        je.setWidthPercentage(100);
        je.setSpacingAfter(6);
        String[] cut = {"16:00", "00:00", "07:00"};
        int i = 0;
        for (Shift s : Shift.values()) {
            int v = l.jobError.get(s) == null ? 0 : l.jobError.get(s);
            jobCell(je, s.shortLabel() + " " + cut[i++], v, v > 0 ? ABN_BG : null);
        }
        jobCell(je, "合計", l.jobErrorTotal(), SECTION_BG);
        doc.add(je);

        // 伍
        doc.add(h2("伍、重要記錄事項"));
        PdfPTable rc = table(new float[] {8, 14, 30, 10, 10, 8, 12, 8}, "時間", "項目", "狀況描述", "通知SP", "通知AP", "復原", "事件單號", "OP");
        if (l.records.isEmpty()) {
            PdfPCell none = new PdfPCell(new Phrase("無", fMuted));
            none.setColspan(8);
            none.setHorizontalAlignment(Element.ALIGN_CENTER);
            none.setBorderColor(GRID);
            none.setPadding(4);
            rc.addCell(none);
        }
        for (ImportantRecord r : l.records) {
            cell(rc, dash(r.time), fBody, Element.ALIGN_CENTER, null);
            cell(rc, r.taskCode, fBody, Element.ALIGN_CENTER, null);
            cell(rc, r.description, fBody, Element.ALIGN_LEFT, null);
            cell(rc, dash(r.notifySP), fBody, Element.ALIGN_CENTER, null);
            cell(rc, dash(r.notifyAP), fBody, Element.ALIGN_CENTER, null);
            cell(rc, dash(r.recoverTime), fBody, Element.ALIGN_CENTER, null);
            cell(rc, dash(r.ticket), fBody, Element.ALIGN_CENTER, null);
            cell(rc, r.op, fBody, Element.ALIGN_CENTER, null);
        }
        doc.add(rc);

        // 陸
        doc.add(h2("陸、簽核"));
        PdfPTable st = new PdfPTable(3);
        st.setWidthPercentage(100);
        st.setSpacingAfter(6);
        stampCell(st, "經辦", l.approvalOperator);
        stampCell(st, "副科長", l.approvalDeputy);
        stampCell(st, "科長", l.approvalChief);
        doc.add(st);
        if (!l.reviewComments.isEmpty()) {
            PdfPTable cm = table(new float[] {18, 14, 16, 52}, "時間", "人員", "角色", "審核意見");
            for (ReviewComment c : l.reviewComments) {
                cell(cm, c.time == null ? "—" : c.time.format(LogicalDate.HUMAN), fBody, Element.ALIGN_CENTER, null);
                cell(cm, c.by, fBody, Element.ALIGN_CENTER, null);
                cell(cm, c.role, fBody, Element.ALIGN_CENTER, null);
                cell(cm, c.text, fBody, Element.ALIGN_LEFT, null);
            }
            doc.add(cm);
        }
        Paragraph foot = new Paragraph("匯出時間：" + LocalDateTime.now().format(LogicalDate.HUMAN) + " ｜ 狀態：" + l.status.label()
                + " ｜ 版本：v" + l.versions + (l.unlockReason.isEmpty() ? "" : " ｜ 解鎖原因：" + l.unlockReason), fMuted);
        foot.setAlignment(Element.ALIGN_RIGHT);
        doc.add(foot);
    }

    // ------------------------------------------------------------------ building blocks

    private Paragraph h2(String text) {
        Paragraph p = new Paragraph(text, fH2);
        p.setSpacingBefore(6);
        p.setSpacingAfter(3);
        return p;
    }

    private PdfPTable rule() throws DocumentException {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(BLUE);
        c.setBorderWidth(1.2f);
        c.setFixedHeight(2);
        t.addCell(c);
        return t;
    }

    private PdfPTable table(float[] widths, String... heads) throws DocumentException {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setSpacingAfter(6);
        t.setHeaderRows(1);
        for (String h : heads) {
            PdfPCell c = new PdfPCell(new Phrase(h, fBold));
            c.setBackgroundColor(HEAD_BG);
            c.setBorderColor(GRID);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            c.setPadding(3);
            t.addCell(c);
        }
        return t;
    }

    private void cell(PdfPTable t, String text, Font f, int align, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, f));
        c.setHorizontalAlignment(align);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setBorderColor(GRID);
        c.setPadding(3);
        if (bg != null) c.setBackgroundColor(bg);
        t.addCell(c);
    }

    private void sigCell(PdfPTable t, Signature s) {
        PdfPCell c = new PdfPCell(new Phrase(s == null ? "—" : s.user, s == null ? fMuted : fSig));
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setBorderColor(GRID);
        c.setPadding(3);
        t.addCell(c);
    }

    private void infoCell(PdfPTable t, String label, String value) {
        Phrase ph = new Phrase();
        ph.add(new Chunk(label + "\n", fMuted));
        ph.add(new Chunk(value, fBold));
        PdfPCell c = new PdfPCell(ph);
        c.setBorderColor(GRID);
        c.setBackgroundColor(new Color(0xf8, 0xfa, 0xfc));
        c.setPadding(4);
        t.addCell(c);
    }

    private void jobCell(PdfPTable t, String label, int value, Color bg) {
        Phrase ph = new Phrase();
        ph.add(new Chunk(label + "\n", fBody));
        ph.add(new Chunk(value + " 支", new Font(bf, 12, Font.BOLD, BLUE)));
        PdfPCell c = new PdfPCell(ph);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setBorderColor(GRID);
        c.setPadding(5);
        if (bg != null) c.setBackgroundColor(bg);
        t.addCell(c);
    }

    private void stampCell(PdfPTable t, String label, Signature s) {
        Phrase ph = new Phrase();
        ph.add(new Chunk(label + "\n\n", fMuted));
        ph.add(new Chunk(s == null ? "—" : s.user, new Font(bf, 11, Font.BOLD, GREEN)));
        ph.add(new Chunk("\n" + (s == null || s.time == null ? " " : s.time.format(LogicalDate.HUMAN)), fMuted));
        PdfPCell c = new PdfPCell(ph);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setBorderColor(BLUE);
        c.setBorderWidth(1.2f);
        c.setPadding(10);
        c.setMinimumHeight(60);
        t.addCell(c);
    }

    private static String equipStatus(EquipItem e) {
        if (EquipType.DMS.key().equals(e.type)) return e.count.isEmpty() ? "—" : e.count + " 台";
        if (EquipType.STATUS.key().equals(e.type)) return dash(e.status);
        return dash(e.notify);
    }

    private static String sigText(Signature s) {
        return s == null ? "—" : s.user;
    }

    private static String dash(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }

    /** Page number footer. */
    private final class Footer extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle r = document.getPageSize();
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_CENTER,
                    new Phrase("第 " + writer.getPageNumber() + " 頁", fMuted), (r.getLeft() + r.getRight()) / 2, r.getBottom() + 22, 0);
        }
    }
}
