"""Switchable reporting — choose the report model on the fly.

``REPORT_TYPE`` (env var, default "allure") selects which report is built
*after* a run, without changing pytest invocation:

    allure  -> generate static Allure report from reports/allure-results
    html    -> pytest-html single-file report
    both    -> generate both

pytest always emits allure-results (see pytest.ini --alluredir); the HTML
report is requested dynamically by ``pytest_addopts`` in conftest. This lets
CI flip report style per stage/environment with one variable.
"""

from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

from config.settings import settings
from framework.utils.logger import get_logger

log = get_logger("report")

REPORTS_DIR = Path("reports")
ALLURE_RESULTS = REPORTS_DIR / "allure-results"
ALLURE_REPORT = REPORTS_DIR / "allure-report"
HTML_REPORT = REPORTS_DIR / "html" / "report.html"


def generate() -> None:
    """Build the configured report(s). Safe to call after the run."""
    report_type = settings.report_type
    log.info("Generating report(s) for REPORT_TYPE=%s", report_type)

    if report_type in {"allure", "both"}:
        _generate_allure()
    if report_type in {"html", "both"}:
        # pytest-html writes the file in its own sessionfinish hook; we just
        # note where it lands (it may not exist yet at this point in the run).
        log.info("HTML report will be written to %s", HTML_REPORT)


def _generate_allure() -> None:
    if not ALLURE_RESULTS.exists():
        log.warning("No allure results at %s; nothing to generate", ALLURE_RESULTS)
        return
    if shutil.which("allure") is None:
        log.warning(
            "Allure CLI not on PATH. Raw results are in %s — run "
            "`allure serve %s` locally, or install allure in CI.",
            ALLURE_RESULTS,
            ALLURE_RESULTS,
        )
        return
    subprocess.run(
        ["allure", "generate", str(ALLURE_RESULTS), "-o", str(ALLURE_REPORT), "--clean"],
        check=True,
    )
    log.info("Allure report generated at %s", ALLURE_REPORT)


if __name__ == "__main__":
    generate()
