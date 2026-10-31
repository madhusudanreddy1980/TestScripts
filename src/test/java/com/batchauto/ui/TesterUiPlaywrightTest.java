package com.batchauto.ui;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;

import com.batchauto.simulator.SimulatedBatchProcessor;
import com.batchauto.simulator.SimulatedBatchProcessor.Behaviour;
import com.batchauto.support.TestWorkspace;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.assertions.PlaywrightAssertions;

/**
 * Playwright drives the tester UI exactly as a QA engineer would:
 * Select job → Fetch eligible data → Generate → Validate → Confirm + Submit → Monitor → Database validation.
 * The UI runs against a seeded SQLite database, local inbound storage and the simulated batch application.
 * A Playwright trace for every test is written to target/playwright-traces/ (open at trace.playwright.dev).
 */
@Tag("ui")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TesterUiPlaywrightTest {

    TestWorkspace ws;
    SimulatedBatchProcessor sim;
    UiServer ui;
    Playwright playwright;
    Browser browser;
    BrowserContext context;
    Page page;
    String base;

    @BeforeAll
    void startEverything() throws Exception {
        ws = new TestWorkspace(Files.createTempDirectory("batchauto-ui"));
        ws.fw.setMonitorOverrides(Map.of("polling_interval_seconds", 0.1, "timeout_seconds", 15.0));
        sim = ws.simulator(Behaviour.normal()).start(100);
        ui = new UiServer(ws.fw).start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + ui.port() + "/";
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        PlaywrightAssertions.setDefaultAssertionTimeout(20_000);
    }

    @AfterAll
    void stopEverything() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (ui != null) ui.close();
        if (sim != null) sim.close();
    }

    @BeforeEach
    void openPage() {
        context = browser.newContext(new Browser.NewContextOptions().setAcceptDownloads(true).setViewportSize(1400, 1000));
        context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
        page = context.newPage();
        page.navigate(base);
        assertThat(page.getByTestId("job-status-customer-import")).hasText("VALID");
    }

    @AfterEach
    void closePage(TestInfo info) {
        context.tracing().stop(new Tracing.StopOptions()
                .setPath(Paths.get("target", "playwright-traces", info.getTestMethod().orElseThrow().getName() + ".zip")));
        context.close();
    }

    private Locator id(String testId) {
        return page.getByTestId(testId);
    }

    private void execute(String jobId) {
        id("execute-" + jobId).click();
        assertThat(id("screen-data")).isVisible();
        assertThat(id("selected-job")).containsText(jobId);
    }

    private void generateAndValidate(String scenario, int count) {
        id("scenario").selectOption(scenario);
        id("count").fill(String.valueOf(count));
        id("btn-generate").click();
        assertThat(id("run-state")).hasText("DATA_GENERATED");
        id("btn-validate").click();
    }

    private void confirmAndSubmit() {
        assertThat(id("btn-submit")).isDisabled();
        id("confirm-submit").check();
        assertThat(id("btn-submit")).isEnabled();
        id("btn-submit").click();
        assertThat(id("screen-monitor")).isVisible();
    }

    // -------------------------------------------------------------------- tests

    @Test
    void catalogueShowsEveryConfiguredJob() {
        assertThat(id("env-badge")).containsText("env: local");
        Locator row = id("job-row-customer-import");
        assertThat(row).containsText("Customer Import (XML)");
        assertThat(row).containsText("batch_execution_status");
        assertThat(row).containsText("inbound/customer");
        assertThat(id("job-row-payment-import")).containsText("JSON");
        assertThat(id("job-status-payment-import")).hasText("VALID");
    }

    @Test
    void viewConfigurationAndTemplate() {
        id("view-config-customer-import").click();
        assertThat(id("config-body")).containsText("job_id: customer-import");
        assertThat(id("config-body")).containsText("count_reconciliation");
        id("view-template-payment-import").click();
        assertThat(id("config-title")).containsText("template.json.ftl");
        assertThat(id("config-body")).containsText("\"paymentRef\": ${r.payment_ref}");
    }

    @Test
    void customerPositiveFlowEndToEnd() {
        execute("customer-import");
        id("btn-fetch-eligible").click();
        assertThat(id("eligible-summary")).hasText("9 eligible of 9 returned");
        assertThat(id("eligible-row-C000001")).containsText("A***@example.com"); // email masked in the grid

        generateAndValidate("positive", 3);
        assertThat(id("run-state")).hasText("VALIDATED");
        assertThat(id("validation-ok")).isVisible();
        assertThat(id("record-count")).hasText("3");
        assertThat(id("preview-file")).containsText("<Email>A***@example.com</Email>");
        assertThat(id("preview-file")).not().containsText("Aarav.Sharma@example.com");

        confirmAndSubmit();
        assertThat(id("m-result")).containsText("PASS");
        assertThat(id("chain-terminal")).hasText("COMPLETED");
        assertThat(id("m-upload")).containsText("verified");
        assertThat(id("m-inserted")).hasText("2");
        assertThat(id("m-updated")).hasText("1");
        assertThat(id("m-rejected")).hasText("0");
        assertThat(id("m-exec")).containsText("EX-");

        id("tab-validation").click();
        assertThat(id("v-result-badge")).hasText("PASS");
        assertThat(id("assert-A1")).containsText("PASS");
        assertThat(id("assert-C-C000002")).containsText("update: mapped fields match");
        assertThat(id("assert-C10")).containsText("PASS");
        assertThat(id("expectations-table")).containsText("C000003");

        Page report = page.waitForPopup(() -> id("v-report-link").click());
        assertThat(report.getByTestId("report-result")).hasText("PASS");
        report.close();
    }

    @Test
    void tickingEligibleRowsSetsBusinessIdentifiers() {
        execute("customer-import");
        id("btn-fetch-eligible").click();
        id("eligible-check-C000005").check();
        id("eligible-check-C000008").check();
        assertThat(id("keys")).hasValue("C000005, C000008");
        assertThat(id("count")).hasValue("2");
        id("btn-generate").click();
        assertThat(id("record-count")).hasText("2");
        assertThat(id("preview-table")).containsText("C000005");
        assertThat(id("preview-table")).containsText("C000008");
        assertThat(id("preview-table")).not().containsText("C000001");
    }

    @Test
    void invalidFileIsBlockedAndCannotBeSubmitted() {
        execute("customer-import");
        generateAndValidate("invalid_type", 1);
        assertThat(id("run-state")).hasText("VALIDATION_FAILED");
        assertThat(id("validation-errors")).containsText("CreditLimit");
        assertThat(id("confirm-submit")).isDisabled();
        assertThat(id("btn-submit")).isDisabled();
        assertThat(id("submit-hint")).containsText("Negative scenario: PASS");
    }

    @Test
    void submitStaysDisabledUntilValidated() {
        execute("payment-import");
        id("scenario").selectOption("positive");
        id("count").fill("1");
        id("btn-generate").click();
        assertThat(id("run-state")).hasText("DATA_GENERATED");
        assertThat(id("confirm-submit")).isDisabled();
        assertThat(id("btn-submit")).isDisabled();
        assertThat(id("submit-hint")).containsText("Validate before submitting");
    }

    @Test
    void paymentPartialRejectionThroughUi() {
        execute("payment-import");
        generateAndValidate("partial_rejection", 2);
        assertThat(id("run-state")).hasText("VALIDATED");
        confirmAndSubmit();
        assertThat(id("m-result")).containsText("PASS");
        assertThat(id("m-rejected")).hasText("1");
        assertThat(id("m-status")).containsText("COMPLETED_WITH_REJECTS");
        id("tab-validation").click();
        assertThat(id("assertions-table")).containsText("INVALID_CURRENCY");
    }

    @Test
    void downloadGeneratedFile() throws Exception {
        execute("payment-import");
        id("count").fill("2");
        id("btn-generate").click();
        assertThat(id("run-state")).hasText("DATA_GENERATED");
        Download d = page.waitForDownload(() -> id("btn-download").click());
        Assertions.assertTrue(d.suggestedFilename().startsWith("PAYIMP_"), d.suggestedFilename());
        String body = Files.readString(d.path());
        Assertions.assertTrue(body.contains("\"paymentRef\""), body);
        Assertions.assertTrue(body.contains("123456789"), "the downloaded file is the real (unmasked) test file");
    }

    @Test
    void nonOverridableFieldShowsAnError() {
        execute("customer-import");
        id("overrides").fill("all.date_of_birth=2000-01-01");
        id("btn-generate").click();
        assertThat(id("toast")).containsText("not overridable");
        assertThat(id("run-panel")).isHidden();
    }

    @Test
    void reportHistoryCanBeFiltered() {
        execute("customer-import");
        generateAndValidate("invalid_email", 1);
        assertThat(id("submit-hint")).containsText("Negative scenario: PASS");
        id("tab-reports").click();
        id("f-job").selectOption("customer-import");
        id("f-result").selectOption("PASS");
        id("f-scenario").fill("invalid_email");
        id("btn-filter").click();
        assertThat(id("reports-table").locator("tbody tr").first()).containsText("invalid_email");
        id("f-result").selectOption("FAIL");
        id("btn-filter").click();
        assertThat(id("reports-table").locator("tbody tr")).hasCount(0);
    }
}
