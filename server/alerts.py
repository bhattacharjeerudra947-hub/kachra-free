"""Threshold alerts (AGENTS.md sections 3 and 12).

After every truck location update, check_alerts() recomputes the ETA of
every resident who registered with that truck's ID. The first time an ETA
drops to the resident's chosen threshold (or the truck is already at their
collection point), it records one alert for that run. So the alert fires
once per collection round, not on every later update. The resident app
picks it up from GET /api/residents/status and shows it as a notification.
"""

import time

import eta


def check_alerts(data, truck_id):
    """Caller holds db.lock."""
    for phone, resident in data["residents"].items():
        if resident.get("truckId") != truck_id:
            continue

        status = eta.resident_status(data, resident)
        if status["status"] not in ("on_the_way", "at_stop"):
            continue
        if status["etaMinutes"] > resident["alertMinutes"]:
            continue

        key = f"{phone}|{status['runId']}"
        if key in data["alerts"]:
            continue

        if status["status"] == "at_stop":
            message = f"The garbage truck is at {status['stopName']} now."
        else:
            message = f"The garbage truck is about {status['etaMinutes']} minutes from {status['stopName']}."
        data["alerts"][key] = {"message": message, "sentAt": time.time()}
