package com.batchauto.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.batchauto.errors.BatchAutoException.ConfigException;
import com.batchauto.errors.BatchAutoException.InfraException;
import com.batchauto.errors.BatchAutoException.QueryTimeoutException;

/**
 * Read-only JDBC access. Connections are opened read-only (SQLite at the driver level with
 * SQLITE_OPEN_READONLY, other engines via Connection#setReadOnly) and every statement passes
 * {@link SqlGuard}. Parameters are always bound; identifiers come only from validated config.
 */
public final class ReadOnlyDatabase {

    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection c) throws SQLException;
    }

    private final String url;
    private final String displayUrl;
    private final int timeoutSeconds;
    private final boolean readOnly;
    private final boolean sqlite;

    public ReadOnlyDatabase(String url, double queryTimeoutSeconds, boolean readOnly) {
        this.url = url;
        this.displayUrl = SqlGuard.redactUrl(url);
        this.timeoutSeconds = (int) Math.max(1, Math.ceil(queryTimeoutSeconds));
        this.readOnly = readOnly;
        this.sqlite = url.startsWith("jdbc:sqlite:");
    }

    public String displayUrl() { return displayUrl; }

    public boolean isSqlite() { return sqlite; }

    Connection open() throws SQLException {
        Properties props = new Properties();
        if (sqlite) {
            org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
            cfg.setReadOnly(readOnly);
            cfg.setBusyTimeout(15_000);
            props = cfg.toProperties();
        }
        Connection c = DriverManager.getConnection(url, props);
        if (!sqlite && readOnly) {
            c.setReadOnly(true);
        }
        return c;
    }

    public List<Map<String, Object>> query(String sql, Map<String, ?> params) {
        return withConnection(c -> query(c, sql, params));
    }

    public List<Map<String, Object>> query(Connection c, String sql, Map<String, ?> params) throws SQLException {
        NamedSql named = NamedSql.parse(SqlGuard.assertReadOnly(sql));
        try (PreparedStatement ps = c.prepareStatement(named.jdbcSql())) {
            ps.setQueryTimeout(timeoutSeconds);
            for (int i = 0; i < named.names().size(); i++) {
                String n = named.names().get(i);
                if (!params.containsKey(n)) {
                    throw new ConfigException("SQL parameter :" + n + " has no value");
                }
                ps.setObject(i + 1, params.get(n));
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
            return rows;
        } catch (SQLTimeoutException e) {
            throw new QueryTimeoutException("query exceeded " + timeoutSeconds + "s", e);
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).toLowerCase().contains("interrupt")) {
                throw new QueryTimeoutException("query exceeded " + timeoutSeconds + "s", e);
            }
            throw e;
        }
    }

    /** One connection + one transaction, so a group of reads sees a consistent state. */
    public <T> T snapshot(SqlWork<T> work) {
        return withConnection(c -> {
            c.setAutoCommit(false);
            if (!sqlite) {
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            }
            try {
                return work.apply(c);
            } finally {
                c.rollback(); // read-only: nothing to keep
            }
        });
    }

    private <T> T withConnection(SqlWork<T> work) {
        try (Connection c = open()) {
            return work.apply(c);
        } catch (SQLException e) {
            throw new InfraException("database error (" + displayUrl + "): " + e.getMessage(), e);
        }
    }
}
