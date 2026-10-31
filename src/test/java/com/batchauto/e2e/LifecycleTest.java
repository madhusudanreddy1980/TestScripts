package com.batchauto.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.batchauto.errors.BatchAutoException.SubmissionBlockedException;
import com.batchauto.errors.ExitCodes;
import com.batchauto.reconcile.Assertion;
import com.batchauto.reconcile.RecordExpectation;
import com.batchauto.runner.SubmissionLedger;
import com.batchauto.runner.TestRun;
import com.batchauto.simulator.SimulatedBatchProcessor.Behaviour;
import com.batchauto.support.TestWorkspace;
import com.batchauto.support.TestWorkspace.Executed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** AC06–AC10, AC12: the full lifecycle against the simulated batch application. */
@Tag("e2e")
class LifecycleTest {

    @TempDir Path tmp;
    TestWorkspace ws;

    @BeforeEach
    void setUp() {
        ws = new TestWorkspace(tmp);
    }

    static Map<String, String> statuses(TestRun r) {
        return r.assertions().stream().collect(Collectors.toMap(Assertion::id, Assertion::status, (a, b) -> a));
    }

    static String failed(TestRun r) {
        return r.assertions().stream().filter(a -> Assertion.FAIL.equals(a.status()))
                .map(a -> a.id() + " " + a.rule() + " expected=" + a.expected() + " actual=" + a.actual() + " " + a.diffs())
                .collect(Collectors.joining("\n"));
    }

    // ----------------------------------------------------------- happy paths
    @Test
    void customerUpsertPositive() {
        Executed e = ws.run("customer-import", "positive", 3);
        assertThat(e.result().result()).as(failed(e.run())).isEqualTo("PASS");
        assertThat(e.result().exitCode()).isEqualTo(ExitCodes.PASS);
        assertThat(e.run().expectations()).extracting(RecordExpectation::action).containsExactly("insert", "update", "insert");
        assertThat(e.run().outcome().status()).isEqualTo("COMPLETED");
        assertThat(e.run().outcome().counts()).containsEntry("records_inserted", 2);
        assertThat(e.run().uploadVerified()).isTrue();
        assertThat(statuses(e.run())).containsEntry("C-C000002", "PASS").containsEntry("C10", "PASS");
    }

    @Test
    void paymentInsertPositive() {
        Executed e = ws.run("payment-import", "positive", 4);
        assertThat(e.result().result()).as(failed(e.run())).isEqualTo("PASS");
        assertThat(e.run().expectations()).allMatch(x -> x.action().equals("insert"));
    }

    @ParameterizedTest
    @CsvSource({
        "customer-import,boundary_max_credit,2",
        "customer-import,boundary_zero_credit,2",
        "customer-import,special_characters,1",
        "customer-import,unknown_customer,2",
        "payment-import,boundary_large_amount,2",
        "payment-import,synthetic_payments,3",
    })
    void positiveVariants(String job, String scenario, int count) {
        Executed e = ws.run(job, scenario, count);
        assertThat(e.result().result()).as(failed(e.run())).isEqualTo("PASS");
    }

    // ----------------------------------------------------------- partial rejection
    @ParameterizedTest
    @CsvSource({
        "customer-import,partial_rejection,NEGATIVE_CREDIT_LIMIT",
        "customer-import,duplicate_records,DUPLICATE_IN_FILE",
        "payment-import,partial_rejection,INVALID_CURRENCY",
        "payment-import,zero_amount,INVALID_AMOUNT",
        "payment-import,duplicate_records,DUPLICATE_IN_FILE",
    })
    void partialRejectionIsReconciled(String job, String scenario, String reason) {
        Executed e = ws.run(job, scenario, 3);
        assertThat(e.result().result()).as(failed(e.run())).isEqualTo("PASS");
        List<RecordExpectation> rejected = e.run().expectations().stream().filter(x -> x.action().equals("reject")).toList();
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).reason()).isEqualTo(reason);
        assertThat(e.run().outcome().appStatus()).isEqualTo("COMPLETED_WITH_REJECTS");
        assertThat(e.run().assertions()).anyMatch(a -> a.category().equals("REJECTS") && a.status().equals("PASS")
                && reason.equals(a.expected()));
    }

    @Test
    void rerunUpdatesInsteadOfInserting() {
        Executed first = ws.run("customer-import", "positive", 2);
        Executed second = ws.run("customer-import", "positive", 2);
        assertThat(first.result().result()).isEqualTo("PASS");
        assertThat(second.result().result()).as(failed(second.run())).isEqualTo("PASS");
        assertThat(second.run().expectations()).extracting(RecordExpectation::action).containsOnly("update");
    }

    // ----------------------------------------------------------- blocked / failed
    @Test
    void blockedScenarioPassesWithoutSubmission() {
        Executed e = ws.run("customer-import", "missing_mandatory", 2);
        assertThat(e.result().result()).isEqualTo("PASS");
        assertThat(e.run().submitted()).isFalse();
        assertThat(e.run().state().name()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void malformedFileFailsBatchWithoutPartialWrites() {
        Executed e = ws.run("customer-import", "malformed_file", 3);
        assertThat(e.run().outcome().status()).isEqualTo("FAILED");
        assertThat(e.run().outcome().errorMessage()).contains("MALFORMED_FILE");
        assertThat(e.result().result()).as(failed(e.run())).isEqualTo("PASS");
        assertThat(statuses(e.run())).containsEntry("D-nochange", "PASS");
    }

    @Test
    void processingCrashReportsFailedAndRollsBack() {
        Executed e = ws.run("payment-import", TestRun.Options.defaults().count(2), Behaviour.normal().withCrash("PAYIMP"));
        assertThat(e.run().outcome().status()).isEqualTo("FAILED");
        assertThat(e.run().outcome().errorMessage()).contains("PROCESSING_ERROR");
        assertThat(e.result().result()).isEqualTo("FAIL");
        assertThat(statuses(e.run())).containsEntry("A1", "FAIL");
    }

    // ----------------------------------------------------------- AC08
    @Test
    void timeoutIsDistinct() {
        ws.fw.setMonitorOverrides(Map.of("polling_interval_seconds", 0.05, "timeout_seconds", 0.5));
        Executed e = ws.run("customer-import", TestRun.Options.defaults().count(1), Behaviour.normal().withHang("CUSTIMP"));
        assertThat(e.result().result()).isEqualTo("TIMED_OUT");
        assertThat(e.result().exitCode()).isEqualTo(ExitCodes.TIMEOUT);
        assertThat(e.run().outcome().appStatus()).isEqualTo("RUNNING");
    }

    @Test
    void missingStatusIsDistinct() {
        ws.fw.setMonitorOverrides(Map.of("polling_interval_seconds", 0.05, "timeout_seconds", 0.5));
        Executed e = ws.run("customer-import", TestRun.Options.defaults().count(1), Behaviour.normal().withIgnore("CUSTIMP"));
        assertThat(e.result().result()).isEqualTo("NOT_FOUND");
        assertThat(e.result().exitCode()).isEqualTo(ExitCodes.TIMEOUT);
        assertThat(e.run().outcome().executionId()).isNull();
    }

    // ----------------------------------------------------------- AC09 defect detection
    @ParameterizedTest
    @ValueSource(strings = {"wrong_segment", "skip_last", "lowercase_country"})
    void reconciliationCatchesInjectedDefects(String bug) {
        // count=8 includes C000006 (25000.50: STANDARD, PREMIUM under the bug) and C000011 (country 'in')
        Executed e = ws.run("customer-import", TestRun.Options.defaults().count(8), Behaviour.normal().withBug(bug));
        assertThat(e.result().result()).isEqualTo("FAIL");
        assertThat(e.result().exitCode()).isEqualTo(ExitCodes.TEST_FAILURE);
        Set<String> failing = e.run().assertions().stream().filter(a -> a.status().equals("FAIL"))
                .map(Assertion::category).collect(Collectors.toSet());
        assertThat(failing).as(failed(e.run())).contains("DATA_INTEGRITY");
    }

    @Test
    void skippedRowIsCaughtByTableCountEvenWhenBatchCountersAgree() {
        Executed e = ws.run("customer-import", TestRun.Options.defaults().count(8), Behaviour.normal().withBug("skip_last"));
        Map<String, String> s = statuses(e.run());
        assertThat(e.run().assertions()).filteredOn(a -> a.rule().equals("records_inserted == expected_inserted"))
                .allMatch(a -> a.status().equals("PASS"));
        assertThat(e.run().assertions()).filteredOn(a -> a.rule().startsWith("target_rows_after"))
                .allMatch(a -> a.status().equals("FAIL"));
        assertThat(s).containsEntry("C-C000011", "FAIL");
    }

    // ----------------------------------------------------------- idempotency
    @Test
    void sameRunCannotBeSubmittedTwice() {
        Executed first = ws.run("payment-import", TestRun.Options.defaults().count(1).runId("20261024T101010-0a0a0a"), Behaviour.normal());
        assertThat(first.result().result()).isEqualTo("PASS");
        TestRun again = new TestRun(ws.fw, "payment-import", TestRun.Options.defaults().count(1).runId("20261024T101010-0a0a0a"));
        var res = again.execute(true, true);
        assertThat(res.result()).isEqualTo("ERROR");
        assertThat(res.exitCode()).isEqualTo(ExitCodes.CONFIG_ERROR);
        assertThat(again.error()).isInstanceOf(SubmissionBlockedException.class).hasMessageContaining("already submitted");
    }

    @Test
    void identicalContentIsNotResubmitted() {
        SubmissionLedger ledger = new SubmissionLedger(ws.fw.reportsDir());
        ledger.claim("r1", "payment-import", "local", "abc", false);
        assertThatThrownBy(() -> ledger.claim("r2", "payment-import", "local", "abc", false))
                .isInstanceOf(SubmissionBlockedException.class).hasMessageContaining("identical file");
        ledger.claim("r3", "payment-import", "local", "abc", true);
    }

    // ----------------------------------------------------------- AC10
    @Test
    void ac10_reportTraceabilityAndMasking() throws Exception {
        Executed e = ws.run("payment-import", "positive", 2);
        String json = Files.readString(e.result().reportJson());
        String html = Files.readString(e.result().reportHtml());
        JsonNode r = new ObjectMapper().readTree(json);
        assertThat(r.get("test_run_id").asText()).isEqualTo(e.run().runId());
        assertThat(r.get("result").asText()).isEqualTo("PASS");
        assertThat(r.at("/input_file/sha256").asText()).isEqualTo(e.run().sha256());
        assertThat(r.get("source_record_ids").toString()).isEqualTo("[\"PAY00000001\",\"PAY00000002\"]");
        assertThat(r.at("/batch/executionId").asText()).startsWith("EX-");
        assertThat(r.at("/upload/verified").asBoolean()).isTrue();
        for (JsonNode a : r.get("assertions")) {
            assertThat(a.get("query").asText()).isNotBlank();
            assertThat(a.get("status").asText()).isIn("PASS", "SKIPPED");
            assertThat(a.get("timestamp").asText()).isNotBlank();
        }
        assertThat(json + html).doesNotContain("123456789012").contains("********9012");
        assertThat(Files.readString(e.run().runDir().resolve("expectations.json"))).doesNotContain("123456789012");
        assertThat(e.run().runDir().resolve(e.run().fileName())).exists(); // the real input file is kept as evidence
        assertThat(html).contains("<table>", "data-testid=\"report-result\"");
    }
}
