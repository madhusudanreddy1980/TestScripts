"""Central, environment-aware settings loader.

Resolves configuration in this order of precedence (highest wins):
    1. Environment variables  (e.g. TEST_ENV, REPORT_TYPE, DB_PASSWORD)
    2. The selected ``config/<env>.yaml`` file
    3. Hard-coded defaults below

``TEST_ENV`` drives which YAML is loaded, so the same suite runs against
dev / qa / uat just by flipping one variable — locally or in CI.
"""

from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Any

import yaml
from dotenv import load_dotenv
from pydantic import BaseModel, Field

load_dotenv()  # pull in a local .env if present

CONFIG_DIR = Path(__file__).resolve().parent
VALID_ENVS = {"dev", "qa", "uat"}


class WebConfig(BaseModel):
    base_url: str = "http://localhost"
    timeout_ms: int = 30000


class ApiConfig(BaseModel):
    base_url: str = "http://localhost"
    timeout_s: int = 30


class DbConfig(BaseModel):
    dialect: str = "postgresql+psycopg2"
    host: str = "localhost"
    port: int = 5432
    name: str = "appdb"
    user: str = Field(default_factory=lambda: os.getenv("DB_USER", ""))
    password: str = Field(default_factory=lambda: os.getenv("DB_PASSWORD", ""))

    @property
    def url(self) -> str:
        """SQLAlchemy connection URL for the configured database."""
        return (
            f"{self.dialect}://{self.user}:{self.password}"
            f"@{self.host}:{self.port}/{self.name}"
        )


class IntegrationsConfig(BaseModel):
    jira_project: str = ""
    ado_test_plan: str = ""


class Settings(BaseModel):
    env: str = "qa"
    web: WebConfig = WebConfig()
    api: ApiConfig = ApiConfig()
    db: DbConfig = DbConfig()
    integrations: IntegrationsConfig = IntegrationsConfig()

    # Runtime knobs (driven by env vars, switchable on the fly)
    browser: str = Field(default_factory=lambda: os.getenv("BROWSER", "chromium"))
    headless: bool = Field(
        default_factory=lambda: os.getenv("HEADLESS", "true").lower() == "true"
    )
    report_type: str = Field(
        default_factory=lambda: os.getenv("REPORT_TYPE", "allure").lower()
    )


def _load_yaml(env: str) -> dict[str, Any]:
    path = CONFIG_DIR / f"{env}.yaml"
    if not path.exists():
        raise FileNotFoundError(
            f"No config file for environment '{env}'. Expected {path}."
        )
    with path.open("r", encoding="utf-8") as fh:
        return yaml.safe_load(fh) or {}


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Build (and cache) the Settings object for the active environment."""
    env = os.getenv("TEST_ENV", "qa").lower()
    if env not in VALID_ENVS:
        raise ValueError(
            f"TEST_ENV='{env}' is invalid. Choose one of: {sorted(VALID_ENVS)}"
        )

    raw = _load_yaml(env)

    # DB credentials always come from env vars (never committed to YAML).
    db_raw = raw.get("db", {})
    db_raw.setdefault("user", os.getenv("DB_USER", ""))
    db_raw.setdefault("password", os.getenv("DB_PASSWORD", ""))
    raw["db"] = db_raw

    return Settings(**raw)


# Convenient module-level singleton.
settings = get_settings()
