package com.machineroom.pdf;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.lowagie.text.pdf.BaseFont;
import com.machineroom.config.AppConfig;

/**
 * Locates and caches the CJK font for PDF output. {@code pdf.fontPaths} lists candidates separated by ';'
 * (a TTC needs its index: {@code C:/Windows/Fonts/msjh.ttc,0}). Falls back to Helvetica — which cannot
 * render Chinese — with a loud warning, so a missing font is visible in the logs rather than a crash.
 */
public final class PdfFonts {

    private static final Logger LOG = Logger.getLogger(PdfFonts.class.getName());
    private static volatile BaseFont cached;
    private static volatile String cachedKey;

    private PdfFonts() {
    }

    public static void warmUp() {
        try {
            BaseFont f = base();
            LOG.info("PDF font: " + f.getPostscriptFontName());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "PDF font warm-up failed", e);
        }
    }

    public static synchronized BaseFont base() {
        AppConfig cfg = AppConfig.get();
        String key = String.valueOf(cfg.pdfFontPaths());
        if (cached != null && key.equals(cachedKey)) return cached;
        BaseFont found = null;
        for (String candidate : cfg.pdfFontPaths()) {
            String path = candidate.trim();
            String file = path;
            int comma = path.lastIndexOf(',');
            if (comma > 0 && path.substring(comma + 1).trim().matches("\\d+")) file = path.substring(0, comma);
            if (!new File(file).isFile()) continue;
            try {
                found = BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                LOG.info("Using PDF font " + path);
                if (file.toLowerCase(java.util.Locale.ROOT).endsWith(".otf")) {
                    LOG.warning("PDF font is a CFF .otf; OpenPDF subsetting may corrupt some glyphs. "
                            + "Prefer a TrueType font (.ttf / .ttc such as msjh.ttc or NotoSansTC-Regular.ttf).");
                }
                break;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Cannot load PDF font " + path, e);
            }
        }
        if (found == null) {
            LOG.severe("No usable CJK font found in pdf.fontPaths=" + cfg.pdfFontPaths()
                    + " — PDF output will not show Chinese characters. Install a TTF/OTF and set pdf.fontPaths.");
            try {
                found = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        cached = found;
        cachedKey = key;
        return found;
    }
}
