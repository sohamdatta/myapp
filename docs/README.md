# Design documents

Design and planning documents for the HRMS, kept beside the code they describe. Each module has a design, the SQL schema it was built from, and (for work not yet built) an implementation plan.

The versions here are Markdown exports. The live, editable versions are the claude.ai documents linked below, and the same material is in the [HRMS design Google Doc](https://docs.google.com/document/d/18HRTSNafJMzWADY90I0-F21xRxYwIX0eNXZDgonKk5g/edit), one tab per module. When a design changes, update the live document and export it here again in the same commit as the code.

## Modules

| Module | Status | Documents | Live version |
| --- | --- | --- | --- |
| Modules (what a customer pays for) | Built (V16) | [Core HR, Attendance and Payroll; switching, trials, payroll without attendance](modules.md) | |
| Users and access | Built | [Access model, detailed design and tested schema](notes/user-access-model.md) | [claude.ai](https://claude.ai/artifact/EZsRyKnqKYNWb7okS6Fszx) |
| Attendance | Built (commit `3aeabe4`) | [Features and competitive position](attendance/features.md) · [Implementation design](attendance/design.md) · [Schema (V3)](attendance/schema.md) · [Modes: present by default and biometric devices (V15)](attendance/modes.md) | [Features](https://claude.ai/code/artifact/61c2baf4-f684-46bf-9e89-8ad4828facae) · [Design](https://claude.ai/code/artifact/e10d5bc8-ad11-4ae5-8ed0-6ba6773fe050) · [Modes](https://claude.ai/code/artifact/976dcb87-e698-4983-8994-3408cd0b5d3d) |
| Leave and work from home | Built (commit `ebbeff0`) | [Features summary](notes/leave-and-wfh-features.md) · [Implementation design](leave-and-wfh/design.md) · [Schema (V4)](leave-and-wfh/schema.md) | [Design](https://claude.ai/code/artifact/669d7b0f-b526-4966-8bed-cf1e6d6b28b2) |
| Employee onboarding | Designed, not built | [Implementation design](onboarding/design.md) · [Schema](onboarding/schema.md) · [Implementation plan](onboarding/implementation-plan.md) · [Tested SQL](onboarding/V5__employee_onboarding.sql) | [Design](https://claude.ai/code/artifact/a9cccecf-9740-4693-ba98-db3990c8f14c) · [Plan](https://claude.ai/code/artifact/ae6f7b7c-452b-48f9-9196-c73995da3613) |
| Employee exit | Built (stages E1 to E6) | [Design, as built](exit/design.md) | [Design](https://claude.ai/code/artifact/7afa54a6-7167-4814-b837-4e05cd6dc72a) |
| Payroll | Release 1 built: setup, pay, monthly runs, PF, ESI, PT and LWF for Goa, Karnataka and Maharashtra, income tax | [Design, as built](payroll/design.md) · [Tax declarations and proofs (V17)](payroll/tax-declarations.md) · [Feature checklist](notes/payroll-feature-checklist.md) · [Implementation plan](notes/payroll-module-implementation-plan.md) | [Design](https://claude.ai/code/artifact/0af98c52-4583-4973-ac9d-be1a41213738) |

## Configuration

[Settings framework, as built (V18)](configuration/design.md): one registry of settings, values by Organization, location or pay group and from a date, platform ranges and locks, password or second-person approval for weakening changes, history and undo. Live design: [claude.ai](https://claude.ai/artifact/3wSpJUPbWwxVxE4KtNi3Ug).

## Roadmap

[Design for the next five releases](roadmap/next-releases.md): green CI, sign-in security (email, password reset, MFA), income tax filings (Forms 122, 138, 130), PF ECR and ESI returns, and the installable app. Live version: [claude.ai](https://claude.ai/artifact/RkLD2g8gjmfVprQ5pxRwUq).

## Notes on reading them

- **Which file wins.** For a built module, the migration under `backend/src/main/resources/db/migration/` is the source of truth; the schema page records what was designed and lists where the build differed.
- **Onboarding SQL.** `onboarding/V5__employee_onboarding.sql` loads on PostgreSQL 16 on top of V1 to V4 and passed its checks, but it is not a migration yet. The implementation plan splits it into V5 (employee record) and V6 (configuration and onboarding) when it is built.
- **Stack.** The earlier payroll and access documents sketch code in TypeScript. The product is built on React, Spring Boot and PostgreSQL; the database designs carry over unchanged.
- **Diagrams.** Charts in the live documents are given here as tables or Mermaid diagrams, which GitHub renders.
