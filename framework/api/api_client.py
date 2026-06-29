"""Thin, test-friendly REST client built on requests.

Features used across API / integration / E2E tests:
  * base-URL aware (per environment)
  * retry/backoff on idempotent calls (auto-correct at the network layer)
  * JSON-schema validation helper (auto-assertion for contract testing)
  * structured logging of every request/response
"""

from __future__ import annotations

from typing import Any

import requests
from jsonschema import ValidationError, validate
from tenacity import retry, retry_if_exception_type, stop_after_attempt, wait_exponential

from config.settings import settings
from framework.utils.logger import get_logger

log = get_logger("api")

_RETRYABLE = (requests.ConnectionError, requests.Timeout)


class ApiResponse:
    """Lightweight wrapper exposing common assertions on a response."""

    def __init__(self, response: requests.Response) -> None:
        self.raw = response
        self.status = response.status_code
        try:
            self.json: Any = response.json()
        except ValueError:
            self.json = None
        self.text = response.text

    def expect_status(self, *expected: int) -> "ApiResponse":
        assert self.status in expected, (
            f"Expected status in {expected}, got {self.status}: {self.text[:300]}"
        )
        return self

    def expect_schema(self, schema: dict) -> "ApiResponse":
        try:
            validate(instance=self.json, schema=schema)
        except ValidationError as exc:
            raise AssertionError(f"Response schema mismatch: {exc.message}") from exc
        return self


class ApiClient:
    def __init__(self, base_url: str | None = None, timeout: int | None = None) -> None:
        self.base_url = (base_url or settings.api.base_url).rstrip("/")
        self.timeout = timeout or settings.api.timeout_s
        self.session = requests.Session()

    def set_auth_token(self, token: str) -> None:
        self.session.headers["Authorization"] = f"Bearer {token}"

    @retry(
        reraise=True,
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=0.5, max=8),
        retry=retry_if_exception_type(_RETRYABLE),
    )
    def request(self, method: str, path: str, **kwargs) -> ApiResponse:
        url = path if path.startswith("http") else f"{self.base_url}/{path.lstrip('/')}"
        kwargs.setdefault("timeout", self.timeout)
        log.info("%s %s", method.upper(), url)
        resp = self.session.request(method, url, **kwargs)
        log.info("-> %s (%d bytes)", resp.status_code, len(resp.content))
        return ApiResponse(resp)

    def get(self, path: str, **kwargs) -> ApiResponse:
        return self.request("GET", path, **kwargs)

    def post(self, path: str, **kwargs) -> ApiResponse:
        return self.request("POST", path, **kwargs)

    def put(self, path: str, **kwargs) -> ApiResponse:
        return self.request("PUT", path, **kwargs)

    def patch(self, path: str, **kwargs) -> ApiResponse:
        return self.request("PATCH", path, **kwargs)

    def delete(self, path: str, **kwargs) -> ApiResponse:
        return self.request("DELETE", path, **kwargs)
