"""Database access layer for DB and cross-integration testing.

Uses SQLAlchemy Core so the same client works across PostgreSQL, MySQL,
etc. — the dialect/host/credentials all come from the environment config.
Connections are pooled and queries return plain dicts for easy assertion.
"""

from __future__ import annotations

from contextlib import contextmanager
from typing import Any, Iterator

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

from config.settings import settings
from framework.utils.logger import get_logger

log = get_logger("db")


class DbClient:
    def __init__(self, url: str | None = None) -> None:
        self._url = url or settings.db.url
        self._engine: Engine | None = None

    @property
    def engine(self) -> Engine:
        if self._engine is None:
            # pool_pre_ping guards against stale connections in long suites.
            self._engine = create_engine(self._url, pool_pre_ping=True, future=True)
            log.info("DB engine ready for %s", settings.db.name)
        return self._engine

    def query(self, sql: str, **params: Any) -> list[dict[str, Any]]:
        """Run a SELECT and return rows as a list of dicts."""
        log.info("QUERY: %s", sql)
        with self.engine.connect() as conn:
            result = conn.execute(text(sql), params)
            return [dict(row) for row in result.mappings()]

    def scalar(self, sql: str, **params: Any) -> Any:
        """Return a single scalar value (e.g. COUNT(*))."""
        with self.engine.connect() as conn:
            return conn.execute(text(sql), params).scalar()

    def execute(self, sql: str, **params: Any) -> int:
        """Run an INSERT/UPDATE/DELETE inside a transaction; return rowcount."""
        log.info("EXECUTE: %s", sql)
        with self.engine.begin() as conn:
            result = conn.execute(text(sql), params)
            return result.rowcount

    @contextmanager
    def transaction(self) -> Iterator[Any]:
        """Explicit transaction scope, e.g. for multi-statement setup/teardown."""
        with self.engine.begin() as conn:
            yield conn

    def row_exists(self, table: str, where: str, **params: Any) -> bool:
        count = self.scalar(f"SELECT COUNT(*) FROM {table} WHERE {where}", **params)
        return bool(count)

    def dispose(self) -> None:
        if self._engine is not None:
            self._engine.dispose()
            self._engine = None
