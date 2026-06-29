"""Example Page Object: the authenticated home/dashboard page."""

from __future__ import annotations

from framework.core.base_page import BasePage


class HomePage(BasePage):
    locators = {
        "welcome": ["#welcome", ".dashboard-header", "text=Welcome"],
        "user_menu": ["#user-menu", "[data-test='user-menu']", ".avatar"],
        "logout": ["#logout", "text=Log out", "[data-test='logout']"],
        "search": ["#search", "input[name='q']", "input[placeholder*='Search']"],
    }

    def is_loaded(self) -> bool:
        return self.is_visible("welcome") or self.is_visible("user_menu")

    def search_for(self, term: str) -> None:
        self.fill("search", term)

    def logout(self) -> None:
        self.click("user_menu")
        self.click("logout")
