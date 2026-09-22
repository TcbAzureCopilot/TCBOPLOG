package com.machineroom.boot;

import java.io.IOException;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Bean;

import com.machineroom.auth.AuthFilter;
import com.machineroom.auth.ClientIpFilter;
import com.machineroom.auth.LoginServlet;
import com.machineroom.auth.LogoutServlet;
import com.machineroom.config.AppConfig;
import com.machineroom.db.Db;
import com.machineroom.db.SchemaInitializer;
import com.machineroom.pdf.PdfFonts;
import com.machineroom.web.ApiServlet;
import com.machineroom.web.EncodingFilter;
import com.machineroom.web.PdfServlet;
import com.machineroom.web.PrintServlet;

/**
 * TCBOPLOG: the same application as the WebSphere WAR, wired into embedded Tomcat with an
 * embedded H2 database. Everything web.xml declared for WAS is registered here in code.
 */
@SpringBootApplication
public class TcbOpLogApplication extends SpringBootServletInitializer {

    private static final Logger LOG = Logger.getLogger(TcbOpLogApplication.class.getName());

    public static void main(String[] args) {
        SpringApplication.run(TcbOpLogApplication.class, args);
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(TcbOpLogApplication.class);
    }

    // ------------------------------------------------------------------ database

    /** Embedded H2 (DB2 compatibility mode) at {@code db.url}; schema/seed are created on first start. */
    @Bean
    public DataSource dataSource() {
        AppConfig cfg = AppConfig.get();
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(cfg.dbUrl());
        ds.setUser(cfg.dbUser());
        ds.setPassword(cfg.dbPassword());
        Db.setDataSource(ds);
        LOG.info("=== TCBOPLOG starting; config=" + (cfg.file() == null ? "none" : cfg.file().getAbsolutePath())
                + " auth.mode=" + cfg.authMode() + " db.url=" + cfg.dbUrl() + " ===");
        if (cfg.isBypassAuth()) {
            LOG.warning("auth.mode=bypass — passwords are NOT checked. Trial / demo use only.");
        }
        SchemaInitializer.run();
        PdfFonts.warmUp();
        return ds;
    }

    // ------------------------------------------------------------------ filters (same order as web.xml)

    @Bean
    public FilterRegistrationBean<EncodingFilter> encodingFilter() {
        FilterRegistrationBean<EncodingFilter> f = new FilterRegistrationBean<>(new EncodingFilter());
        f.addUrlPatterns("/*");
        f.setOrder(1);
        return f;
    }

    @Bean
    public FilterRegistrationBean<ClientIpFilter> clientIpFilter() {
        FilterRegistrationBean<ClientIpFilter> f = new FilterRegistrationBean<>(new ClientIpFilter());
        f.addUrlPatterns("/*");
        f.setOrder(2);
        return f;
    }

    @Bean
    public FilterRegistrationBean<AuthFilter> authFilter() {
        FilterRegistrationBean<AuthFilter> f = new FilterRegistrationBean<>(new AuthFilter());
        f.addUrlPatterns("/*");
        f.setOrder(3);
        return f;
    }

    // ------------------------------------------------------------------ servlets

    @Bean
    public ServletRegistrationBean<HttpServlet> loginServlet() {
        return new ServletRegistrationBean<HttpServlet>(new LoginServlet(), "/login");
    }

    @Bean
    public ServletRegistrationBean<HttpServlet> logoutServlet() {
        return new ServletRegistrationBean<HttpServlet>(new LogoutServlet(), "/logout");
    }

    @Bean
    public ServletRegistrationBean<HttpServlet> apiServlet() {
        return new ServletRegistrationBean<HttpServlet>(new ApiServlet(), "/api/*");
    }

    @Bean
    public ServletRegistrationBean<HttpServlet> printServlet() {
        return new ServletRegistrationBean<HttpServlet>(new PrintServlet(), "/print/*");
    }

    @Bean
    public ServletRegistrationBean<HttpServlet> pdfServlet() {
        return new ServletRegistrationBean<HttpServlet>(new PdfServlet(), "/pdf/*");
    }

    /** Context root → the SPA shell (index.jsp), replacing web.xml's welcome-file. */
    @Bean
    public ServletRegistrationBean<HttpServlet> homeServlet() {
        HttpServlet home = new HttpServlet() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
                req.getRequestDispatcher("/index.jsp").forward(req, resp);
            }
        };
        return new ServletRegistrationBean<HttpServlet>(home, "");
    }
}
