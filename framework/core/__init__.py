from framework.core.auto_assert import SoftAssert
from framework.core.auto_correct import auto_correct
from framework.core.base_page import BasePage
from framework.core.driver_factory import browser_context, new_page
from framework.core.self_healing import HealingLocator, heal_summary, reset_heal_log

__all__ = [
    "SoftAssert",
    "auto_correct",
    "BasePage",
    "browser_context",
    "new_page",
    "HealingLocator",
    "heal_summary",
    "reset_heal_log",
]
