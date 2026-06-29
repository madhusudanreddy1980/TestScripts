"""Azure DevOps (ADO) integration: pull work items / test cases.

Credentials from env vars (ADO_ORG_URL, ADO_PROJECT, ADO_PAT). Like the Jira
client, the connection is lazy so unused imports cost nothing.
"""

from __future__ import annotations

import os

from framework.utils.logger import get_logger

log = get_logger("ado")


class AdoClient:
    def __init__(self) -> None:
        self._connection = None

    def _connect(self):
        if self._connection is not None:
            return self._connection
        org_url = os.getenv("ADO_ORG_URL")
        pat = os.getenv("ADO_PAT")
        if not all([org_url, pat]):
            raise RuntimeError("ADO not configured. Set ADO_ORG_URL and ADO_PAT.")

        from azure.devops.connection import Connection  # lazy import
        from msrest.authentication import BasicAuthentication

        creds = BasicAuthentication("", pat)
        self._connection = Connection(base_url=org_url, creds=creds)
        log.info("Connected to ADO at %s", org_url)
        return self._connection

    def get_work_item(self, work_item_id: int) -> dict:
        """Fetch a work item (e.g. a Test Case) by id."""
        client = self._connect().clients.get_work_item_tracking_client()
        item = client.get_work_item(work_item_id)
        fields = item.fields or {}
        return {
            "id": item.id,
            "title": fields.get("System.Title", ""),
            "description": fields.get("System.Description", ""),
            "state": fields.get("System.State", ""),
            "steps": fields.get("Microsoft.VSTS.TCM.Steps", ""),
        }


ado_client = AdoClient()
