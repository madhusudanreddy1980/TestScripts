"""Global pytest fixtures and hooks.

Wires the framework together:
  * env-aware settings, browser pages (POM), API & DB clients, data gen,
    soft-assert — all as fixtures.
  * switchable reporting: injects --html opts when REPORT_TYPE wants HTML,
    and builds the chosen report after the session.
  * auto screenshot + Allure attachment on UI test failure.
  * resets the self-healing audit log per test and attaches a heal summary.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from config.settings import settings
from framework.api.api_client import ApiClient
from framework.core.auto_assert import SoftAssert
from framework.core.driver_factory import browser_context
from framework.core.self_healing import heal_summary, reset_heal_log
from framework.data.data_generator import DataGenerator
from framework.db.db_client import DbClient
from framework.reporting import report_manager
from framework.utils.logger import get_logger

log = get_logger("conftest")

SCREENSHOT_DIR = Path("reports/screenshots")


# ── switchable report model on the fly ───────────────────────────
def pytest_configure(config):
    log.info(
        "Test session | env=%s | browser=%s | report=%s",
        settings.env, settings.browser, settings.report_type,
    )
    SCREENSHOT_DIR.mkdir(parents=True, exist_ok=True)

    # When HTML output is requested, drive the pytest-html plugin directly by
    # setting its options — reliable regardless of CLI arg ordering.
    if settings.report_type in {"html", "both"} and config.pluginmanager.hasplugin("html"):
        report_manager.HTML_REPORT.parent.mkdir(parents=True, exist_ok=True)
        config.option.htmlpath = str(report_manager.HTML_REPORT)
        config.option.self_contained_html = True


def pytest_sessionfinish(session, exitstatus):
    # Build whatever report the environment asked for.
    try:
        report_manager.generate()
    except Exception as exc:  # noqa: BLE001
        log.warning("Report generation skipped: %s", exc)


# ── core fixtures ─────────────────────────────────────────────────
@pytest.fixture
def page():
    """A managed Playwright Page for GUI/E2E tests (auto-healing ready)."""
    reset_heal_log()
    with browser_context() as context:
        pg = context.new_page()
        yield pg


@pytest.fixture
def api() -> ApiClient:
    return ApiClient()


@pytest.fixture(scope="session")
def db() -> DbClient:
    client = DbClient()
    yield client
    client.dispose()


@pytest.fixture
def data() -> DataGenerator:
    return DataGenerator()


@pytest.fixture
def soft() -> SoftAssert:
    sa = SoftAssert()
    yield sa
    sa.assert_all()  # raise accumulated failures at teardown


# ── failure capture + heal summary attachment ────────────────────
@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    report = outcome.get_result()
    if report.when != "call":
        return

    # Attach self-healing audit trail to every UI test.
    heals = heal_summary()
    if heals:
        _attach_text("self-heal-summary", json.dumps(heals, indent=2))

    # Screenshot on failure if a page fixture is in play.
    if report.failed and "page" in item.fixturenames:
        pg = item.funcargs.get("page")
        if pg is not None:
            shot = SCREENSHOT_DIR / f"{item.name}.png"
            try:
                pg.screenshot(path=str(shot), full_page=True)
                _attach_png("failure-screenshot", shot)
                log.info("Saved failure screenshot: %s", shot)
            except Exception as exc:  # noqa: BLE001
                log.warning("Could not capture screenshot: %s", exc)


def _attach_text(name: str, body: str) -> None:
    try:
        import allure

        allure.attach(body, name=name, attachment_type=allure.attachment_type.JSON)
    except Exception:  # noqa: BLE001
        pass


def _attach_png(name: str, path: Path) -> None:
    try:
        import allure

        allure.attach.file(
            str(path), name=name, attachment_type=allure.attachment_type.PNG
        )
    except Exception:  # noqa: BLE001
        pass
