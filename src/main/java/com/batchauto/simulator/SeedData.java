package com.batchauto.simulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates and seeds the demo application database (SQLite).
 * customers_src / payments_src are the source systems; customer / payment_ledger the targets;
 * batch_execution_status is the authoritative status table; batch_reject holds per-record rejections.
 */
public final class SeedData {

    private static final String[] SCHEMA = {
        """
        CREATE TABLE customers_src (customer_id TEXT PRIMARY KEY, first_name TEXT NOT NULL, last_name TEXT NOT NULL,
          email TEXT, date_of_birth TEXT NOT NULL, country_code TEXT NOT NULL, credit_limit NUMERIC NOT NULL, status TEXT NOT NULL)""",
        """
        CREATE TABLE customer (customer_id TEXT PRIMARY KEY, full_name TEXT NOT NULL, email TEXT NOT NULL,
          date_of_birth TEXT NOT NULL, country TEXT NOT NULL, credit_limit NUMERIC NOT NULL, segment TEXT NOT NULL,
          created_at TEXT NOT NULL, updated_at TEXT NOT NULL, last_batch_file TEXT)""",
        """
        CREATE TABLE payments_src (payment_ref TEXT PRIMARY KEY, debit_account TEXT NOT NULL, amount NUMERIC NOT NULL,
          currency TEXT NOT NULL, value_date TEXT NOT NULL, narrative TEXT, status TEXT NOT NULL)""",
        """
        CREATE TABLE payment_ledger (payment_ref TEXT PRIMARY KEY, account_no TEXT NOT NULL, amount NUMERIC NOT NULL,
          currency TEXT NOT NULL, value_date TEXT NOT NULL, status TEXT NOT NULL, batch_file TEXT, posted_at TEXT NOT NULL)""",
        """
        CREATE TABLE batch_execution_status (execution_id TEXT PRIMARY KEY, job_id TEXT NOT NULL, file_name TEXT NOT NULL,
          status TEXT NOT NULL, started_at TEXT, completed_at TEXT, records_read INTEGER, records_processed INTEGER,
          records_inserted INTEGER, records_updated INTEGER, records_rejected INTEGER, error_message TEXT)""",
        "CREATE INDEX ix_status_file ON batch_execution_status(file_name)",
        """
        CREATE TABLE batch_reject (execution_id TEXT NOT NULL, record_key TEXT NOT NULL, reason_code TEXT NOT NULL, detail TEXT)"""
    };

    // id, first, last, email, dob, country, limit, status
    private static final Object[][] CUSTOMERS = {
        {"C000001", "Aarav", "Sharma", "Aarav.Sharma@example.com", "1988-02-14", "IN", 75000, "ACTIVE"},
        {"C000002", "Priya", "Reddy", "priya.reddy@example.com", "1991-07-30", "IN", 150000, "ACTIVE"},
        {"C000003", "John", "Miller", "john.miller@example.com", "1979-11-03", "US", 99999.99, "ACTIVE"},
        {"C000004", "Old", "Account", "old.account@example.com", "1965-01-01", "GB", 1000, "INACTIVE"},
        {"C000005", "Sara", "O'Neil", "sara.oneil@example.com", "1985-05-21", "GB", 100000, "ACTIVE"},
        {"C000006", "Ravi", "Kumar", "ravi.kumar@example.com", "1993-09-09", "IN", 25000.5, "ACTIVE"},
        {"C000007", "No", "Email", null, "1990-01-01", "IN", 5000, "ACTIVE"},
        {"C000008", "Meera", "Iyer", "meera.iyer@example.com", "1987-12-12", "IN", 220000, "ACTIVE"},
        {"C000009", "Tom", "Baker", "tom.baker@example.com", "1970-04-04", "US", 0, "ACTIVE"},
        {"C000010", "Jean", "Dupont", "jean.dupont@example.com", "1982-06-06", "FR", 40000, "ACTIVE"},
        {"C000011", "Anil", "Rao", "anil.rao@example.com", "1995-03-15", "in", 60000, "ACTIVE"},
        {"C000012", "Lakshmi", "Devi", "lakshmi.devi@example.com", "1999-08-08", "IN", 130000, "ACTIVE"},
    };

    /** Already in the customer master (update path) plus two unrelated rows that must never change. */
    private static final Object[][] EXISTING_CUSTOMERS = {
        {"C000002", "Priya R", "old.priya@example.com", "1991-07-30", "IN", 90000, "STANDARD"},
        {"C000005", "Sara ONeil", "sara.old@example.com", "1985-05-21", "GB", 50000, "STANDARD"},
        {"C000008", "Meera Iyer", "meera.iyer@example.com", "1987-12-12", "IN", 220000, "PREMIUM"},
        {"C900001", "Unrelated One", "u1@example.com", "1960-01-01", "US", 1, "STANDARD"},
        {"C900002", "Unrelated Two", "u2@example.com", "1961-01-01", "GB", 2, "STANDARD"},
    };

    private static final Object[][] PAYMENTS = {
        {"PAY00000001", "123456789012", 1500.00, "INR", "2026-10-26", "Vendor invoice 1001", "PENDING_EXPORT"},
        {"PAY00000002", "123456789013", 250.75, "USD", "2026-10-26", null, "PENDING_EXPORT"},
        {"PAY00000003", "123456789014", 99000.10, "INR", "2026-10-27", "Rent & maintenance", "PENDING_EXPORT"},
        {"PAY00000004", "123456789015", 10.00, "EUR", "2026-10-27", "Already exported", "EXPORTED"},
        {"PAY00000005", "123456789016", 7800.00, "GBP", "2026-10-28", "Salary advance", "PENDING_EXPORT"},
        {"PAY00000006", "123456789017", 300.00, "INR", "2026-10-28", "Already in ledger", "PENDING_EXPORT"},
        {"PAY00000007", "123456789018", 42.42, "USD", "2026-10-29", "Subscription", "PENDING_EXPORT"},
        {"PAY00000008", "123456789019", 1.00, "INR", "2026-10-29", "Test penny", "PENDING_EXPORT"},
        {"PAY00000009", "123456789020", 0.00, "INR", "2026-10-30", "Zero amount - ineligible", "PENDING_EXPORT"},
        {"PAY00000010", "123456789021", 5000.00, "INR", "2026-10-30", "Utility bill", "PENDING_EXPORT"},
    };

    private static final Object[][] LEDGER = {
        {"PAY00000006", "123456789017", 300.00, "INR", "2026-10-28", "POSTED", "PAYIMP_20261001_seed.json"},
        {"PAY99999999", "999999999999", 1.00, "INR", "2026-01-01", "POSTED", "PAYIMP_20260101_seed.json"},
    };

    private SeedData() { }

    public static Path seed(Path dbFile) {
        try {
            Files.createDirectories(dbFile.toAbsolutePath().getParent());
            Files.deleteIfExists(dbFile);
        } catch (IOException e) {
            throw new IllegalStateException("cannot prepare " + dbFile, e);
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                for (String ddl : SCHEMA) {
                    s.execute(ddl);
                }
            }
            insert(c, "INSERT INTO customers_src VALUES (?,?,?,?,?,?,?,?)", CUSTOMERS);
            insert(c, "INSERT INTO customer VALUES (?,?,?,?,?,?,?,'2026-01-01T00:00:00','2026-01-01T00:00:00','CUSTIMP_seed.xml')",
                    EXISTING_CUSTOMERS);
            insert(c, "INSERT INTO payments_src VALUES (?,?,?,?,?,?,?)", PAYMENTS);
            insert(c, "INSERT INTO payment_ledger VALUES (?,?,?,?,?,?,?,'2026-10-01T00:00:00')", LEDGER);
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("seeding failed: " + e.getMessage(), e);
        }
        return dbFile;
    }

    private static void insert(Connection c, String sql, Object[][] rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Object[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    ps.setObject(i + 1, row[i]);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
