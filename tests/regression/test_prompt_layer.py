"""Regression tests for the prompt layer's offline (heuristic) path.

These run without any network/LLM/Jira/ADO access, proving the framework is
usable end-to-end even when integrations aren't configured.
"""

import pytest

from framework.integrations.prompt_layer import PromptLayer


@pytest.mark.regression
def test_heuristic_step_extraction(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    text = (
        "Login regression\n"
        "1. Navigate to /login\n"
        "2. Enter valid username and password\n"
        "3. Click the submit button\n"
        "4. Verify the dashboard is displayed\n"
    )
    structured = PromptLayer().to_steps(text)
    assert structured["title"] == "Login regression"
    actions = [s["action"] for s in structured["steps"]]
    assert {"navigate", "enter", "click", "verify"}.issubset(set(actions))


@pytest.mark.regression
def test_scaffold_generates_runnable_stub():
    structured = {
        "title": "Add item to cart",
        "type": "e2e",
        "steps": [{"action": "click", "target": "Add to cart", "data": "", "expected": "in cart"}],
    }
    code = PromptLayer().scaffold_test(structured)
    assert "def test_add_item_to_cart():" in code
    assert "@pytest.mark.e2e" in code
    compile(code, "<generated>", "exec")  # must be valid Python
