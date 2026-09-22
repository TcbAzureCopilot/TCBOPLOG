package com.machineroom.web;

import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import com.machineroom.config.AppConfig;
import com.machineroom.db.SchemaInitializer;
import com.machineroom.pdf.PdfFonts;

/** Start-up: load config, verify / initialise the database, warm the PDF font. */
public class AppContextListener implements ServletContextListener {

    private static final Logger LOG = Logger.getLogger(AppContextListener.class.getName());

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        AppConfig cfg = AppConfig.get();
        LOG.info("=== " + cfg.appTitle() + " starting; config=" + (cfg.file() == null ? "none" : cfg.file().getAbsolutePath())
                + " auth.mode=" + cfg.authMode() + " db.jndiName=" + cfg.dbJndiName() + " ===");
        if (cfg.isDevAuth()) {
            LOG.warning("auth.mode=dev — NOT for production. Set auth.mode=ldap in app.properties.");
        }
        SchemaInitializer.run();
        PdfFonts.warmUp();
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        LOG.info("=== application stopped ===");
    }
}
