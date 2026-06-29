"""Self-healing locator resolution for Playwright.

A ``HealingLocator`` is given an *ordered* list of candidate selectors for a
single logical element. When the primary selector fails to resolve (the
element is missing, hidden, or detached), it transparently falls back to the
next candidate and logs the heal so flaky UIs don't fail the whole test.

This gives the framework's "auto-healing" property: locators keep working
across small DOM/markup changes as long as one alternative still matches.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from playwright.sync_api import Locator, Page, TimeoutError as PWTimeoutError

from framework.utils.logger import get_logger

log = get_logger("self_healing")


@dataclass
class HealEvent:
    """Record of a single heal — surfaced in reports for visibility."""

    logical_name: str
    failed_selector: str
    healed_selector: str


# Module-level audit trail; the reporting layer can attach this run summary.
HEAL_LOG: list[HealEvent] = field(default_factory=list)  # type: ignore[assignment]
HEAL_LOG = []


class HealingLocator:
    """Resolves a logical element from a prioritised list of candidates."""

    def __init__(
        self,
        page: Page,
        logical_name: str,
        candidates: list[str],
        probe_timeout_ms: int = 2000,
    ) -> None:
        if not candidates:
            raise ValueError(f"'{logical_name}' needs at least one candidate selector")
        self.page = page
        self.logical_name = logical_name
        self.candidates = candidates
        self.probe_timeout_ms = probe_timeout_ms

    def resolve(self) -> Locator:
        """Return the first candidate Locator that is actually present.

        The first candidate is the canonical one. Any later candidate that
        wins is recorded as a heal event.
        """
        primary = self.candidates[0]
        for index, selector in enumerate(self.candidates):
            locator = self.page.locator(selector).first
            try:
                locator.wait_for(state="attached", timeout=self.probe_timeout_ms)
            except PWTimeoutError:
                continue

            if index > 0:
                event = HealEvent(self.logical_name, primary, selector)
                HEAL_LOG.append(event)
                log.warning(
                    "[yellow]Self-heal[/]: '%s' primary '%s' failed -> using '%s'",
                    self.logical_name,
                    primary,
                    selector,
                )
            return locator

        raise PWTimeoutError(
            f"Self-healing exhausted for '{self.logical_name}'. "
            f"None of {self.candidates} resolved."
        )


def reset_heal_log() -> None:
    """Clear the audit trail (call between test runs)."""
    HEAL_LOG.clear()


def heal_summary() -> list[dict[str, str]]:
    """Serialisable summary of heals for attaching to a report."""
    return [
        {
            "element": e.logical_name,
            "failed_selector": e.failed_selector,
            "healed_selector": e.healed_selector,
        }
        for e in HEAL_LOG
    ]
