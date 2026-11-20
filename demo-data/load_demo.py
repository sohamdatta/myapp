#!/usr/bin/env python3
"""Loads the demo data into a running copy of the app through its own API, as an HR admin or owner of a DEMO organization.

    DEMO_PASSWORD=... python3 load_demo.py --base https://demo.example.org --email hr@yourdemo.org            # checks only
    DEMO_PASSWORD=... python3 load_demo.py --base https://demo.example.org --email hr@yourdemo.org --run      # writes

Steps, in order (each safe to see in the app afterwards):
  1. people      employees.csv through the same importer as People > Import
  2. logins      gives every imported person a sign-in (invitation accepted here with the password in DEMO_LOGIN_PASSWORD,
                 default "Demo-Login-2026!"), which attendance and leave need. No email is sent.
  3. pay         one salary revision per person, from 1 Apr 2026 or their joining date if later
  4. leave       a few leave requests recorded by HR
  5. attendance  attendance_punches.csv through the device-import endpoint

Never point this at the production database that holds real data."""
import argparse
import csv
import json
import os
import random
import sys
import urllib.error
import urllib.request
from datetime import date
from pathlib import Path

HERE = Path(__file__).parent


class Api:
    def __init__(self, base):
        self.base, self.token = base.rstrip("/"), None

    def call(self, method, path, body=None, ok=(200, 201, 202, 204)):
        req = urllib.request.Request(self.base + "/api" + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + self.token} if self.token else {})})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                raw = r.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            detail = e.read().decode()[:400]
            raise SystemExit(f"{method} {path} -> {e.code}: {detail}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--email", required=True)
    ap.add_argument("--organization", help="name of the organization, when the login belongs to several")
    ap.add_argument("--run", action="store_true", help="write the data; without it only the checks run")
    a = ap.parse_args()
    password = os.environ.get("DEMO_PASSWORD") or sys.exit("Set DEMO_PASSWORD to the password of --email")
    login_password = os.environ.get("DEMO_LOGIN_PASSWORD", "Demo-Login-2026!")

    api = Api(a.base)
    me = api.call("POST", "/auth/login", {"email": a.email, "password": password})
    api.token = me["token"]
    orgs = me.get("organizations") or []
    org = next((o for o in orgs if not a.organization or o.get("tenantName") == a.organization), None) or sys.exit("No such organization on this login")
    api.token = api.call("POST", "/auth/enter", {"organizationId": org["tenantId"]})["token"]
    print(f"signed in to {org.get("tenantName")}")

    content = (HERE / "employees.csv").read_text(encoding="utf-8")
    read = api.call("POST", "/org/people/import/read", {"content": content})
    request = {"content": content, "mapping": read["mapping"]}
    preview = api.call("POST", "/org/people/import/preview", request)
    print(f"people: {preview['ready']} ready, {preview['refused']} refused")
    if not a.run:
        print("checks done; add --run to write")
        return
    if preview["ready"]:
        ran = api.call("POST", "/org/people/import/run", request)
        print("people imported:", {k: v for k, v in ran.items() if isinstance(v, (int, str))})
    else:
        print("people: already loaded, carrying on with the rest")

    people = {p["employeeCode"]: p for p in api.call("GET", "/org/people/employees")}
    codes = [c["Employee code"] for c in csv.DictReader(open(HERE / "employees.csv", encoding="utf-8"))]
    ids = [people[c]["id"] for c in codes if c in people]

    invited = api.call("POST", "/org/people/import/invite", {"employmentIds": ids})
    accepted = 0
    for row in invited:
        if row.get("token"):
            api.call("POST", "/invitations/accept", {"token": row["token"], "password": login_password})
            accepted += 1
    print(f"logins: {accepted} created (password {login_password!r})")

    people = {p["employeeCode"]: p for p in api.call("GET", "/org/people/employees")}
    member_ids = [people[c]["membershipId"] for c in codes if c in people and people[c].get("membershipId")]
    locations = api.call("GET", "/org/attendance/locations")
    if not locations:
        locations = [api.call("POST", "/org/attendance/locations", {"name": "Demo Office", "timezone": "Asia/Kolkata", "punchCheck": "none"})]
    shifts = api.call("GET", "/org/attendance/shifts")
    if not shifts:
        shifts = [api.call("POST", "/org/attendance/shifts", {"name": "General", "startTime": "09:30", "endTime": "18:30", "graceMinutes": 15,
                                                               "fullDayMinutes": 480, "halfDayMinutes": 240})]
    api.call("POST", "/org/attendance/assignments", {"membershipIds": member_ids, "workLocationId": locations[0]["id"], "shiftId": shifts[0]["id"],
                                                     "weeklyOffs": [0, 6], "remoteDays": [], "effectiveFrom": "2026-04-01"})
    print("attendance: location and shift assigned to", len(member_ids), "people")

    setup = api.call("GET", "/org/payroll/setup")
    structure = setup["structures"][0]["id"]
    random.seed(7)
    start = date(2026, 4, 1).isoformat()
    for code in codes:
        p = people.get(code)
        if p:
            senior = code in ("DEMO001",) or "Head" in p.get("designation", "") or p.get("designation", "").startswith(("VP", "Chief"))
            annual = random.choice(range(36, 61)) * 25_000 if not senior else random.choice(range(60, 96)) * 25_000
            api.call("POST", f"/org/people/employees/{p['id']}/pay",
                     {"validFrom": max(start, p.get("joiningDate") or start), "structureId": structure, "annualAmount": annual, "reason": "Demo data"})
    print("pay: salary set for", len(ids), "people")

    people = {p["employeeCode"]: p for p in api.call("GET", "/org/people/employees")}
    types = [t for t in api.call("GET", "/org/leave/types") if t.get("active", True)]
    if not types:
        for name, accrual, quota in (("Casual Leave", "yearly", 12), ("Sick Leave", "yearly", 8), ("Earned Leave", "monthly", 18)):
            api.call("POST", "/org/leave/types", {"name": name, "kind": "regular", "paid": True, "accrual": accrual, "yearlyQuota": quota,
                                                  "prorateJoiners": True})
        types = [t for t in api.call("GET", "/org/leave/types") if t.get("active", True)]
        print("leave: created", len(types), "leave types")
    paid = next((t for t in types if t.get("paid")), types[0])
    members = [people[c].get("membershipId") for c in codes if c in people and people[c].get("membershipId")]
    random.seed(11)
    made = 0
    for m in random.sample(members, min(8, len(members))):
        month, day = random.choice([(10, 6), (10, 14), (10, 21), (10, 27)])
        api.call("POST", f"/org/leave/users/{m}/requests",
                 {"leaveTypeId": paid["id"], "fromDate": date(2026, month, day).isoformat(), "toDate": date(2026, month, day + 1).isoformat(),
                  "half": None, "reason": "Demo data"})
        made += 1
    print("leave:", made, "requests recorded")

    punches = (HERE / "attendance_punches.csv").read_text(encoding="utf-8")
    done = api.call("POST", "/org/attendance/imports",
                    {"deviceLabel": "Demo gate", "fileName": "attendance_punches.csv", "content": punches,
                     "mapping": {"hasHeader": True, "codeColumn": 0, "dateTimeColumn": 1, "directionColumn": 2,
                                 "format": "yyyy-MM-dd HH:mm:ss", "timezone": "Asia/Kolkata"}})
    print("attendance:", {k: v for k, v in done.items() if isinstance(v, (int, str))})


if __name__ == "__main__":
    main()
