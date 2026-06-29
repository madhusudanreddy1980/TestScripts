# Python + Playwright Test Automation Framework

A unified automation framework covering **GUI, API, DB, cross-integration,
smoke, regression, and end-to-end** testing — with self-healing locators,
auto-correct, auto-assertion, auto data generation, a Page Object Model, a
Jira/ADO-connected prompt layer, switchable reporting, and per-environment
(dev/qa/uat) CI.

## Capabilities

| Feature | Where | What it does |
|---|---|---|
| **GUI testing** | `pages/`, `framework/core/base_page.py` | Page Object Model on Playwright |
| **API testing** | `framework/api/api_client.py` | REST client w/ retry + JSON-schema contract checks |
| **DB testing** | `framework/db/db_client.py` | SQLAlchemy client, query-level assertions |
| **Cross-integration** | `tests/integration/` | Verifies consistency across API ⇄ DB |
| **Smoke / Regression / E2E** | `tests/`, pytest markers | Suite selection via `-m smoke` / `regression` / `e2e` |
| **Auto-correct** | `framework/core/auto_correct.py` | Retries transient action failures w/ backoff |
| **Auto-healing** | `framework/core/self_healing.py` | Falls back through candidate selectors |
| **Auto-assertion** | `framework/core/auto_assert.py` | Soft asserts + smart waiting |
| **Auto data generators** | `framework/data/data_generator.py` | Faker-based realistic, seeded data |
| **Prompt layer** | `framework/integrations/prompt_layer.py` | Turns Jira/ADO test cases into steps/stubs |
| **Switchable reports** | `framework/reporting/report_manager.py` | `allure` / `html` / `both` on the fly |
| **Env switching** | `config/` | `dev` / `qa` / `uat` via `TEST_ENV` |

## Quick start

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python -m playwright install chromium

cp .env.example .env          # then fill in credentials

# Offline tests (no browser/DB/network needed) — verify the install:
pytest tests/regression/test_framework_core.py tests/regression/test_prompt_layer.py -v
```

## Running tests

```bash
# Pick environment + report model on the fly:
TEST_ENV=qa  REPORT_TYPE=allure pytest -m smoke
TEST_ENV=uat REPORT_TYPE=html   pytest -m regression
TEST_ENV=dev REPORT_TYPE=both   pytest -m "e2e or integration"

# By layer:
pytest -m api          # API only
pytest -m db           # DB only
pytest -m gui          # browser only
pytest -n auto         # parallel (pytest-xdist)
```

`--reruns` (in `pytest.ini`) gives whole-test auto-correct; `auto_correct()`
adds action-level retries inside the framework.

## Reports

`REPORT_TYPE` selects the report without changing the command:

```bash
REPORT_TYPE=allure pytest        # raw results -> allure-results/, built on finish
REPORT_TYPE=html   pytest        # self-contained reports/html/report.html
REPORT_TYPE=both   pytest        # both

allure serve reports/allure-results   # interactive Allure (needs allure CLI)
```

Failure screenshots and the self-healing audit trail are auto-attached.

## Prompt layer (Jira / ADO)

```python
from framework.integrations.prompt_layer import prompt_layer

structured = prompt_layer.from_jira("QA-123")   # or .from_ado(45678)
print(prompt_layer.scaffold_test(structured))    # runnable pytest stub
```

With `ANTHROPIC_API_KEY` set it uses Claude to structure steps; otherwise it
falls back to an offline heuristic parser so it always works.

## Configuration

- `config/<env>.yaml` — per-environment URLs, DB host/name, integration keys.
- Secrets (DB creds, tokens, API keys) come from environment variables / `.env`.
- `TEST_ENV`, `REPORT_TYPE`, `BROWSER`, `HEADLESS` are all runtime switches.

## CI

`.github/workflows/ci.yml` runs the offline smoke/core tests across
`dev`/`qa`/`uat` on every push, and a full marker-driven regression suite on
manual dispatch with a selectable environment and report model.
