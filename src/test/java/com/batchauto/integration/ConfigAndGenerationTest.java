package com.batchauto.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.batchauto.Framework;
import com.batchauto.config.CloudProfile;
import com.batchauto.config.JobLoader.LoadedJob;
import com.batchauto.data.EligibleRow;
import com.batchauto.errors.BatchAutoException.ConfigException;
import com.batchauto.errors.BatchAutoException.DataShortageException;
import com.batchauto.errors.BatchAutoException.StorageException;
import com.batchauto.errors.BatchAutoException.SubmissionBlockedException;
import com.batchauto.runner.RunState;
import com.batchauto.runner.TestRun;
import com.batchauto.storage.LocalFilesystemAdapter;
import com.batchauto.storage.StorageRegistry;
import com.batchauto.storage.UploadResult;
import com.batchauto.support.TestWorkspace;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.batchauto.config.Yaml;

/** AC01–AC06 and AC11 against the real configuration, SQLite and the local adapter. */
class ConfigAndGenerationTest {

    @TempDir Path tmp;
    TestWorkspace ws;

    @BeforeEach
    void setUp() {
        ws = new TestWorkspace(tmp);
    }

    TestRun run(String job, String scenario, Integer count) {
        return new TestRun(ws.fw, job, TestRun.Options.defaults().scenario(scenario).count(count));
    }

    // ---------------------------------------------------------------- AC01
    @ParameterizedTest
    @CsvSource({"customer-import,xml,template.xml.ftl,schema.xsd", "payment-import,json,template.json.ftl,schema.json"})
    void ac01_selectingJobLoadsTemplateSchemaAndMetadata(String jobId, String fmt, String template, String schema) {
        LoadedJob lj = ws.fw.catalogue().get(jobId);
        assertThat(lj.job().file().format()).isEqualTo(fmt);
        assertThat(lj.job().file().template()).isEqualTo(template);
        assertThat(lj.job().file().schemaFile()).isEqualTo(schema);
        assertThat(lj.dir().resolve(template)).isRegularFile();
        assertThat(lj.rules().targetMapping()).isNotEmpty();
        assertThat(lj.rules().countReconciliation()).isNotEmpty();
        Map<String, Object> row = ws.fw.catalogueRows().stream().filter(r -> jobId.equals(r.get("job_id"))).findFirst().orElseThrow();
        assertThat(row).containsEntry("configuration_status", "VALID").containsEntry("batch_status_table", "batch_execution_status");
    }

    @Test
    void ac01_brokenJobIsListedInvalidNotFatal(@TempDir Path wsDir) throws Exception {
        TestWorkspace.copyWorkspace(wsDir);
        Path bad = Files.createDirectories(wsDir.resolve("jobs/broken-job"));
        Files.writeString(bad.resolve("job.yaml"), "job_id: broken-job\nname: x\nunknown_key: 1\n");
        Files.writeString(bad.resolve("validation-rules.yaml"), "target_mapping: {}\n");
        Framework fw = new Framework(wsDir, "local", tmp);
        Map<String, String> status = new java.util.HashMap<>();
        fw.catalogueRows().forEach(r -> status.put((String) r.get("job_id"), (String) r.get("configuration_status")));
        assertThat(status.get("broken-job")).startsWith("INVALID");
        assertThat(status.get("customer-import")).isEqualTo("VALID");
        assertThatThrownBy(() -> fw.catalogue().get("broken-job")).isInstanceOf(ConfigException.class);
    }

    // ---------------------------------------------------------------- AC02
    @Test
    void ac02_eligibleDataOnlyContainsEligibleRecords() {
        List<EligibleRow> rows = new TestRun(ws.fw, "customer-import").fetchEligible();
        assertThat(rows).extracting(EligibleRow::key).containsExactly(
                "C000001", "C000002", "C000003", "C000005", "C000006", "C000008", "C000009", "C000011", "C000012");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.eligible()).isTrue();
            assertThat(r.row()).containsEntry("status", "ACTIVE");
            assertThat(r.row().get("email")).isNotNull();
        });
    }

    @Test
    void ac02_paymentEligibilityExcludesPostedExportedAndZero() {
        List<Object> keys = new TestRun(ws.fw, "payment-import").fetchEligible().stream().map(EligibleRow::key).toList();
        assertThat(keys).doesNotContain("PAY00000004", "PAY00000006", "PAY00000009").hasSize(7);
    }

    @Test
    void ac02_shortageIsReportedNotFabricated() {
        TestRun r = run("payment-import", "positive", 50);
        assertThatThrownBy(r::generate).isInstanceOf(DataShortageException.class).hasMessageContaining("needed 50 eligible records, found 7");
    }

    @Test
    void ac02_requestedIneligibleKeyIsRefused() {
        TestRun r = new TestRun(ws.fw, "customer-import", TestRun.Options.defaults().keys(List.of("C000001", "C000004")));
        assertThatThrownBy(r::generate).isInstanceOf(DataShortageException.class).hasMessageContaining("C000004");
    }

    // ---------------------------------------------------------------- AC03
    @Test
    void ac03_generatesValidXmlWithDbValuesAndOverrides() {
        TestRun r = new TestRun(ws.fw, "customer-import", TestRun.Options.defaults().count(3)
                .overrides(Map.of("1", Map.of("first_name", "Override"))));
        r.generate();
        assertThat(r.validate()).as(String.valueOf(r.allErrors())).isTrue();
        String text = new String(r.content(), StandardCharsets.UTF_8);
        assertThat(text).contains("customerId=\"C000001\"", "<Email>Aarav.Sharma@example.com</Email>", "<FirstName>Override</FirstName>");
        assertThat(r.validation().recordCount()).isEqualTo(3);
        assertThat(r.records()).extracting(g -> g.sourceKey()).containsExactly("C000001", "C000002", "C000003");
    }

    @Test
    void ac03_generatesValidJsonWithExactDecimals() {
        TestRun r = run("payment-import", "positive", 3);
        r.generate();
        assertThat(r.validate()).as(String.valueOf(r.allErrors())).isTrue();
        assertThat(new String(r.content(), StandardCharsets.UTF_8)).contains("\"amount\": 99000.1").contains("\"narrative\": null");
    }

    @Test
    void ac03_xmlEscaping() {
        TestRun r = run("customer-import", "special_characters", 1);
        r.generate();
        assertThat(r.validate()).isTrue();
        assertThat(new String(r.content(), StandardCharsets.UTF_8)).contains("O&apos;Brien &amp; Sons &lt;Ltd&gt;");
    }

    @Test
    void ac03_nonOverridableFieldIsRefused() {
        TestRun r = new TestRun(ws.fw, "customer-import", TestRun.Options.defaults().count(1)
                .overrides(Map.of("all", Map.of("date_of_birth", "2000-01-01"))));
        assertThatThrownBy(r::generate).isInstanceOf(ConfigException.class).hasMessageContaining("not overridable");
    }

    @Test
    void ac03_generationIsDeterministic() {
        assertThat(contentFor("20261024T000000-aaaaaa")).isEqualTo(contentFor("20261024T000000-aaaaaa"));
        assertThat(contentFor("20261024T000000-aaaaaa")).isNotEqualTo(contentFor("20261024T000000-bbbbbb"));
    }

    private byte[] contentFor(String runId) {
        TestRun r = new TestRun(ws.fw, "payment-import", TestRun.Options.defaults().count(2).runId(runId));
        r.generate();
        return r.content();
    }

    // ---------------------------------------------------------------- AC04
    @ParameterizedTest
    @CsvSource({
        "customer-import,missing_mandatory,Email",
        "customer-import,invalid_type,CreditLimit",
        "customer-import,invalid_email,BR-CUST-001",
        "customer-import,empty_file,Customer",
        "customer-import,malformed_file,malformed XML",
        "payment-import,missing_mandatory,amount",
        "payment-import,invalid_type,amount",
        "payment-import,empty_file,payments",
        "payment-import,malformed_file,malformed JSON",
    })
    void ac04_invalidFilesFailValidation(String job, String scenario, String needle) {
        TestRun r = run(job, scenario, 2);
        r.generate();
        assertThat(r.validate()).isFalse();
        assertThat(r.state()).isEqualTo(RunState.VALIDATION_FAILED);
        assertThat(r.allErrors()).anyMatch(e -> e.contains(needle));
    }

    @Test
    void ac04_invalidFileCannotBeSubmitted() {
        TestRun r = run("customer-import", "invalid_type", 1);
        r.generate();
        r.validate();
        assertThatThrownBy(() -> r.submit(true)).isInstanceOf(SubmissionBlockedException.class);
    }

    @Test
    void generationNeverSubmitsWithoutConfirmation() throws Exception {
        TestRun r = run("customer-import", "positive", 1);
        r.generate();
        r.validate();
        assertThatThrownBy(() -> r.submit(false)).isInstanceOf(SubmissionBlockedException.class).hasMessageContaining("explicit confirmation");
        Path storage = Path.of(ws.fw.cloudProfile().root());
        if (Files.exists(storage)) {
            try (Stream<Path> s = Files.walk(storage)) {
                assertThat(s.filter(p -> p.toString().endsWith(".xml"))).isEmpty();
            }
        }
    }

    @Test
    void productionEnvironmentRefusesSubmission() {
        System.setProperty("BATCHAUTO_QA_DB_URL", "jdbc:sqlite:" + tmp.resolve("demo.db"));
        try {
            Framework prod = new Framework(TestWorkspace.ROOT, "prod_readonly", tmp);
            TestRun r = new TestRun(prod, "customer-import", TestRun.Options.defaults().count(1));
            r.generate();
            r.validate();
            assertThatThrownBy(() -> r.submit(true)).isInstanceOf(SubmissionBlockedException.class).hasMessageContaining("disabled");
        } finally {
            System.clearProperty("BATCHAUTO_QA_DB_URL");
        }
    }

    // ---------------------------------------------------------------- AC05
    @ParameterizedTest
    @CsvSource({
        "gcs,com.batchauto.storage.cloud.GcsAdapter",
        "s3,com.batchauto.storage.cloud.S3Adapter",
        "azure_blob,com.batchauto.storage.cloud.AzureBlobAdapter",
    })
    void ac05_providerIsSelectedByConfiguration(String provider, String cls) {
        CloudProfile p = new CloudProfile(provider, null, "b", "c", "https://a.blob.core.windows.net", null, null, null, null);
        assertThat(StorageRegistry.adapterClassName(p)).isEqualTo(cls);
    }

    @Test
    void ac05_localProfileCreatesLocalAdapter() {
        assertThat(ws.fw.storage()).isInstanceOf(LocalFilesystemAdapter.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"qa_gcp", "qa_aws", "qa_azure"})
    void ac05_switchingEnvironmentLeavesCoreLogicUnchanged(String env) {
        System.setProperty("BATCHAUTO_QA_DB_URL", "jdbc:sqlite:" + tmp.resolve("demo.db"));
        try {
            Framework fw = new Framework(TestWorkspace.ROOT, env, tmp);
            TestRun r = new TestRun(fw, "customer-import", TestRun.Options.defaults().count(2));
            r.generate();
            assertThat(r.validate()).isTrue();
            assertThat(fw.cloudProfile().provider()).isIn("gcs", "s3", "azure_blob");
        } finally {
            System.clearProperty("BATCHAUTO_QA_DB_URL");
        }
    }

    // ---------------------------------------------------------------- AC06
    @Test
    void ac06_localUploadVerificationAndNoOverwrite() throws Exception {
        Path src = Files.writeString(tmp.resolve("f.xml"), "<x/>");
        LocalFilesystemAdapter a = new LocalFilesystemAdapter(tmp.resolve("store"));
        UploadResult up = a.uploadFile(src, "inbound/customer/f.xml", Map.of("run_id", "r1"));
        assertThat(a.verifyFile("inbound/customer/f.xml", up.sha256())).isTrue();
        assertThat(a.verifyFile("inbound/customer/f.xml", "0".repeat(64))).isFalse();
        assertThat(a.getFileMetadata("inbound/customer/f.xml")).containsEntry("run_id", "r1");
        assertThatThrownBy(() -> a.uploadFile(src, "inbound/customer/f.xml", null)).isInstanceOf(StorageException.class)
                .hasMessageContaining("overwrite");
        assertThatThrownBy(() -> a.verifyFile("../../etc/passwd", null)).isInstanceOf(StorageException.class);
        assertThat(a.deleteTestFile("inbound/customer/f.xml")).isTrue();
        assertThat(a.verifyFile("inbound/customer/f.xml", null)).isFalse();
    }

    // ---------------------------------------------------------------- AC11
    @Test
    void ac11_newJobOnboardedByConfigurationOnly(@TempDir Path wsDir) throws Exception {
        TestWorkspace.copyWorkspace(wsDir);
        Path dst = wsDir.resolve("jobs/vip-customer-import");
        TestWorkspace.copyTree(wsDir.resolve("jobs/customer-import"), dst);
        ObjectNode job = (ObjectNode) Yaml.readTree(dst.resolve("job.yaml"));
        job.put("job_id", "vip-customer-import").put("name", "VIP Customer Import");
        ((ObjectNode) job.get("file")).put("naming", "VIPCUST_{run_id}.xml");
        ((com.fasterxml.jackson.databind.node.ArrayNode) job.get("eligibility").get("assertions")).add("credit_limit >= 100000");
        Files.writeString(dst.resolve("job.yaml"), Yaml.MAPPER.writeValueAsString(job));

        Framework fw = new Framework(wsDir, "local", tmp);
        TestRun r = new TestRun(fw, "vip-customer-import", TestRun.Options.defaults().count(3));
        r.generate();
        assertThat(r.validate()).isTrue();
        assertThat(r.fileName()).startsWith("VIPCUST_");
        assertThat(r.records()).extracting(g -> g.sourceKey()).containsExactly("C000002", "C000005", "C000008");
    }
}
