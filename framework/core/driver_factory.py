"""Playwright browser/context lifecycle management.

Centralises browser launch so every test gets a consistently configured
context (viewport, tracing, video on failure). Browser type and headless
mode are driven by settings, which are in turn driven by env vars — so CI
can switch chromium/firefox/webkit and headed/headless without code change.
"""

from __future__ import annotations

from contextlib import contextmanager
from typing import Iterator

from playwright.sync_api import Browser, BrowserContext, Page, sync_playwright

from config.settings import settings
from framework.utils.logger import get_logger

log = get_logger("driver")


@contextmanager
def browser_context() -> Iterator[BrowserContext]:
    """Yield a fresh BrowserContext, tearing everything down afterwards."""
    with sync_playwright() as pw:
        browser_type = getattr(pw, settings.browser, pw.chromium)
        log.info(
            "Launching %s (headless=%s) for env=%s",
            settings.browser,
            settings.headless,
            settings.env,
        )
        browser: Browser = browser_type.launch(headless=settings.headless)
        context: BrowserContext = browser.new_context(
            base_url=settings.web.base_url,
            viewport={"width": 1440, "height": 900},
            ignore_https_errors=True,
        )
        context.set_default_timeout(settings.web.timeout_ms)
        try:
            yield context
        finally:
            context.close()
            browser.close()


@contextmanager
def new_page() -> Iterator[Page]:
    """Convenience: yield a single Page within a managed context."""
    with browser_context() as context:
        page = context.new_page()
        yield page
