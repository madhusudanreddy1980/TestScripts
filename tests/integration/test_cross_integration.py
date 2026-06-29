"""Cross-integration tests: verify consistency *across* systems.

These exercise the seam between the API and the database — the kind of bug
that unit and single-layer tests miss: the API says it created something,
but did it actually land in the DB with the right values?
"""

import pytest


@pytest.mark.integration
def test_api_create_reflects_in_db(api, db, data, soft):
    payload = data.user().as_dict()

    created = api.post("/users", json=payload).expect_status(200, 201)
    user_id = created.json["id"]

    # The cross-system assertion: API write must be visible in the DB.
    row = db.query("SELECT email, username FROM users WHERE id = :id", id=user_id)
    soft.true(len(row) == 1, msg="user row should exist in DB after API create")
    if row:
        soft.equal(row[0]["email"], payload["email"], msg="email matches across systems")
        soft.equal(row[0]["username"], payload["username"], msg="username matches")


@pytest.mark.integration
def test_db_change_visible_via_api(api, db, data):
    """A direct DB update should surface through the read API."""
    new_email = data.email()
    affected = db.execute(
        "UPDATE users SET email = :email WHERE role = :role",
        email=new_email, role="admin",
    )
    assert affected >= 1

    resp = api.get("/users?role=admin").expect_status(200)
    emails = [u.get("email") for u in resp.json]
    assert new_email in emails, "DB update not reflected through API"
