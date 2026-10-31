package com.batchauto.simulator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stand-in for the real batch application, for local runs and tests. Watches the inbound folders,
 * registers an execution, applies its own business rules, writes targets and rejects, and marks the
 * execution terminal. It deliberately shares no code with the framework.
 *
 * Behaviour switches (substring of the file name): hang (stays RUNNING → TIMED_OUT),
 * ignore (never picked up → NOT_FOUND), crash (rolls back → FAILED).
 * Defect injection: wrong_segment, skip_last, lowercase_country.
 */
public final class SimulatedBatchProcessor implements AutoCloseable {

    public record Behaviour(Set<String> hang, Set<String> ignore, Set<String> crash, Set<String> bugs) {
        public static Behaviour normal() { return new Behaviour(Set.of(), Set.of(), Set.of(), Set.of()); }
        public Behaviour withHang(String s) { return new Behaviour(Set.of(s), ignore, crash, bugs); }
        public Behaviour withIgnore(String s) { return new Behaviour(hang, Set.of(s), crash, bugs); }
        public Behaviour withCrash(String s) { return new Behaviour(hang, ignore, Set.of(s), bugs); }
        public Behaviour withBug(String b) { return new Behaviour(hang, ignore, crash, Set.of(b)); }
    }

    private static final String NS = "urn:example:batch:customer:v1";
    private static final Set<String> CURRENCIES = Set.of("INR", "USD", "EUR", "GBP");
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final String url;
    private final Path root;
    private final Behaviour behaviour;
    private final Set<String> seen = new HashSet<>();
    private final Map<String, String> routes = new LinkedHashMap<>();
    private Thread thread;
    private volatile boolean running;

    public SimulatedBatchProcessor(Path dbFile, Path storageRoot, Behaviour behaviour) {
        this.url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        this.root = storageRoot;
        this.behaviour = behaviour;
        routes.put("inbound/customer", "customer-import");
        routes.put("inbound/payments", "payment-import");
    }

    private static String now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private Connection conn() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (var s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout = 15000");
        }
        return c;
    }

    // ---------------------------------------------------------------- lifecycle
    public SimulatedBatchProcessor start(long intervalMs) {
        running = true;
        thread = new Thread(() -> {
            while (running) {
                processPending();
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "sim-batch");
        thread.setDaemon(true);
        thread.start();
        return this;
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public synchronized int processPending() {
        int n = 0;
        for (Map.Entry<String, String> route : routes.entrySet()) {
            Path folder = root.resolve(route.getKey());
            if (!Files.isDirectory(folder)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> s = Files.list(folder)) {
                files = s.filter(Files::isRegularFile).sorted().toList();
            } catch (IOException e) {
                continue;
            }
            for (Path f : files) {
                String name = f.getFileName().toString();
                if (name.endsWith(".part") || !seen.add(name) || alreadyRegistered(name) || matches(behaviour.ignore(), name)) {
                    continue;
                }
                process(f, route.getValue());
                n++;
            }
        }
        return n;
    }

    private static boolean matches(Set<String> patterns, String name) {
        return patterns.stream().anyMatch(name::contains);
    }

    private boolean alreadyRegistered(String name) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement("SELECT 1 FROM batch_execution_status WHERE file_name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private record Outcome(int inserted, int updated, List<String[]> rejects) { }

    private void process(Path file, String jobId) {
        String exec = "EX-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        String name = file.getFileName().toString();
        try (Connection c = conn()) {
            exec(c, "INSERT INTO batch_execution_status (execution_id, job_id, file_name, status, started_at) VALUES (?,?,?,?,?)",
                    exec, jobId, name, "QUEUED", now());
            exec(c, "UPDATE batch_execution_status SET status='RUNNING' WHERE execution_id=?", exec);
            if (matches(behaviour.hang(), name)) {
                return;
            }
            List<Map<String, String>> records;
            try {
                byte[] data = Files.readAllBytes(file);
                records = "customer-import".equals(jobId) ? parseCustomers(data) : parsePayments(data);
            } catch (Exception e) {
                finish(c, exec, "FAILED", 0, 0, 0, 0, 0, "MALFORMED_FILE: " + e.getMessage());
                return;
            }
            c.setAutoCommit(false);
            Outcome o;
            try {
                if (matches(behaviour.crash(), name)) {
                    throw new IllegalStateException("simulated processing crash");
                }
                o = "customer-import".equals(jobId) ? applyCustomers(c, records, name) : applyPayments(c, records, name);
                for (String[] r : o.rejects()) {
                    exec(c, "INSERT INTO batch_reject VALUES (?,?,?,?)", exec, r[0], r[1], null);
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                c.setAutoCommit(true);
                finish(c, exec, "FAILED", records.size(), 0, 0, 0, 0, "PROCESSING_ERROR: " + e.getMessage());
                return;
            }
            c.setAutoCommit(true);
            finish(c, exec, o.rejects().isEmpty() ? "COMPLETED" : "COMPLETED_WITH_REJECTS", records.size(),
                    o.inserted() + o.updated(), o.inserted(), o.updated(), o.rejects().size(), null);
        } catch (SQLException e) {
            throw new IllegalStateException("simulator database error: " + e.getMessage(), e);
        }
    }

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static void finish(Connection c, String exec, String status, int read, int processed, int ins, int upd, int rej,
                               String err) throws SQLException {
        exec(c, "UPDATE batch_execution_status SET status=?, completed_at=?, records_read=?, records_processed=?, "
                + "records_inserted=?, records_updated=?, records_rejected=?, error_message=? WHERE execution_id=?",
                status, now(), read, processed, ins, upd, rej, err, exec);
    }

    // ---------------------------------------------------------------- customers
    private static List<Map<String, String>> parseCustomers(byte[] data) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(data));
        NodeList customers = doc.getElementsByTagNameNS(NS, "Customer");
        List<Map<String, String>> out = new ArrayList<>();
        for (int i = 0; i < customers.getLength(); i++) {
            Element el = (Element) customers.item(i);
            Map<String, String> r = new LinkedHashMap<>();
            r.put("customer_id", el.getAttribute("customerId"));
            r.put("first_name", text(el, "FirstName"));
            r.put("last_name", text(el, "LastName"));
            r.put("email", text(el, "Email"));
            r.put("dob", text(el, "DateOfBirth"));
            r.put("country", text(el, "Country"));
            r.put("credit_limit", text(el, "CreditLimit"));
            out.add(r);
        }
        return out;
    }

    private static String text(Element parent, String tag) {
        NodeList n = parent.getElementsByTagNameNS(NS, tag);
        Node node = n.getLength() == 0 ? null : n.item(0);
        return node == null ? null : node.getTextContent();
    }

    private Outcome applyCustomers(Connection c, List<Map<String, String>> records, String file) throws SQLException {
        List<String[]> rejects = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        List<Object[]> accepted = new ArrayList<>();
        BigDecimal threshold = new BigDecimal(behaviour.bugs().contains("wrong_segment") ? "50000" : "100000");
        for (Map<String, String> r : records) {
            String key = r.get("customer_id");
            if (!seenKeys.add(key)) {
                rejects.add(new String[] {key, "DUPLICATE_IN_FILE"});
                continue;
            }
            BigDecimal limit;
            try {
                limit = new BigDecimal(r.get("credit_limit"));
            } catch (RuntimeException e) {
                rejects.add(new String[] {key, "INVALID_CREDIT_LIMIT"});
                continue;
            }
            if (limit.signum() < 0) {
                rejects.add(new String[] {key, "NEGATIVE_CREDIT_LIMIT"});
                continue;
            }
            if (r.get("email") == null || !r.get("email").contains("@")) {
                rejects.add(new String[] {key, "INVALID_EMAIL"});
                continue;
            }
            accepted.add(new Object[] {r, limit});
        }
        boolean skipped = behaviour.bugs().contains("skip_last") && !accepted.isEmpty();
        if (skipped) {
            accepted.remove(accepted.size() - 1);
        }
        int ins = 0;
        int upd = 0;
        for (Object[] a : accepted) {
            @SuppressWarnings("unchecked")
            Map<String, String> r = (Map<String, String>) a[0];
            BigDecimal limit = (BigDecimal) a[1];
            String full = String.join(" ", ((r.get("first_name") == null ? "" : r.get("first_name")) + " "
                    + (r.get("last_name") == null ? "" : r.get("last_name"))).trim().split("\\s+"));
            String country = behaviour.bugs().contains("lowercase_country") ? r.get("country") : r.get("country").toUpperCase(Locale.ROOT);
            String segment = limit.compareTo(threshold) >= 0 ? "PREMIUM" : "STANDARD";
            String amount = limit.setScale(2, RoundingMode.HALF_UP).toPlainString();
            boolean exists;
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM customer WHERE customer_id=?")) {
                ps.setString(1, r.get("customer_id"));
                try (ResultSet rs = ps.executeQuery()) {
                    exists = rs.next();
                }
            }
            if (exists) {
                exec(c, "UPDATE customer SET full_name=?, email=?, date_of_birth=?, country=?, credit_limit=?, segment=?, "
                        + "updated_at=?, last_batch_file=? WHERE customer_id=?",
                        full, r.get("email").toLowerCase(Locale.ROOT), r.get("dob"), country, amount, segment, now(), file,
                        r.get("customer_id"));
                upd++;
            } else {
                exec(c, "INSERT INTO customer (customer_id, full_name, email, date_of_birth, country, credit_limit, segment, "
                        + "created_at, updated_at, last_batch_file) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        r.get("customer_id"), full, r.get("email").toLowerCase(Locale.ROOT), r.get("dob"), country, amount,
                        segment, now(), now(), file);
                ins++;
            }
        }
        if (skipped) {
            ins++; // the defect: counted but never written
        }
        return new Outcome(ins, upd, rejects);
    }

    // ---------------------------------------------------------------- payments
    private static List<Map<String, String>> parsePayments(byte[] data) throws IOException {
        JsonNode doc = JSON.readTree(new String(data, StandardCharsets.UTF_8));
        if (doc == null || !doc.path("payments").isArray()) {
            throw new IOException("no payments array");
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode p : doc.get("payments")) {
            Map<String, String> r = new LinkedHashMap<>();
            r.put("payment_ref", p.path("paymentRef").asText(null));
            r.put("account", p.path("debitAccount").asText(null));
            r.put("amount", p.path("amount").isNumber() ? p.path("amount").decimalValue().toPlainString() : null);
            r.put("currency", p.path("currency").asText(null));
            r.put("value_date", p.path("valueDate").asText(null));
            out.add(r);
        }
        return out;
    }

    private Outcome applyPayments(Connection c, List<Map<String, String>> records, String file) throws SQLException {
        List<String[]> rejects = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        int ins = 0;
        for (Map<String, String> p : records) {
            String key = p.get("payment_ref");
            if (!seenKeys.add(key)) {
                rejects.add(new String[] {key, "DUPLICATE_IN_FILE"});
                continue;
            }
            BigDecimal amount = p.get("amount") == null ? null : new BigDecimal(p.get("amount"));
            if (amount == null || amount.signum() <= 0) {
                rejects.add(new String[] {key, "INVALID_AMOUNT"});
                continue;
            }
            if (!CURRENCIES.contains(p.get("currency"))) {
                rejects.add(new String[] {key, "INVALID_CURRENCY"});
                continue;
            }
            boolean exists;
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM payment_ledger WHERE payment_ref=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    exists = rs.next();
                }
            }
            if (exists) {
                rejects.add(new String[] {key, "DUPLICATE_PAYMENT"});
                continue;
            }
            exec(c, "INSERT INTO payment_ledger VALUES (?,?,?,?,?,?,?,?)", key, p.get("account"),
                    amount.setScale(2, RoundingMode.HALF_UP).toPlainString(), p.get("currency"), p.get("value_date"),
                    "POSTED", file, now());
            ins++;
        }
        return new Outcome(ins, 0, rejects);
    }
}
