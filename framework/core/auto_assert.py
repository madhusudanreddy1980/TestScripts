"""Auto-assertion engine.

Goals:
  * **Soft assertions** — collect multiple failures in one test and report
    them together instead of stopping at the first.
  * **Smart waiting** — UI assertions auto-retry until a timeout, so you
    assert intent ("the banner says Welcome") without sprinkling sleeps.
  * **Type-aware equality** — light coercion so "200" == 200 doesn't fail a
    check for the wrong reason, while still flagging genuine mismatches.

Every assertion is logged so the reporting layer captures the evidence
trail automatically.
"""

from __future__ import annotations

import time
from typing import Any, Callable

from framework.utils.logger import get_logger

log = get_logger("auto_assert")


class SoftAssert:
    """Accumulates assertion failures; ``assert_all`` raises them as one."""

    def __init__(self) -> None:
        self._failures: list[str] = []

    # ── primitive checks ─────────────────────────────────────────
    def equal(self, actual: Any, expected: Any, msg: str = "") -> bool:
        ok = actual == expected or str(actual) == str(expected)
        self._record(ok, f"{msg or 'equal'}: expected={expected!r} actual={actual!r}")
        return ok

    def true(self, condition: bool, msg: str = "") -> bool:
        self._record(bool(condition), f"{msg or 'true'}: condition was {condition!r}")
        return bool(condition)

    def contains(self, container: Any, member: Any, msg: str = "") -> bool:
        ok = member in container
        self._record(ok, f"{msg or 'contains'}: {member!r} not in {container!r}")
        return ok

    # ── smart waiting check ──────────────────────────────────────
    def eventually(
        self,
        predicate: Callable[[], bool],
        msg: str = "",
        timeout_s: float = 5.0,
        interval_s: float = 0.25,
    ) -> bool:
        """Pass if ``predicate()`` becomes True within ``timeout_s``."""
        deadline = time.monotonic() + timeout_s
        last = False
        while time.monotonic() < deadline:
            try:
                last = bool(predicate())
            except Exception:  # noqa: BLE001 - predicate may raise mid-wait
                last = False
            if last:
                break
            time.sleep(interval_s)
        self._record(last, f"{msg or 'eventually'}: not satisfied in {timeout_s}s")
        return last

    # ── internals ────────────────────────────────────────────────
    def _record(self, ok: bool, detail: str) -> None:
        if ok:
            log.info("[green]PASS[/] %s", detail)
        else:
            log.error("[red]FAIL[/] %s", detail)
            self._failures.append(detail)

    def assert_all(self) -> None:
        """Raise a combined AssertionError if any soft check failed."""
        if self._failures:
            joined = "\n  - ".join(self._failures)
            count = len(self._failures)
            self._failures.clear()
            raise AssertionError(f"{count} soft assertion(s) failed:\n  - {joined}")
