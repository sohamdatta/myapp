# User guides

Guides for customers, one per module: how to set it up and use it, screen by screen. The screenshots come from demo
Organizations made by the browser tests; to take them again, run the guide's project with `E2E_SHOTS=<folder>` (see each
guide's spec) and copy the files into `img/<module>/`.

| Module | Guide | Test cases (every setting and screen) | Browser tests |
| --- | --- | --- | --- |
| Attendance | [attendance.md](attendance.md) | [attendance-ui-test-cases.xlsx](../testing/attendance-ui-test-cases.xlsx) | `e2e/tests/40` to `43` |
| Leave | [leave.md](leave.md) | [leave-ui-test-cases.xlsx](../testing/leave-ui-test-cases.xlsx) | `e2e/tests/44-leave-cases`, `45-leave-guide` |
| People and org chart | [people.md](people.md) | [people-ui-test-cases.xlsx](../testing/people-ui-test-cases.xlsx) | `e2e/tests/46-people-cases`, `47-people-guide` |
| Exits | [exits.md](exits.md) | [exit-ui-test-cases.xlsx](../testing/exit-ui-test-cases.xlsx) | `e2e/tests/48-exit-cases`, `49-exit-guide` |
| Performance | [performance.md](performance.md) | [performance-ui-test-cases.xlsx](../testing/performance-ui-test-cases.xlsx) | `e2e/tests/50-performance-cases`, `51-performance-guide` |
| Payroll and tax declarations | [payroll.md](payroll.md) | [payroll-ui-test-cases.xlsx](../testing/payroll-ui-test-cases.xlsx) | `e2e/tests/52-payroll-cases`, `53-payroll-guide` |
