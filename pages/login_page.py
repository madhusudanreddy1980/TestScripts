"""Example Page Object: a login page.

Note how locators are declared as *lists* of candidate selectors. The
self-healing engine tries them in order, so a markup change that only breaks
the first selector still lets the test pass via a fallback.
"""

from __future__ import annotations

from framework.core.base_page import BasePage


class LoginPage(BasePage):
    locators = {
        "username": ["#username", "input[name='username']", "input[type='email']"],
        "password": ["#password", "input[name='password']", "input[type='password']"],
        "submit": ["#login", "button[type='submit']", "text=Log in"],
        "error_banner": [".error", "[role='alert']", "text=Invalid"],
    }

    PATH = "/login"

    def load(self) -> "LoginPage":
        self.open(self.PATH)
        return self

    def login(self, username: str, password: str) -> None:
        self.fill("username", username)
        self.fill("password", password)
        self.click("submit")

    def error_text(self) -> str:
        return self.text_of("error_banner")
