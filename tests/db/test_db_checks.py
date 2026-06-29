"""DB tests: data integrity and query-level assertions."""

import pytest


@pytest.mark.db
@pytest.mark.smoke
def test_db_connectivity(db):
    # Trivial round-trip proves the configured environment DB is reachable.
    assert db.scalar("SELECT 1") == 1


@pytest.mark.db
@pytest.mark.regression
def test_no_orphaned_orders(db):
    """Every order must reference an existing customer."""
    orphans = db.scalar(
        "SELECT COUNT(*) FROM orders o "
        "LEFT JOIN customers c ON o.customer_id = c.id "
        "WHERE c.id IS NULL"
    )
    assert orphans == 0, f"Found {orphans} orphaned orders"


@pytest.mark.db
def test_seeded_admin_exists(db):
    assert db.row_exists("users", "role = :role", role="admin")
