"""Auto-correct: resilient action execution with retry/backoff.

Transient UI/network hiccups (animations mid-flight, momentary detaches,
slow XHR) are the usual cause of false-negative failures. ``auto_correct``
wraps any callable so it is retried with exponential backoff before being
allowed to fail — turning many flaky steps into deterministic passes.

This complements pytest-rerunfailures (whole-test reruns) by correcting at
the *action* level, which is faster and keeps state intact.
"""

from __future__ import annotations

from typing import Callable, TypeVar

from playwright.sync_api import Error as PWError, TimeoutError as PWTimeoutError
from tenacity import (
    retry,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential,
)

from framework.utils.logger import get_logger

log = get_logger("auto_correct")

T = TypeVar("T")

# Exceptions we consider "correctable" — transient by nature.
CORRECTABLE = (PWTimeoutError, PWError)


def auto_correct(
    func: Callable[..., T],
    *args,
    attempts: int = 3,
    base_wait: float = 0.5,
    **kwargs,
) -> T:
    """Run ``func(*args, **kwargs)`` with retry + exponential backoff.

    Raises the last exception if every attempt fails.
    """

    @retry(
        reraise=True,
        stop=stop_after_attempt(attempts),
        wait=wait_exponential(multiplier=base_wait, max=8),
        retry=retry_if_exception_type(CORRECTABLE),
        before_sleep=lambda rs: log.warning(
            "[yellow]Auto-correct[/]: attempt %d/%d failed (%s), retrying...",
            rs.attempt_number,
            attempts,
            rs.outcome.exception().__class__.__name__,
        ),
    )
    def _runner() -> T:
        return func(*args, **kwargs)

    return _runner()
