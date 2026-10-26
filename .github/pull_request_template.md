## What and why

<!-- One or two sentences: what changes, and what problem it solves. -->

## How it was tested

<!-- Tests added or changed, and anything checked by hand. -->

## Review checklist

Copy of docs/engineering-standards.md, section 11. Tick each line, or write why it does not apply.
A rule that cannot be met needs an exception (section 12): which rule, why, and the issue that will fix it.

**Correctness**
- [ ] Tests reproduce the bug or cover the new behaviour, including refusals
- [ ] Money changes come with golden-file diffs that match the consultant's figures
- [ ] Dates are safe at month end, 31 March and in both time zones

**Tenancy, security, privacy**
- [ ] New tables have `tenant_id` and forced row-level security
- [ ] New endpoints declare permission and scope, with a negative-access test
- [ ] Changes write audit events; identifiers stay encrypted and out of logs

**Java 21 and boilerplate**
- [ ] Records instead of maps for new requests, responses and rows
- [ ] Sealed types and pattern-matching `switch` for states and results
- [ ] Session injected (`SessionCtx` parameter), not parsed from the header
- [ ] Text blocks for SQL; `java.time` types in requests

**Performance**
- [ ] No queries inside loops; batches for multi-row writes
- [ ] New queries checked with `EXPLAIN ANALYZE` on the seed Organization; indexes added
- [ ] Lists paginated

**Migrations**
- [ ] Squawk clean, or each warning explained
- [ ] Schema diff reviewed; breaking changes follow expand-then-contract and carry the `breaking-change` label
- [ ] Header comment says what changes and how to undo it

**Clean code**
- [ ] Files, methods and parameters within the limits in section 4
- [ ] Comments explain why; no dead code or untracked TODOs
- [ ] Error messages say what is wrong and what to do

**Docs**
- [ ] The design page in `docs/` updated; README status updated if a limitation changed
