"""Smoke test: critical login path. Runs on every commit."""

import pytest

from pages.login_page import LoginPage
from pages.home_page import HomePage


@pytest.mark.smoke
@pytest.mark.gui
def test_login_page_loads(page):
    login = LoginPage(page).load()
    # Self-healing locators resolve the username field even if markup shifts.
    assert login.is_visible("username"), "Login form did not render"


@pytest.mark.smoke
@pytest.mark.gui
def test_valid_login(page, data, soft):
    user = data.user()
    login = LoginPage(page).load()
    login.login(user.username, user.password)

    home = HomePage(page)
    # Soft assertions: collect all checks, report together at teardown.
    soft.eventually(home.is_loaded, msg="dashboard should load after login")
    soft.true(home.is_visible("user_menu"), msg="user menu should appear")
