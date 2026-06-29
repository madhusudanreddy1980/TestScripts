"""Prompt layer — bridges Jira/ADO test cases to the automation framework.

This is the "AI" seam of the framework. Given a manual test case pulled from
Jira or Azure DevOps, it asks an LLM (Claude) to:

  * produce structured, executable steps for the framework, and/or
  * scaffold a pytest test stub that targets the existing page objects /
    API / DB clients.

The LLM call is optional and lazy: if ANTHROPIC_API_KEY is unset, the layer
falls back to a deterministic heuristic parser so the framework still runs
end-to-end without network access.
"""

from __future__ import annotations

import json
import os
import re

from framework.integrations.ado_client import ado_client
from framework.integrations.jira_client import jira_client
from framework.utils.logger import get_logger

log = get_logger("prompt_layer")

_SYSTEM_PROMPT = (
    "You convert manual QA test cases into structured automation steps for a "
    "Python + Playwright framework that uses a Page Object Model, an ApiClient, "
    "and a DbClient. Respond ONLY with compact JSON of the form: "
    '{"title": str, "type": "gui|api|db|e2e", "steps": [{"action": str, '
    '"target": str, "data": str, "expected": str}]}.'
)


class PromptLayer:
    def __init__(self, model: str | None = None) -> None:
        self.model = model or os.getenv("PROMPT_MODEL", "claude-opus-4-8")

    # ── source loaders ───────────────────────────────────────────
    def from_jira(self, issue_key: str) -> dict:
        issue = jira_client.get_issue(issue_key)
        return self.to_steps(f"{issue['summary']}\n\n{issue['description']}")

    def from_ado(self, work_item_id: int) -> dict:
        item = ado_client.get_work_item(work_item_id)
        return self.to_steps(f"{item['title']}\n\n{item['description']}\n{item['steps']}")

    # ── core conversion ──────────────────────────────────────────
    def to_steps(self, test_case_text: str) -> dict:
        """Turn free-text test-case content into structured steps."""
        if os.getenv("ANTHROPIC_API_KEY"):
            try:
                return self._llm_to_steps(test_case_text)
            except Exception as exc:  # noqa: BLE001
                log.warning("LLM step generation failed (%s); using heuristic", exc)
        return self._heuristic_to_steps(test_case_text)

    def _llm_to_steps(self, text: str) -> dict:
        from anthropic import Anthropic  # lazy import

        client = Anthropic(api_key=os.getenv("ANTHROPIC_API_KEY"))
        msg = client.messages.create(
            model=self.model,
            max_tokens=1500,
            system=_SYSTEM_PROMPT,
            messages=[{"role": "user", "content": text}],
        )
        raw = "".join(block.text for block in msg.content if block.type == "text")
        # Be defensive: pull the JSON object out of any surrounding prose.
        match = re.search(r"\{.*\}", raw, re.DOTALL)
        return json.loads(match.group(0) if match else raw)

    def _heuristic_to_steps(self, text: str) -> dict:
        """Offline fallback: parse imperative lines into rough steps."""
        steps = []
        verbs = ("click", "enter", "type", "navigate", "go to", "verify",
                 "select", "open", "submit", "check", "assert")
        for line in (ln.strip(" -*\t") for ln in text.splitlines()):
            if not line:
                continue
            lower = line.lower()
            if any(lower.startswith(v) or f" {v} " in lower for v in verbs):
                action = next((v for v in verbs if v in lower), "do")
                steps.append({
                    "action": action,
                    "target": line,
                    "data": "",
                    "expected": "",
                })
        title = next((ln.strip() for ln in text.splitlines() if ln.strip()), "Test case")
        return {"title": title, "type": "e2e", "steps": steps}

    # ── scaffolding ──────────────────────────────────────────────
    def scaffold_test(self, structured: dict) -> str:
        """Render a runnable pytest stub from structured steps."""
        slug = re.sub(r"[^a-z0-9]+", "_", structured["title"].lower()).strip("_")
        marker = structured.get("type", "e2e")
        lines = [
            "import pytest",
            "",
            f"@pytest.mark.{marker}",
            f"def test_{slug}():",
            f'    """{structured["title"]}"""',
        ]
        for step in structured["steps"]:
            lines.append(f"    # {step['action'].upper()}: {step['target']}")
            if step.get("expected"):
                lines.append(f"    #   expect: {step['expected']}")
        lines.append("    pytest.skip('Generated stub — implement against page objects')")
        return "\n".join(lines) + "\n"


prompt_layer = PromptLayer()
