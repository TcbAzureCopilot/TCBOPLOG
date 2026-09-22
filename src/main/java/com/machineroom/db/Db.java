package com.machineroom.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import com.machineroom.config.AppConfig;

/** DataSource lookup (JNDI) and a tiny transaction helper. */
public final class Db {

    private static final Logger LOG = Logger.getLogger(Db.class.getName());
    private static volatile DataSource dataSource;

    private Db() {
    }

    /** Test hook: inject a DataSource instead of looking it up via JNDI. */
    public static void setDataSource(DataSource ds) {
        dataSource = ds;
    }

    public static DataSource dataSource() {
        DataSource ds = dataSource;
        if (ds == null) {
            synchronized (Db.class) {
                ds = dataSource;
                if (ds == null) {
                    ds = lookup();
                    dataSource = ds;
                }
            }
        }
        return ds;
    }

    private static DataSource lookup() {
        String name = AppConfig.get().dbJndiName();
        String[] candidates = {"java:comp/env/" + name, name};
        NamingException last = null;
        for (String c : candidates) {
            try {
                Object o = new InitialContext().lookup(c);
                if (o instanceof DataSource) {
                    LOG.info("DataSource bound: " + c);
                    return (DataSource) o;
                }
            } catch (NamingException e) {
                last = e;
            }
        }
        throw new IllegalStateException("DataSource not found in JNDI: " + name, last);
    }

    /** Opens a connection with auto-commit off. */
    public static Connection open() throws SQLException {
        Connection c = dataSource().getConnection();
        c.setAutoCommit(false);
        return c;
    }

    /** Unit of work executed in one transaction. */
    public interface Work<T> {
        T run(Connection c) throws Exception;
    }

    /** Runs {@code work} in a transaction: commit on success, rollback on any exception. */
    public static <T> T tx(Work<T> work) {
        Connection c = null;
        try {
            c = open();
            T result = work.run(c);
            c.commit();
            return result;
        } catch (RuntimeException e) {
            rollback(c);
            throw e;
        } catch (Exception e) {
            rollback(c);
            throw new DbException(e);
        } finally {
            if (c != null) {
                try { c.close(); } catch (SQLException e) { LOG.log(Level.FINE, "close failed", e); }
            }
        }
    }

    private static void rollback(Connection c) {
        if (c != null) {
            try { c.rollback(); } catch (SQLException e) { LOG.log(Level.WARNING, "rollback failed", e); }
        }
    }

    /** Unchecked wrapper for persistence failures. */
    public static class DbException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DbException(Throwable cause) {
            super(cause.getMessage(), cause);
        }

        public DbException(String message) {
            super(message);
        }
    }
}
