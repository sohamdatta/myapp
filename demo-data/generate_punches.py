#!/usr/bin/env python3
"""Writes attendance_punches.csv: biometric-device style punches (employee code, date-time, direction) for the people in
employees.csv, on weekdays from 1 Oct to 6 Nov 2026. Repeatable: the same file every run.

Each person arrives around 9:30 and leaves around 18:30. Some days are late, a few are half days, a few are absences, and
Diwali week (9-13 Nov is outside the range) is not special-cased. Public holidays are left to the app's own calendar."""
import csv
import random
from datetime import date, datetime, timedelta

random.seed(20261107)
people = [r["Employee code"] for r in csv.DictReader(open("employees.csv", encoding="utf-8"))]
rows = []
day = date(2026, 10, 1)
while day <= date(2026, 11, 6):
    if day.weekday() < 5:
        for code in people:
            roll = random.random()
            if roll < 0.03:
                continue  # absent
            arrive = datetime(day.year, day.month, day.day, 9, 30) + timedelta(minutes=random.randint(-25, 20) + (45 if roll > 0.92 else 0))
            half = 0.03 <= roll < 0.05
            leave = datetime(day.year, day.month, day.day, 13, 30) if half else datetime(day.year, day.month, day.day, 18, 30)
            leave += timedelta(minutes=random.randint(-10, 45))
            rows.append((code, arrive.strftime("%Y-%m-%d %H:%M:%S"), "IN"))
            rows.append((code, leave.strftime("%Y-%m-%d %H:%M:%S"), "OUT"))
    day += timedelta(days=1)
with open("attendance_punches.csv", "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["Employee code", "Date and time", "Direction"])
    w.writerows(rows)
print(f"attendance_punches.csv: {len(rows)} punches")
