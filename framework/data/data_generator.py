"""Auto data generators built on Faker.

Provides realistic, randomised-but-reproducible test data (users, addresses,
payloads). A fixed-but-overridable seed keeps runs reproducible for
debugging while still exercising varied inputs.
"""

from __future__ import annotations

import random
import string
from dataclasses import asdict, dataclass

from faker import Faker

_fake = Faker()


def seed(value: int) -> None:
    """Seed both Faker and random for reproducible data in a given run."""
    Faker.seed(value)
    random.seed(value)


@dataclass
class User:
    first_name: str
    last_name: str
    email: str
    username: str
    password: str
    phone: str

    def as_dict(self) -> dict:
        return asdict(self)


class DataGenerator:
    """Factory for common test entities and primitives."""

    def __init__(self) -> None:
        self.fake = _fake

    # ── entities ─────────────────────────────────────────────────
    def user(self, domain: str = "example.com") -> User:
        first = self.fake.first_name()
        last = self.fake.last_name()
        username = f"{first.lower()}.{last.lower()}{random.randint(1, 9999)}"
        return User(
            first_name=first,
            last_name=last,
            email=f"{username}@{domain}",
            username=username,
            password=self.strong_password(),
            phone=self.fake.msisdn(),
        )

    def address(self) -> dict:
        return {
            "street": self.fake.street_address(),
            "city": self.fake.city(),
            "state": self.fake.state_abbr(),
            "zip": self.fake.postcode(),
            "country": self.fake.country_code(),
        }

    def company(self) -> dict:
        return {
            "name": self.fake.company(),
            "catch_phrase": self.fake.catch_phrase(),
            "ein": self.fake.numerify("##-#######"),
        }

    # ── primitives ───────────────────────────────────────────────
    def strong_password(self, length: int = 14) -> str:
        pools = [string.ascii_uppercase, string.ascii_lowercase,
                 string.digits, "!@#$%^&*"]
        required = [random.choice(p) for p in pools]
        rest = random.choices("".join(pools), k=max(0, length - len(required)))
        chars = required + rest
        random.shuffle(chars)
        return "".join(chars)

    def email(self, domain: str = "example.com") -> str:
        return f"{self.fake.user_name()}{random.randint(1, 9999)}@{domain}"

    def random_string(self, length: int = 10) -> str:
        return "".join(random.choices(string.ascii_letters, k=length))

    def uuid(self) -> str:
        return self.fake.uuid4()


# Module-level convenience instance.
data = DataGenerator()
