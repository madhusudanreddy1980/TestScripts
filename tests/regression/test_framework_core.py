"""Offline unit tests for framework core building blocks.

No browser, DB, or network required — these validate the data generator,
soft-assert engine, auto-correct retry, and settings loader so the framework
itself is covered by tests.
"""

import pytest

from config.settings import get_settings
from framework.core.auto_assert import SoftAssert
from framework.core.auto_correct import auto_correct
from framework.data.data_generator import DataGenerator, seed


@pytest.mark.regression
def test_data_generator_user_is_well_formed():
    seed(42)
    user = DataGenerator().user()
    assert "@" in user.email
    assert len(user.password) >= 12
    assert user.username


@pytest.mark.regression
def test_data_generator_is_reproducible_with_seed():
    seed(7)
    first = DataGenerator().user().email
    seed(7)
    second = DataGenerator().user().email
    assert first == second


@pytest.mark.regression
def test_soft_assert_collects_failures():
    soft = SoftAssert()
    soft.equal(1, 1, msg="ok")
    soft.equal(1, 2, msg="bad-one")
    soft.true(False, msg="bad-two")
    with pytest.raises(AssertionError) as exc:
        soft.assert_all()
    assert "2 soft assertion(s) failed" in str(exc.value)


@pytest.mark.regression
def test_soft_assert_type_coercion():
    soft = SoftAssert()
    soft.equal("200", 200, msg="coerced equal")
    soft.assert_all()  # should not raise


@pytest.mark.regression
def test_auto_correct_retries_then_succeeds():
    calls = {"n": 0}

    def flaky():
        calls["n"] += 1
        if calls["n"] < 3:
            from playwright.sync_api import TimeoutError as PWTimeoutError
            raise PWTimeoutError("transient")
        return "ok"

    assert auto_correct(flaky, attempts=5, base_wait=0.01) == "ok"
    assert calls["n"] == 3


@pytest.mark.regression
def test_settings_loads_for_env(monkeypatch):
    monkeypatch.setenv("TEST_ENV", "qa")
    get_settings.cache_clear()
    s = get_settings()
    assert s.env == "qa"
    assert s.api.base_url.startswith("http")
