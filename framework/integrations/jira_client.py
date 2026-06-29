"""Jira integration: link tests to Jira issues/test-cases and push results.

Credentials come from env vars (JIRA_URL, JIRA_EMAIL, JIRA_API_TOKEN). The
client is lazily connected so importing it never requires Jira to be
reachable — tests that don't use Jira pay nothing.
"""

from __future__ import annotations

import os

from framework.utils.logger import get_logger

log = get_logger("jira")


class JiraClient:
    def __init__(self) -> None:
        self._client = None

    def _connect(self):
        if self._client is not None:
            return self._client
        url = os.getenv("JIRA_URL")
        email = os.getenv("JIRA_EMAIL")
        token = os.getenv("JIRA_API_TOKEN")
        if not all([url, email, token]):
            raise RuntimeError(
                "Jira not configured. Set JIRA_URL, JIRA_EMAIL, JIRA_API_TOKEN."
            )
        from jira import JIRA  # imported lazily

        self._client = JIRA(server=url, basic_auth=(email, token))
        log.info("Connected to Jira at %s", url)
        return self._client

    def get_issue(self, key: str) -> dict:
        """Fetch summary/description/status for a Jira issue (e.g. test case)."""
        issue = self._connect().issue(key)
        return {
            "key": issue.key,
            "summary": issue.fields.summary,
            "description": issue.fields.description or "",
            "status": str(issue.fields.status),
        }

    def add_result_comment(self, key: str, passed: bool, details: str = "") -> None:
        """Post an automated result comment back onto the issue."""
        verdict = "✅ PASSED" if passed else "❌ FAILED"
        body = f"Automated test result: {verdict}\n{details}".strip()
        self._connect().add_comment(key, body)
        log.info("Posted result to %s: %s", key, verdict)


jira_client = JiraClient()
