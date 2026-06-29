"""API tests: status, contract (schema), and CRUD round-trips."""

import pytest

USER_SCHEMA = {
    "type": "object",
    "required": ["id", "email"],
    "properties": {
        "id": {"type": ["integer", "string"]},
        "email": {"type": "string"},
    },
}


@pytest.mark.smoke
@pytest.mark.api
def test_health_endpoint(api):
    api.get("/health").expect_status(200)


@pytest.mark.api
def test_create_user_contract(api, data):
    payload = data.user().as_dict()
    resp = api.post("/users", json=payload)
    resp.expect_status(200, 201).expect_schema(USER_SCHEMA)


@pytest.mark.regression
@pytest.mark.api
def test_user_crud_roundtrip(api, data, soft):
    payload = data.user().as_dict()
    created = api.post("/users", json=payload).expect_status(200, 201)
    user_id = created.json["id"]

    fetched = api.get(f"/users/{user_id}").expect_status(200)
    soft.equal(fetched.json["email"], payload["email"], msg="email persisted")

    api.delete(f"/users/{user_id}").expect_status(200, 204)
    api.get(f"/users/{user_id}").expect_status(404)
