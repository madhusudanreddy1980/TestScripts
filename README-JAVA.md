# Batch Job Automation Test Framework — Java + Playwright

Metadata-driven testing of file-driven batch jobs on GCP, AWS, Azure or local storage.
For each run it:

1. picks eligible records from the database (read-only),
2. builds an XML or JSON file from the job's template,
3. validates it against XSD / JSON Schema plus business rules,
4. delivers it to the inbound location — only after explicit confirmation,
5. follows the batch through its status table,
6. reconciles the database against expectations frozen *before* submission.

Every assertion carries its SQL evidence. Every run writes a JSON and an HTML report.

A web **tester UI** covers the four screens:
- Job catalogue
- Test data generation
- Execution monitor
- Database validation

**Playwright for Java** drives that UI end to end.

| Layer | Tools |
|---|---|
| Language / build | Java 21, Maven |
| Configuration | YAML via Jackson (strict: unknown keys rejected) |
| Templates | FreeMarker (XML auto-escaping; JSON values pre-encoded by Jackson) |
| Validation | JDK XSD validator (hardened), networknt JSON Schema 2020-12 |
| Expressions in YAML | Apache Commons JEXL, sandboxed (no method calls, loops, object creation) |
| Database | JDBC, read-only connections (SQLite for local; PostgreSQL driver included) |
| Cloud | GCS, S3, Azure Blob SDKs (optional `-Pcloud` profile) |
| UI | Javalin REST API + dependency-free HTML/JS |
| Tests | JUnit 5, AssertJ, Playwright for Java (Chromium, traces per test) |
| CLI | picocli, fat jar via maven-shade |

## Quick start

```bash
# 1. Build and run every test (unit, integration, end-to-end, Playwright UI)
mvn verify
#    Playwright downloads Chromium on first run. On a fresh Linux box install its OS libraries once:
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install --with-deps chromium" -Dexec.classpathScope=test

# Skip the browser tests:          mvn verify -DexcludedGroups=ui
# Only the browser tests:          mvn test -Dgroups=ui

# 2. Open the tester UI with a seeded demo database and the simulated batch application
java -jar target/batchauto.jar ui --simulator          # → http://127.0.0.1:7070/

# 3. Command line
java -jar target/batchauto.jar demo                    # 9 scenarios end to end (last one injects a defect → FAIL)
java -jar target/batchauto.jar catalogue
java -jar target/batchauto.jar eligible --job customer-import
java -jar target/batchauto.jar generate --job payment-import --count 2
java -jar target/batchauto.jar run --job customer-import --count 3 --submit --yes
java -jar target/batchauto.jar run --job customer-import --scenario partial_rejection --count 3 --submit --yes
java -jar target/batchauto.jar reports --result FAIL
```

Submission always needs explicit confirmation:
- **UI:** the confirmation checkbox.
- **CLI:** `--submit --yes`.
- **Code:** `submit(true)`.

It is refused in any environment with `is_production: true` or `allow_submission: false`. A run ID can be submitted only once.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Passed (or a negative test was correctly blocked) |
| 1 | Test failure — the application did something other than expected |
| 2 | Configuration error, or submission blocked |
| 3 | Infrastructure error (storage, database) — not an application defect |
| 4 | Batch timed out, or no execution appeared for the file |
| 5 | Not enough eligible test data |

## Layout

```
pom.xml
config/                 environment.yaml · cloud-profiles.yaml · database-profiles.yaml
jobs/<job-id>/          job.yaml · template.(xml|json).ftl · schema.(xsd|json) · eligible-data.sql · validation-rules.yaml
src/main/java/com/batchauto/
  config/               typed, validated configuration (records)
  catalogue/            job discovery + per-job configuration status
  db/                   read-only JDBC, SELECT-only guard, named parameters, query timeout
  data/                 eligibility, selection, mapping, generators, overrides, scenarios, record rules
  template/             FreeMarker → XML (XSD) / JSON (JSON Schema)
  storage/              InboundStorageAdapter, local adapter, registry
  monitor/              status-table polling: COMPLETED / FAILED / TIMED_OUT / NOT_FOUND / AMBIGUOUS
  reconcile/            expectations + reconciliation (A–F) with SQL evidence
  runner/               TestRun state machine, submission ledger (idempotency)
  report/               report.json, report.html, filterable index
  security/             masking, secret redaction
  ui/                   Javalin API for the tester UI
  simulator/            stand-in batch application + seed data (independent of the framework)
  cli/                  picocli entry point
src/main/resources/ui/  index.html · app.js · app.css
src/cloud/java/         GcsAdapter · S3Adapter · AzureBlobAdapter (built with -Pcloud)
src/test/java/          unit · integration · e2e · ui (Playwright)
```

## Onboarding a new batch job (no Java changes)

1. Copy `jobs/customer-import/` to `jobs/<new-job-id>/`.
2. Edit **`job.yaml`**:
   - `job_id` and the logical `database`
   - `file`: format, template, schema, naming
   - `fields`: source column / generator / default, type, sensitive, overridable
   - `eligibility`: SQL file, key, params, optional JEXL re-check assertions
   - `inbound.prefix`
   - `monitoring`: status table, columns, status values
   - `target`: table, key, insert/update/upsert, duplicate policy, lineage and audit columns, reject table
   - `scenarios`
3. Write the **template**:
   - **XML:** FreeMarker in XML output format, so values are escaped automatically. Test optional values with `<#if r.x??>`.
   - **JSON:** every `${r.x}` is already a JSON literal.
4. Add the **schema**: XSD or JSON Schema 2020-12.
5. Write **`eligible-data.sql`**: a single SELECT with `:named` parameters. The framework adds paging.
6. Write **`validation-rules.yaml`**:
   - `pre_submit_rules`: blocking checks.
   - `expected_rejections`: JEXL condition plus reason code.
   - `target_mapping`: expected target column values (`field`, `format`, `value`, `cases`, `transform`).
   - `count_reconciliation`: job-specific equations.
7. Check the job in the UI catalogue or with `catalogue`: it shows `VALID`, or the exact reason it isn't.

Expressions use JEXL syntax, for example:
- `credit_limit >= 100000`
- `email != null`
- `!(currency =~ ['INR', 'USD'])`
- `records_read == records_processed + records_rejected`

Scenarios are data, not code. The available settings are:
- `set`, `drop`, `records`
- `duplicate`, `empty`, `malformed`
- `synthetic`, `force_submit_invalid`
- `expect`: one of `processed`, `partial_rejection`, `blocked`, `batch_failed`

## Acceptance criteria → tests

| AC | Test |
|---|---|
| AC01 template/schema/metadata load | `ConfigAndGenerationTest.ac01_*`, UI `catalogueShowsEveryConfiguredJob`, `viewConfigurationAndTemplate` |
| AC02 only eligible records | `ac02_*` — SQL plus independent JEXL re-check, shortage reported, ineligible keys refused |
| AC03 valid file from DB values + overrides | `ac03_*` — XML, exact JSON decimals, escaping, override rules, determinism; UI `tickingEligibleRowsSetsBusinessIdentifiers` |
| AC04 invalid files blocked | `ac04_*` (9 variants), UI `invalidFileIsBlockedAndCannotBeSubmitted`, `submitStaysDisabledUntilValidated` |
| AC05 provider by configuration | `ac05_*` |
| AC06 upload verification | `ac06_*` + every e2e run (SHA-256 re-check, create-only writes) |
| AC07 correct execution, terminal wait | `BatchMonitorTest`, e2e positives, UI flows |
| AC08 timeout / failed / missing distinct | `timeoutIsDistinct`, `missingStatusIsDistinct`, `processingCrash…`, `BatchMonitorTest` |
| AC09 inserts, updates, transforms, counts | e2e positive/partial/re-run; **defect injection** (wrong segment, silently skipped row, missing upper-casing) |
| AC10 traceability + report | `ac10_reportTraceabilityAndMasking`, UI report link |
| AC11 onboarding by config | `ac11_newJobOnboardedByConfigurationOnly` |
| AC12 local end-to-end | e2e suite, Playwright suite, `batchauto demo` |

## Status and known limits

- **Not yet compiled or run.** The build environment this was written in had no access to Maven
  Central. Run `mvn verify` first. Expect, at most, small compile fixes on library-API details.
- **Behaviour is ported from a working version.** The logic, scenarios and seed data match a Python version
  that passed 88 tests and a 9-scenario demo, including the defect-injection cases.
- **Cloud adapters** (`-Pcloud`) use create-only writes (GCS `doesNotExist`, S3 `If-None-Match`,
  Azure `If-None-Match`) but have not been run against real accounts.
- **Paging SQL** is `LIMIT/OFFSET` (SQLite, PostgreSQL, MySQL). Oracle and SQL Server need a dialect
  branch in `TestDataFactory.fetchEligible`.
- **Unrelated-row guard** hashes the target table outside the test keys. Turn it off on shared, busy
  databases (`guard_unrelated_rows: false`).
- **UI access:** it binds to `127.0.0.1` by default and has no login of its own. Put it behind your SSO
  proxy before sharing.

## Troubleshooting

| Symptom | Cause |
|---|---|
| `INVALID: …` in catalogue | YAML typo or missing file — the message names the key |
| exit 5 `needed N eligible records` | Not enough rows meet the eligibility SQL |
| exit 4 `NOT_FOUND` | Batch never registered the file — check the inbound prefix and pickup trigger |
| exit 4 `TIMED_OUT` | Execution stuck in a running status; raise `timeout_seconds` or investigate |
| `UNSAFE_SQL` | A configured query is not a single SELECT/WITH |
| `the s3 adapter is not in this build` | Rebuild with `mvn -Pcloud package` |
| Playwright: browser missing / libraries | Run the `install --with-deps chromium` command above |
| A UI test failed | Open `target/playwright-traces/<test>.zip` at trace.playwright.dev |
