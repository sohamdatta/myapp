#!/usr/bin/env python3
"""Writes employees.csv: 40 fictional people for a demo Organization, in the columns the People > Import screen reads.

Everything is made up and repeatable (run it again and the file is identical):
- names are combinations of common first and last names; none belongs to a real person;
- emails use example.com, a domain reserved for examples, so nothing can be delivered;
- PANs follow the format (P as the fourth letter, then the surname's first letter) with DEMO prefixes and counting digits;
- bank accounts are counting digits, the IFSC is DEMO0000001 and the bank is "Demo Bank";
- no mobile numbers, Aadhaar numbers or UANs, so none can match a real person.
"""
import csv
import random
from datetime import date

random.seed(20261106)

FIRST_F = ["Ananya", "Diya", "Meera", "Priya", "Kavya", "Isha", "Neha", "Sana", "Lakshmi", "Pooja", "Riya", "Tanvi", "Aarti", "Nisha"]
FIRST_M = ["Arjun", "Rohan", "Vikram", "Karan", "Aditya", "Rahul", "Sanjay", "Imran", "Suresh", "Nikhil", "Manoj", "Deepak", "Harsh", "Varun"]
LAST = ["Sharma", "Iyer", "Nair", "Reddy", "Patel", "Khan", "Das", "Mehta", "Gupta", "Menon", "Joshi", "Bose", "Singh", "Pillai", "Kulkarni", "Chopra"]

# (department, head designation, [team designations]); the first row is the CEO, who reports to nobody.
ORG = [
    ("Leadership", "Chief Executive Officer", []),
    ("Engineering", "VP Engineering", ["Engineering Manager", "Senior Software Engineer", "Software Engineer", "Software Engineer", "QA Engineer", "DevOps Engineer", "Software Engineer"]),
    ("Product", "Head of Product", ["Product Manager", "Product Designer", "Business Analyst"]),
    ("Sales", "Head of Sales", ["Account Executive", "Account Executive", "Sales Development Representative", "Sales Development Representative"]),
    ("Finance", "Finance Controller", ["Senior Accountant", "Accountant", "Payroll Specialist"]),
    ("People", "HR Manager", ["HR Executive", "Talent Acquisition Specialist"]),
    ("Operations", "Operations Manager", ["Operations Executive", "Operations Executive", "Office Administrator"]),
    ("Customer Success", "Customer Success Manager", ["Support Engineer", "Support Engineer", "Customer Success Associate", "Customer Success Associate"]),
]

HEADER = ["Employee code", "Full name as on PAN", "Preferred name", "Gender", "Date of birth", "Joining date", "Employment type",
          "Probation end date", "Contract end date", "Designation", "Department", "Reporting manager", "Personal email", "PAN",
          "Account holder", "Bank account number", "IFSC", "Bank name", "Branch"]


def person(gender):
    first = random.choice(FIRST_F if gender == "Female" else FIRST_M)
    return first, random.choice(LAST)


def random_date(start, end):
    return date.fromordinal(random.randint(start.toordinal(), end.toordinal()))


rows, used = [], set()
number = 0
ceo_code = None
for dept, head, team in ORG:
    head_code = None
    for index, designation in enumerate([head] + team):
        number += 1
        code = f"DEMO{number:03d}"
        gender = random.choice(["Female", "Male"])
        while True:
            first, last = person(gender)
            if (first, last) not in used:
                used.add((first, last))
                break
        senior = index == 0
        joining = random_date(date(2019, 3, 1), date(2021, 12, 31)) if senior else random_date(date(2021, 6, 1), date(2026, 9, 15))
        age_years = random.randint(38, 52) if senior else random.randint(22, 40)
        dob = date(2026 - age_years, random.randint(1, 12), random.randint(1, 28))
        kind, probation_end, contract_end = "Permanent", "", ""
        if not senior and joining >= date(2026, 6, 1):
            kind, probation_end = "Probation", date(joining.year + (joining.month + 5) // 12, (joining.month + 5) % 12 + 1, joining.day).isoformat()
        elif not senior and designation in ("Office Administrator", "Customer Success Associate") and index % 2 == 0:
            kind, contract_end = "Contract", date(2027, 3, 31).isoformat()
        manager = "" if dept == "Leadership" else (ceo_code if senior else head_code)
        if dept == "Engineering" and designation in ("Senior Software Engineer", "QA Engineer", "DevOps Engineer", "Software Engineer") and index > 1:
            manager = rows[-index + 1][0] if False else head_code  # everyone reports to the head unless set below
        if senior:
            head_code = code
            if dept == "Leadership":
                ceo_code = code
        rows.append([code, f"{first} {last}", first, gender, dob.isoformat(), joining.isoformat(), kind, probation_end, contract_end,
                     designation, dept, manager, f"{first}.{last}.{code.lower()}@example.com".lower(),
                     f"DEMP{last[0]}{number:04d}{chr(65 + number % 26)}", f"{first} {last}", f"1000000{number:07d}", "DEMO0000001", "Demo Bank", "Head Office"])

# Engineering: the engineering manager leads the engineers.
eng_manager = next(r[0] for r in rows if r[9] == "Engineering Manager")
for r in rows:
    if r[10] == "Engineering" and r[9] in ("Senior Software Engineer", "Software Engineer", "QA Engineer", "DevOps Engineer"):
        r[11] = eng_manager

with open("employees.csv", "w", newline="", encoding="utf-8") as f:
    writer = csv.writer(f)
    writer.writerow(HEADER)
    writer.writerows(rows)
print(f"employees.csv: {len(rows)} people in {len(ORG)} departments")
