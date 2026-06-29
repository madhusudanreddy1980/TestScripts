"""Shared logging setup using Rich for readable, colourised output."""

from __future__ import annotations

import logging
from functools import lru_cache

from rich.logging import RichHandler


@lru_cache(maxsize=None)
def get_logger(name: str = "framework") -> logging.Logger:
    """Return a configured logger. Cached so repeated calls reuse handlers."""
    logger = logging.getLogger(name)
    if logger.handlers:
        return logger

    handler = RichHandler(rich_tracebacks=True, show_path=False, markup=True)
    handler.setFormatter(logging.Formatter("%(message)s", datefmt="[%X]"))
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)
    logger.propagate = False
    return logger
