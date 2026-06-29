"""End-to-end journey spanning API setup, UI flow, and DB verification.

Demonstrates the full stack working together with auto-healing locators,
auto-correct actions, auto-generated data, and soft assertions.
"""

import pytest

from pages.login_page import LoginPage
from pages.home_page import HomePage


@pytest.mark.e2e
@pytest.mark.regression
def test_signup_login_search_journey(page, api, db, data, soft):
    # 1) Arrange via API: create the user the UI will log in as.
    user = data.user()
    api.post("/users", json=user.as_dict()).expect_status(200, 201)

    # 2) Act via UI: log in and use the app.
    login = LoginPage(page).load()
    login.login(user.username, user.password)

    home = HomePage(page)
    soft.eventually(home.is_loaded, msg="home should load after login")
    home.search_for("widgets")

    # 3) Assert via DB: a search event was recorded for the user.
    logged = db.row_exists(
        "activity_log",
        "username = :u AND action = :a",
        u=user.username, a="search",
    )
    soft.true(logged, msg="search activity should be persisted")
