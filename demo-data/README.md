# Demo data

A fictional company of 34 people for demos. Nothing here belongs to a real person: example.com emails (nothing can be
delivered), made-up PANs (`DEMP...`), counting-digit bank accounts, IFSC `DEMO0000001`, and no mobile, Aadhaar or UAN numbers.

| File | What it is |
| --- | --- |
| `employees.csv` | 34 people, 8 departments, a reporting line from a CEO down. Loads through People > Import with no changes (34 ready, 0 refused). |
| `attendance_punches.csv` | 1,768 device-style punches (code, date-time, IN/OUT) on weekdays from 1 Oct to 6 Nov 2026, with some late days, half days and absences. |
| `load_demo.py` | Loads everything below through the app's own API. |
| `generate_employees.py`, `generate_punches.py` | Rebuild the two CSV files. The output is repeatable. |

## Where to load it

Use a separate demo Organization or the demo/UAT environment. Do not load it into the production database that holds real data.

## Load it

You need an HR admin or owner login of the demo Organization.

```
DEMO_PASSWORD='the login password' python3 load_demo.py --base https://your-demo-host --email hr@yourdemo.org          # checks only
DEMO_PASSWORD='the login password' python3 load_demo.py --base https://your-demo-host --email hr@yourdemo.org --run    # writes
```

With `--run` it does, in order:

1. imports `employees.csv` (departments are created for you);
2. gives every person a sign-in, password `Demo-Login-2026!` (change it with `DEMO_LOGIN_PASSWORD`). No email is sent, and the
   attendance and leave steps need these logins;
3. creates a "Demo Office" location, a 9:30 to 18:30 shift and Saturday/Sunday offs if none exist, and assigns everyone;
4. sets a salary for each person, using the first salary structure in Payroll setup (Payroll must be set up first);
5. creates Casual, Sick and Earned leave types if none exist, and records 8 leave requests for October;
6. imports `attendance_punches.csv`.

Run it once. A second run repeats the salaries and leave requests. Payroll runs, payslips and tax declarations are not
created; run payroll for October in the app to show them.

If you only want people, skip the script: sign in, open People > Import and choose `employees.csv`.
