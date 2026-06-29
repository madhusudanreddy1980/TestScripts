"""Page Object Model base class.

Every concrete page object inherits from ``BasePage`` and declares its
elements as ``{logical_name: [candidate_selectors...]}``. All interactions
flow through helpers here so they automatically gain:

  * self-healing locator resolution (HealingLocator)
  * auto-correct retry/backoff on transient failures (auto_correct)

Concrete pages therefore stay declarative and free of waits/retries.
"""

from __future__ import annotations

from playwright.sync_api import Locator, Page

from framework.core.auto_correct import auto_correct
from framework.core.self_healing import HealingLocator
from framework.utils.logger import get_logger

log = get_logger("base_page")


class BasePage:
    # Subclasses override: {"login_btn": ["#login", "button[name=login]", "text=Log in"]}
    locators: dict[str, list[str]] = {}

    def __init__(self, page: Page) -> None:
        self.page = page

    # ── element resolution ───────────────────────────────────────
    def element(self, name: str) -> Locator:
        """Resolve a named element via the self-healing locator."""
        if name not in self.locators:
            raise KeyError(f"'{name}' is not declared in {type(self).__name__}.locators")
        return HealingLocator(self.page, name, self.locators[name]).resolve()

    # ── navigation ───────────────────────────────────────────────
    def open(self, path: str = "/") -> None:
        log.info("Navigating to %s", path)
        auto_correct(self.page.goto, path, wait_until="domcontentloaded")

    # ── actions (each wrapped in auto-correct) ───────────────────
    def click(self, name: str) -> None:
        log.info("Click '%s'", name)
        auto_correct(lambda: self.element(name).click())

    def fill(self, name: str, value: str) -> None:
        log.info("Fill '%s'", name)
        auto_correct(lambda: self.element(name).fill(value))

    def text_of(self, name: str) -> str:
        return auto_correct(lambda: self.element(name).inner_text())

    def is_visible(self, name: str) -> bool:
        try:
            return self.element(name).is_visible()
        except Exception:  # noqa: BLE001
            return False

    def title(self) -> str:
        return self.page.title()
