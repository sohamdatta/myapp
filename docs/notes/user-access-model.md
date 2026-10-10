# User and Access Model — Super User, Tenants, Org Admins, Users

Full design with diagram, schema and permission matrix: https://claude.ai/artifact/EZsRyKnqKYNWb7okS6Fszx
Dated: 2 Oct 2026 · detailed design added 3 Oct 2026 · This is C2 in `payroll-module-implementation-plan.md` — revised from 30h to **38h**.

Sections 1–5 are the model. Sections 6–20 are the detailed design. The schema in Appendix A and the seed in Appendix B were loaded into PostgreSQL 16 and exercised with 30 access tests (section 18); the TypeScript in section 9 is a sketch and has not been run.

## 1. User structure — the three levels

The module is presented to customers and in the UI as three levels. Google Doc version, with the detailed design and the schema on a second tab: https://docs.google.com/document/d/18HRTSNafJMzWADY90I0-F21xRxYwIX0eNXZDgonKk5g/edit (the earlier summary-only doc is https://docs.google.com/document/d/1ytehv38-GwRdRLU632MfrmLGVknpqHUk-xv7nzTa4Bs/edit)

```
Super Admin                   platform level · creates and manages Organizations
└── Organization (1..n)       one customer account · the `tenant` in the schema
    ├── Org Admin (1..n)      full control of that Organization · the `owner` role
    └── User (0..n)           Employee role + any functional roles assigned
```

| Level | Scope | Created by | Responsible for |
|---|---|---|---|
| Super Admin | Whole platform | Seeded at install; more added by an existing Super Admin | Create, suspend, close Organizations; plans; platform users; approve support access |
| Organization | One customer account | Super Admin | Isolation and billing boundary; all employee, payroll and user data belongs to exactly one |
| Org Admin | One Organization | First invited by Super Admin; others by an existing Org Admin | Users, roles, org structure, policies, settings, billing |
| User | One Organization, limited by role | Org Admin (or HR admin) | Self-service as Employee, plus assigned roles: HR admin, Payroll operator, Payroll approver, Manager, Auditor |

**How the levels map to the rest of this doc**

- Organization = `tenant`. "Organization" is the product and UI word; the schema keeps `tenant` so it is never confused with a legal entity (see Hierarchy below).
- Super Admin = the `super_admin` platform role in the control plane. It sits above Organizations for management only; it has no standing access to data inside them (see Six super-user rules).
- Org Admin = the tenant `owner` role. At least one active Org Admin always exists per Organization (last-owner guard).
- User = any other membership. Org Admin and User are both rows in `membership`; the roles attached are the only difference.

## 2. The core idea: three planes, not one tree

A super user over many organisations is the most dangerous object in a multi-tenant payroll product. Build it as one hierarchy and the super user becomes a row that can read every salary in the system — the exact failure RLS exists to prevent.

- **Control plane** — platform. Tenants, subscriptions, support. Holds **no employee data**. Super users live here.
- **Tenant plane** — one customer's world. Every table `tenant_id` + FORCE RLS.
- **Identity plane** — humans and credentials. The only thing that crosses, and it carries no authority: an identity is a login, not a permission.

Separate DB roles per plane. The super user's session has **no tenant_id at all**, so RLS has nothing to let through. Access happens by issuing an ordinary tenant-scoped token through an auditable gate — the same code path a customer's own session uses.

## 3. Hierarchy — stop using "organisation" in the schema

```
Platform
└── Tenant                    customer account · isolation + billing boundary
    ├── Legal entity (1..n)   statutory employer · PF, ESI, PT registrations
    │   └── Work location     carries state → PT, LWF, min wage, holidays
    ├── Org unit (tree)       departments, cost centres
    └── Membership (1..n)     a person's standing in this tenant, with roles
```

**Tenant ≠ legal entity.** A group customer (common in Indian mid-market) has four companies under one account, each with its own PF code, ESI registration and TAN. Collapse them and that customer cannot buy from you.

**Identity ≠ person ≠ membership.**
- *Identity* — a login. One email, one password. Platform-level.
- *Person* — the human as known to one tenant; carries employment history, survives rehire. Already in the payroll model.
- *Membership* — joins them: this identity, in this tenant, with these roles, in this state.

Buys three things nearly free: a CA can serve six customers from one login; an HR admin who isn't an employee has a membership with no person; a leaver keeps their person record while membership is ended.

## 4. Roles

**Platform**

| Role | Can | Cannot |
|---|---|---|
| `super_admin` | Create, suspend, close tenants; manage platform users; approve impersonation grants | Read employee or salary data without an approved grant |
| `support` | See tenant metadata, health, error logs; request a grant | Change billing, create tenants, approve own grant |
| `billing` | Subscriptions, invoices, usage | Request a grant. No path to tenant data |

Support and Billing are optional at launch; solo, the Super Admin does all three.

**Tenant** — system roles, shared by every tenant (`role.tenant_id is null`). Nothing is copied at provisioning; custom roles (deferred) will be tenant rows cloned from these.

| Code | UI name | Level | Typical holder | Assignable by users |
|---|---|---|---|---|
| `owner` | Org Admin | Org Admin | Founder, HR head | Yes, by an Org Admin |
| `hr_admin` | HR Admin | User | HR executive | Yes |
| `payroll_operator` | Payroll Operator | User | Payroll executive | Yes |
| `payroll_approver` | Payroll Approver | User | Finance head | Yes |
| `manager` | Manager | User | Line manager | Yes |
| `employee` | Employee | User | Everyone on payroll | Yes |
| `auditor` | Auditor | User | The customer's CA | Yes |
| `ex_employee` | Ex-employee | User | Former staff | No — set by offboarding |
| `support_read`, `support_write` | Support | — | Platform staff under a grant | No — support sessions only |

**Key split:** HR admin does *not* get `compensation.read` beyond their own. In a real company the person maintaining employee records is often not cleared to see the CEO's salary.

**Ex-employee is real, not an oversight.** Someone who left in August needs their tax certificate the following June. Delete the account and the customer's HR handles that by email for years.

## 5. Six super-user rules

1. **No ambient access.** Control-plane DB role cannot read tenant tables at all. No "tenant_id = ALL", no bypass flag.
2. **Access is a grant** with a free-text reason and a 60-minute expiry, instantly revocable.
3. **Two people.** Requester cannot approve their own grant. Solo now = auto-approve with a full record, but build the path.
4. **Read-only unless escalated.** Writes are a separate mode, attributed as "by support, on behalf of".
5. **The customer sees it.** Every impersonated session lands in *that tenant's* audit log — who, when, why, how long, what. This converts the capability from a liability into a differentiator (recall one competitor stores HR data across five regions with cross-region access permitted and says nothing about any of this).
6. **Money has no support path.** Impersonation can never approve a run or release a bank file, in any mode. Enforce in the permission check *and* as a DB constraint on the run state transition.

What this protects against isn't malice — it's a support engineer with standing access, a debug script and a bad `WHERE` clause.

---

# Detailed design

## 6. Database roles and schemas

Three Postgres schemas, one per plane, and three login roles. The app opens three connection pools; which pool a piece of code may import is the plane boundary.

| Schema | Holds | Pool / DB role | That role can |
|---|---|---|---|
| `platform` | `tenant`, `platform_user`, `impersonation_grant`, `platform_audit` (and `subscription`, owned by C10) | `app_platform` | Read/write platform tables; `platform_audit` is insert + select only. No `USAGE` on schema `app` |
| `auth` | `identity`, `session`, `password_reset`, `mfa_recovery_code` | `app_auth` | Read/write auth tables; read `platform.tenant`, `platform_user`, `impersonation_grant`. No `USAGE` on schema `app` |
| `app` | `membership`, `role`, `permission`, `role_permission`, `membership_role`, `access_scope`, `invitation` (and every HR/payroll table) | `app_tenant` | Rows of the tenant in `app.tenant_id` only. No `DELETE` on `membership`. No access to schema `auth` |

All three are `NOBYPASSRLS`. A fourth role, `app_definer` (`NOLOGIN BYPASSRLS`), owns the few `SECURITY DEFINER` functions that must cross a plane. These functions are the complete list of crossings:

| Function | Called by | Why it must cross |
|---|---|---|
| `auth.memberships_for_identity(identity)` | `app_auth` at sign-in | List the Organizations a login can enter; returns ids, names, status only |
| `auth.accept_invitation(token_hash, identity)` | `app_auth` | Token lookup is cross-tenant by nature; creates or reactivates the membership and its roles atomically |
| `platform.invite_first_owner(tenant, email, token_hash)` | `app_platform` | The only write the platform can make inside a tenant; refuses once the tenant has any member |
| `platform.tenant_stats(tenant)` | `app_platform` | Counts for the console (members, pending invitations, owners); never rows |
| `app.member_directory()` | `app_tenant` | Emails for the user list, restricted to the current tenant, so the tenant pool never reads `auth.identity` |
| Trigger functions (maker–checker, last-owner guard, scope-version bump) | fired by writes | Must see the true rows whatever tenant context the writer has |

RLS policy on every tenant table: `tenant_id = app.current_tenant()`, where `current_tenant()` is `nullif(current_setting('app.tenant_id', true), '')::uuid`. The `nullif` matters: after a transaction that used `SET LOCAL`, a pooled connection reports the setting as an empty string, not NULL. `app.role` additionally lets every tenant read system rows (`tenant_id is null`). `permission` and `role_permission` are global reference data with select-only grants and go on the CI allowlist of tables without `tenant_id`.

The auth library (per the solo build plan) may own the exact shape of `auth.identity` and `auth.session`; the columns in Appendix A are the ones this design depends on.

## 7. Tables

Full DDL is Appendix A. Rules that are not obvious from the columns:

| Table | Rules |
|---|---|
| `platform.tenant` | `status`: `provisioning → active` on first Org Admin acceptance; `active ↔ suspended`; `→ closed` is final. `slug` is lowercase, 3–40 chars, used as subdomain lookup only |
| `platform.platform_user` | One per identity. The first Super Admin is created by a CLI seed script, never through the UI. Use a separate email from any tenant login |
| `platform.impersonation_grant` | `status`: `requested → approved / rejected`, `approved → revoked`. Expiry is derived (`now() > expires_at`). Check constraint: `approved_by <> platform_user_id` unless `auto_approved` |
| `auth.session` | `kind` is `tenant`, `platform` or `support`; a check constraint ties each kind to which of `tenant_id`, `membership_id`, `impersonation_grant_id` must be set |
| `app.membership` | One per (tenant, identity). `status`: `active`, `suspended`, `ended`. Never deleted. `scope_version` is bumped by trigger on any role, scope or status change |
| `app.membership_role` | Many roles per membership. `granted_by` null means granted by the system (first Org Admin, offboarding) |
| `app.invitation` | Pending invitations live here, not as a membership status. One pending invitation per (tenant, email). Stores only the SHA-256 of the token |
| `app.role_permission` | Carries the `scope` of the grant: `self`, `team`, `assigned` or `tenant` |
| `app.access_scope` | Legal entities, locations or org units a membership is limited to. Exists now; enforcement deferred |

Database-enforced invariants (all tested):

- One membership cannot hold both `payroll_operator` and `payroll_approver` (deferred constraint trigger, so a swap inside one transaction works).
- A tenant always keeps at least one active `owner`: removing the role from, suspending or ending the last one fails at commit.
- Cross-tenant writes fail the RLS `WITH CHECK`.
- `membership` rows cannot be deleted; `platform_audit` rows cannot be updated or deleted.
- An impersonation grant cannot be approved by its requester unless flagged `auto_approved`.

**Requirement on the payroll module:** Org Admin holds both `payroll.prepare` and `payroll.approve`, so the role-level rule alone does not give maker–checker. `payroll_run` needs `check (approved_by <> prepared_by)`, plus the rule-6 constraint that a run cannot move to approved or released under a support session.

## 8. Permission catalogue

Written first, as strings in a seed file (Appendix B). Code checks these, never role codes.

| Code | Allows |
|---|---|
| `employee.read` / `employee.write` | View / create and change employee records |
| `compensation.read` / `compensation.write` | View salary structures, payslips, tax documents / change structures and pay inputs |
| `payroll.read` | View payroll runs, registers, reports |
| `payroll.prepare` | Enter inputs and calculate a run |
| `payroll.approve` | Approve and lock a run |
| `payroll.release` | Release the bank payment file |
| `statutory.file` | Generate statutory output files |
| `user.read` | View the user list and pending invitations |
| `user.invite` | Invite users; resend and revoke invitations |
| `user.manage` | Suspend, reactivate and end memberships |
| `role.assign` | Grant and remove roles (bounded by the grant rule, section 10) |
| `audit.read` | View the audit log |
| `tenant.manage` | Change Organization settings; close the account |
| `billing.manage` | Manage plan, seats, invoices |

Platform permissions are not in this table. Platform roles are three fixed values checked directly in the console (`super_admin`, `support`, `billing`); they are few, never customer-editable, and never mix with tenant permissions.

**Role → permission seed** (T = tenant, A = assigned, M = team, S = self, blank = none)

| Permission | owner | hr_admin | payroll_operator | payroll_approver | manager | employee | auditor | ex_employee | support_read | support_write |
|---|---|---|---|---|---|---|---|---|---|---|
| employee.read | T | T | A | A | M | S | T | S | T | T |
| employee.write | T | T | | | | S | | | | T |
| compensation.read | T | | A | A | | S | T | S | T | T |
| compensation.write | T | | A | | | | | | | T |
| payroll.read | T | | A | A | | | T | | T | T |
| payroll.prepare | T | | A | | | | | | | T |
| payroll.approve | T | | | A | | | | | | |
| payroll.release | T | | | A | | | | | | |
| statutory.file | T | | A | | | | | | | |
| user.read | T | T | | | | | | | T | T |
| user.invite | T | T | | | | | | | | T |
| user.manage | T | T | | | | | | | | T |
| role.assign | T | T | | | | | | | | |
| audit.read | T | A | A | T | | | T | | T | T |
| tenant.manage | T | | | | | | | | | |
| billing.manage | T | | | | | | | | | |

## 9. Scope and the permission check

| Scope | The subject is allowed when |
|---|---|
| `self` | `subject.person_id = membership.person_id` |
| `team` | The subject is below the member's person in the reporting line, direct or indirect, as of today (effective-dated assignment from Core HR) |
| `assigned` | The subject's legal entity, work location or org unit is in the member's `access_scope` rows. With no rows it means the whole tenant, which is the behaviour until enforcement is switched on. For `audit.read` it means events in categories the reader can otherwise read |
| `tenant` | Always |

A member with several roles keeps every (permission, scope) pair; a check passes if any pair covers the subject. So a Payroll Operator who is also a Manager reads their team's records via `team` and their assigned entities via `assigned`.

**Request pipeline** — every server action and route handler, in this order, default deny:

1. Read the access token from the httpOnly cookie; verify signature, expiry and audience.
2. Open a transaction on the pool matching the token kind and call `set_config('app.tenant_id', <claim>, true)` (the parameterised form of `SET LOCAL`). The tenant id comes from the claim and nowhere else.
3. Load the membership row. Reject if `status = 'suspended'` or if the token's `scope_version` differs from the row's (the client then refreshes, which re-reads roles).
4. Resolve effective permissions with `app.effective_permissions(membership_id)`; cache for the request only.
5. Run the guard for the permission the action declares. An action with no declared permission fails the build (lint rule), not the request.
6. For support sessions, check the grant is still approved, unexpired and unrevoked, and refuse `payroll.approve`, `payroll.release`, `role.assign`, `billing.manage` and `tenant.manage` regardless of role rows.
7. Write the audit event in the same transaction as the change.

```ts
type Scope = 'self' | 'team' | 'assigned' | 'tenant';
type Subject = { personId?: string; legalEntityId?: string; orgUnitId?: string };

// The only way to reach app.* tables. Nothing imports the tenant pool directly.
export function withTenant<T>(ctx: SessionCtx, fn: (tx: Tx) => Promise<T>) {
  return tenantPool.transaction(async (tx) => {
    await tx.execute(sql`select set_config('app.tenant_id', ${ctx.tenantId}, true)`);
    return fn(tx);
  });
}

const NEVER_UNDER_SUPPORT = new Set([
  'payroll.approve', 'payroll.release', 'role.assign', 'billing.manage', 'tenant.manage',
]);

export async function requirePermission(tx: Tx, ctx: SessionCtx, permission: string, subject?: Subject) {
  if (ctx.impersonationGrantId && NEVER_UNDER_SUPPORT.has(permission)) throw new Forbidden(permission);
  const grants = ctx.permissions.filter((p) => p.code === permission);   // loaded in step 4
  for (const g of grants) {
    if (g.scope === 'tenant') return;
    if (!subject) continue;                       // list endpoints filter in SQL instead
    if (g.scope === 'self' && subject.personId === ctx.personId) return;
    if (g.scope === 'team' && (await isInReportingLine(tx, ctx.personId, subject.personId))) return;
    if (g.scope === 'assigned' && (await isInAccessScope(tx, ctx.membershipId, subject))) return;
  }
  throw new Forbidden(permission);
}
```

List queries do not loop over subjects: they call `scopeFilter(ctx, permission)` which returns a SQL predicate (`true` for tenant, `person_id = $me` for self, a reporting-line subquery for team, an `access_scope` join for assigned) OR-ed across the member's grants.

## 10. Grant rule

The earlier "an inviter cannot grant a role they do not hold" is too blunt: an Org Admin does not hold `employee`, and an HR Admin must be able to invite employees. The rule, implemented as `app.can_grant(grantor_membership, role)` and applied to invitations and to role edits:

- The grantor holds `role.assign`.
- The role is `assignable` (so never `ex_employee` or the support roles).
- Every permission of the role with scope other than `self` is already held by the grantor at the same scope or at `tenant` scope. Self-scoped permissions are exempt, because anyone may be given access to their own data.

Resulting behaviour (tested): Org Admin can grant every assignable role. HR Admin can grant `employee`, `manager` and `hr_admin`, and cannot grant `payroll_operator`, `payroll_approver`, `auditor` or `owner`. Payroll roles and managers cannot grant anything.

Additional rules in the service: a member cannot change their own roles; removing `owner` from the last Org Admin is refused (the database also refuses it).

## 11. Sessions and tokens

```json
{ "sub": "identity_uuid", "aud": "tenant", "sid": "session_uuid",
  "tenant_id": "tenant_uuid", "membership_id": "...",
  "roles": ["payroll_operator"], "scope_version": 7, "imp": null, "exp": ... }
```

| Token kind (`aud`) | Carries | Used on | Pool |
|---|---|---|---|
| `tenant` | `tenant_id`, `membership_id`, `scope_version` | The product, `<slug>.<domain>` | `app_tenant` |
| `platform` | No tenant id | The console, separate hostname | `app_platform` |
| `support` | `tenant_id`, `imp` = grant id, role `support_read` or `support_write`, no membership | The product, with a permanent banner | `app_tenant` |

- Access token: signed JWT, 15 minutes, httpOnly + Secure + SameSite=Lax cookie. Refresh: opaque random token, stored hashed in `auth.session`, rotated on every use; reuse of an old refresh token revokes the session.
- Proposed lifetimes: tenant session 60 minutes idle, 12 hours absolute; platform session 15 minutes idle, 8 hours absolute; support session ends at the grant's `expires_at`.
- Never accept tenant id from the client — not header, query or body. Subdomain is a lookup, not an authority: if the subdomain's tenant differs from the claim, redirect to the Organization switcher.
- Switching Organization issues a new session and token; there is no mutable "current tenant" on the server.
- `scope_version` bumps on any role, scope or status change, so a revoked approver loses power on their next request, not at token expiry. Suspension and ending also revoke the membership's sessions.
- The `roles` claim is for display only. Authorization always re-reads permissions from the database (pipeline step 4).

## 12. Authentication rules

| Topic | Rule |
|---|---|
| Passwords | Minimum 12 characters, checked against a breached-password list, hashed with argon2id. No composition rules, no forced rotation |
| Sign-in failures | Same response for unknown email and wrong password. Rate limit per IP and per email. After 5 consecutive failures, lock for 15 minutes (`locked_until`); notify the identity by email |
| Password reset | Single-use token, 30 minutes, stored hashed. The request always answers "if that email exists, we sent a link". Completing a reset revokes every session of the identity |
| MFA | TOTP, with 10 single-use recovery codes stored hashed; the secret is encrypted at rest |
| MFA required | Every platform user. Any membership holding `compensation.read` beyond `self` scope, `payroll.approve`, `payroll.release` or `billing.manage`. Checked when the tenant token is issued: if required and not enrolled, the user is sent to enrolment first |
| MFA reset | A member who loses their device and recovery codes is reset by an Org Admin (`user.manage`), which revokes their sessions and forces re-enrolment. An Org Admin with no second Org Admin to reset them is an open decision (section 20) |
| Email change | Verified by a link to the new address; the old address is notified; sessions revoked |

MFA belongs to the identity, not the membership: one enrolment covers every Organization that login can enter.

## 13. Flows

**Create an Organization** (Super Admin, console)

1. Enter name, slug, plan. Validation: slug unique, lowercase, 3–40 characters. Insert `tenant` with status `provisioning`; write `platform_audit`.
2. Enter the first Org Admin's email. `platform.invite_first_owner` inserts an invitation for role `owner` with `invited_by` null; the invitation email is sent.
3. On acceptance the tenant becomes `active`. Until then the console shows "Awaiting Org Admin" and offers Resend.
4. The Super Admin cannot invite anyone else, ever: the function refuses once the tenant has a member.

**Invite a user** (`user.invite`, plus the grant rule for each role)

1. Enter email, pick roles, optionally link an employee record (pre-filled when started from the employee page).
2. Refuse if the email already has an active or suspended membership here, if a pending invitation exists (offer Resend), if any role fails `can_grant`, or if the plan's seat limit is reached.
3. Insert the invitation: 32 random bytes as the token, SHA-256 stored, expiry 7 days. Send the email. Audit `user.invited`.
4. Resend rotates the token and resets the expiry on the same row. Revoke sets `revoked_at`.

**Accept an invitation**

1. Open the link. An invalid, expired, used or revoked token shows one generic message.
2. If the email has no identity: set a password (and enrol MFA if the roles require it); the identity is created with that email. If it has one: sign in as that identity. An identity with a different email cannot accept.
3. `auth.accept_invitation` creates the membership and its roles in one transaction, or reactivates an `ended` membership (rehire) on the same row. A first session is issued.

**Sign in**

1. Email and password, then MFA if enrolled.
2. `auth.memberships_for_identity`: zero Organizations → "no access" page; one → enter it; several → Organization switcher. A platform user signing in on the console hostname gets a platform session instead.
3. If the chosen membership requires MFA and the identity has none, enrol before the token is issued.

**Change roles** (`role.assign`) — lock the membership row, check `can_grant` for each added role, refuse self-edit, apply adds and removes in one transaction, audit `role.granted` / `role.removed`. The trigger bumps `scope_version`.

**Suspend / reactivate** (`user.manage`) — suspend sets status and revokes sessions; refused for the last active Org Admin and for oneself. Reactivate restores `active` with the same roles.

**Offboard** (`user.manage`, or the Core HR job on the last working day) — in one transaction: remove all roles; if the membership has a person, add `ex_employee`; set status `ended`; revoke sessions. A non-employee (no person) ends with no roles and can no longer enter the Organization. The person record and payroll history are untouched.

**Rehire** — invite the same email again; acceptance reactivates the existing membership row with the new roles.

**Suspend or close an Organization** (Super Admin) — suspend: no new tokens are issued, existing sessions are revoked, data is retained, support grants still work. Close: final; sessions revoked; the DPDP retention clock and per-tenant key handling from the solution design take over.

**Support access**

1. A platform user requests a grant: tenant, mode (`read_only` default, or `write`), reason of at least 10 characters. Audit in `platform_audit`.
2. A different Super Admin approves or rejects. If no other active approver exists, the grant is auto-approved and flagged. `expires_at` = approval + 60 minutes.
3. "Start session" creates an `auth.session` of kind `support` and a token with `tenant_id`, `imp` and role `support_read` or `support_write`. The first request writes `support.session_started` to the tenant's audit log with the requester, reason and expiry.
4. Every write under the session is audited as "by support, on behalf of", with the grant id. The UI shows a banner with the countdown.
5. Revoke (by any Super Admin) or expiry ends the session on the next request; `support.session_ended` is written with the list of pages and records viewed.

Erasure under DPDP is a *different* mechanism — per-tenant encryption keys and crypto-shredding plus retention clocks, not row deletion. Keep the two ideas separate.

## 14. Operations and their guards

| Operation | Requires | Extra rules |
|---|---|---|
| List users and invitations | `user.read` | Emails via `app.member_directory()` |
| Invite user | `user.invite` | `can_grant` per role; seat limit; no existing membership |
| Resend / revoke invitation | `user.invite` | Pending only |
| Change roles | `role.assign` | `can_grant` per added role; not self; last-owner guard; maker–checker |
| Suspend / reactivate | `user.manage` | Not self; not the last Org Admin |
| End membership | `user.manage` | Not self; not the last Org Admin |
| Reset a member's MFA | `user.manage` | Not self; revokes sessions |
| Link membership to employee | `user.manage` + `employee.read` | One membership per person |
| View audit log | `audit.read` | Category filter at `assigned` scope |
| Organization settings / close | `tenant.manage` | Close needs password + MFA re-entry |
| Billing | `billing.manage` | — |
| Own profile, password, MFA, sessions | Signed in | Identity-level; no tenant permission |
| Create / suspend / close tenant | `super_admin` | Console only |
| Manage platform users | `super_admin` | Cannot remove the last active Super Admin |
| Request grant | `super_admin` or `support` | Reason required |
| Approve / reject / revoke grant | `super_admin` | Not own request unless sole approver |
| Platform billing | `super_admin` or `billing` | — |

## 15. Screens

| Area | Screen | Contents and actions |
|---|---|---|
| Console | Organizations | Table: name, slug, plan, status, active members, created. Create; filter by status |
| Console | Organization detail | Status and plan; Org Admin count; pending first invitation with Resend; Suspend, Reactivate, Close; grants for this tenant. No user list, no employee data |
| Console | Platform users | Email, role, status, MFA state. Add, change role, suspend |
| Console | Support access | Request form (tenant, mode, reason); queue to approve or reject; active grants with countdown, Start session, Revoke; history |
| Product | Users | Tabs: Active, Pending invitations, Suspended, Former. Columns: name, email, roles, linked employee, MFA, last sign-in. Row actions by permission |
| Product | Invite user | Email, roles (only those the inviter can grant are enabled), link to employee |
| Product | User detail | Roles with who granted and when; edit roles; suspend, reactivate, end, reset MFA; active sessions |
| Product | Roles | Read-only list of roles and the permissions each carries |
| Product | Audit log | Filter by actor, action, date; support sessions shown as their own category with reason and duration |
| Account | Sign in, MFA challenge, Organization switcher | — |
| Account | Accept invitation | Shows the Organization name and roles; set password or sign in; MFA enrolment when required |
| Account | Forgot / reset password | — |
| Account | Profile and security | Change password, MFA and recovery codes, list and revoke own sessions, change email |
| Product | Support banner | Shown on every page of a support session: who, mode, time remaining, End session |

## 16. Audit events

Tenant audit (table owned by C8) needs these columns for this module: `actor_kind` (`member`, `support`, `system`), `actor_membership_id`, `impersonation_grant_id`, `action`, `target_type`, `target_id`, `detail`, `at`.

| Log | Events |
|---|---|
| Tenant | `user.invited`, `invitation.resent`, `invitation.revoked`, `invitation.accepted`, `role.granted`, `role.removed`, `membership.suspended`, `membership.reactivated`, `membership.ended`, `membership.rehired`, `mfa.reset_by_admin`, `tenant.settings_changed`, `support.session_started`, `support.session_ended`, `support.write` |
| Platform | `tenant.created`, `tenant.suspended`, `tenant.reactivated`, `tenant.closed`, `tenant.first_owner_invited`, `platform_user.added`, `platform_user.role_changed`, `platform_user.suspended`, `grant.requested`, `grant.approved`, `grant.auto_approved`, `grant.rejected`, `grant.revoked`, `support.session_started` |
| Identity (security log) | `login.succeeded`, `login.failed`, `login.locked`, `password.changed`, `password.reset_requested`, `password.reset_completed`, `mfa.enrolled`, `mfa.removed`, `session.revoked`, `email.changed` |

## 17. Errors

| Code | HTTP | When |
|---|---|---|
| `auth.invalid_credentials` | 401 | Wrong email or password (same for both) |
| `auth.locked` | 423 | Too many failures |
| `auth.mfa_required` | 401 | MFA challenge or enrolment needed |
| `auth.token_stale` | 401 | `scope_version` mismatch; client refreshes silently |
| `auth.session_revoked` | 401 | Suspended, ended, reset or signed out elsewhere |
| `tenant.unavailable` | 403 | Organization suspended or closed |
| `permission.denied` | 403 | Guard failed; the response names the permission, never the data |
| `grant.not_allowed` | 403 | `can_grant` refused a role |
| `invitation.invalid` | 410 | Expired, used, revoked or unknown token (one message) |
| `invitation.email_mismatch` | 403 | Signed in as a different email |
| `membership.exists` | 409 | Email already a member, or a pending invitation exists |
| `membership.last_owner` | 409 | Would leave the Organization without an Org Admin |
| `role.maker_checker` | 409 | Would combine Payroll Operator and Payroll Approver |
| `seat.limit_reached` | 402 | Plan seats exhausted |
| `support.expired` | 401 | Grant expired or revoked mid-session |

## 18. Tests

**Run on PostgreSQL 16 against Appendix A and B — 30 checks, all behaved as designed:**

- Platform and auth roles get "permission denied" on tenant tables; the tenant role gets it on `auth.identity`.
- The tenant pool with no tenant set, and again after a transaction that set one, sees zero rows.
- A tenant sees only its own members and emails; inserting a row for another tenant violates RLS.
- Invitations: wrong email refused; single use; duplicate pending refused (case-insensitive email); an active member cannot gain a second membership.
- First-owner invitation works once and is refused after the tenant has members; tenant moves `provisioning → active` on acceptance.
- Grant rule outcomes as listed in section 10.
- Maker–checker fails at commit; an operator-to-approver swap in one transaction succeeds; `scope_version` increments.
- The last Org Admin cannot be suspended or lose the role.
- Suspended member: no permissions, not listed at sign-in. Offboarded employee: only `employee.read@self` and `compensation.read@self`, still able to enter. Rehire reuses the membership row.
- One login in two Organizations lists both; a suspended Organization drops out of the list.
- `membership` cannot be deleted; system roles cannot be edited by tenants; `platform_audit` cannot be updated; a grant cannot be self-approved without the auto-approve flag.

**Still to write with the application (not run):**

- Leak suite extension: for every server action, call it as tenant A with tenant B's ids and assert not-found, both directions.
- Lint: every server action declares a permission; no file outside `withTenant` imports the tenant pool.
- Token: stale `scope_version` rejected; refresh-token reuse revokes the session; tenant id in body or header ignored.
- Support session: read-only mode cannot write; `payroll.approve` and `payroll.release` refused in write mode; session dies at expiry and on revoke; events appear in the tenant's audit log.
- Auth: lockout after 5 failures; reset revokes sessions; identical responses for unknown and known emails.

## 19. Build order — 38h

| Step | Hrs |
|---|---|
| Identity, session, password, MFA | 8 |
| Membership, role, permission, role_permission | 8 |
| Permission middleware (default deny) | 4 |
| Invitation flow + last-owner guard | 6 |
| Platform user, impersonation grant, platform audit | 10 |
| Cross-tenant leak tests extended to roles | 2 |

As 19 two-hour tasks for weeknights, in order:

| # | Task | Step |
|---|---|---|
| 1 | Three schemas, four DB roles, three pools; Appendix A auth tables | 1 |
| 2 | Auth library wired: sign-up by invitation only, sign-in, argon2id, lockout | 1 |
| 3 | Access + refresh tokens, rotation, session list and revoke | 1 |
| 4 | TOTP enrolment, challenge, recovery codes; password reset and email change | 1 |
| 5 | Appendix A tenant tables, RLS policies, grants | 2 |
| 6 | Appendix B seed; integrity triggers; port the 30 SQL checks into the test suite | 2 |
| 7 | Sign-in to Organization: `memberships_for_identity`, switcher, MFA-required check | 2 |
| 8 | Users screen (list, tabs) with `member_directory` | 2 |
| 9 | `withTenant`, request pipeline steps 1–4, `scope_version` rejection | 3 |
| 10 | `requirePermission` guard, `scopeFilter`, lint rule for undeclared permissions | 3 |
| 11 | Invite, resend, revoke; invitation email; `can_grant` in the UI | 4 |
| 12 | Accept invitation (new and existing identity), rehire path | 4 |
| 13 | Role edit, suspend, reactivate, end, MFA reset; error mapping | 4 |
| 14 | Console shell on its own hostname; platform sign-in; seed script for first Super Admin | 5 |
| 15 | Organizations list, create, first Org Admin invitation, suspend and close | 5 |
| 16 | Platform users; `platform_audit` writes | 5 |
| 17 | Grant request, approve, reject, revoke, auto-approve | 5 |
| 18 | Support session token, banner, deny list, tenant audit events | 5 |
| 19 | Leak suite extended to memberships, roles and support sessions | 6 |

**Before any of it:** the permission list is already written (Appendix B). Start from it.

## 20. Deferred, changes and open decisions

**Deferred:** access scope enforcement by entity/location/department (table exists day one) · custom roles · customer-approved impersonation · SSO/SCIM · support and billing platform roles if solo.

**Changed from the 2 Oct version, and why**

- Pending invitations are a separate `invitation` table, not a membership status: an invited email may have no identity yet, and `membership.identity_id` is not null. Membership status is now `active`, `suspended`, `ended`.
- System roles are global rows, not copied per tenant at provisioning. Less to seed and migrate; custom roles later are tenant rows.
- Maker–checker is a constraint trigger. The partial unique index in the published page is not valid Postgres (index predicates cannot contain subqueries).
- The grant rule is permission-based (section 10) instead of "must hold the same role".
- MFA is required for `compensation.read` beyond `self` scope; the earlier wording would have forced MFA on every employee.
- Added permission codes `payroll.read`, `user.read`, `user.manage`, `tenant.manage`.
- `tenant_placement` (shard router) is left out, following the solo build plan; add it back if the team plan resumes.

**Open decisions**

- Split Org Admin into Owner and Admin? Needed if the Org Admin is usually an HR person who should not see compensation or billing.
- A company with one finance person: with `approved_by <> prepared_by` on payroll runs, a single Org Admin cannot run payroll alone. Either require two people (recommended, and a selling point to auditors) or add a tenant setting that allows self-approval with an audit flag.
- Recovery for an Organization whose only Org Admin has lost MFA or left: there is deliberately no platform path into a tenant's roles. Options: require two Org Admins at onboarding, or a documented, audited "owner recovery" grant that needs written customer confirmation.
- How long an ex-employee keeps access to their documents. No expiry is assumed.
- Session lifetimes (section 11) and the 60-minute grant length are proposals.

## 21. Six mistakes (each a live-data migration if found in month eight)

1. `tenant_id` on the user row — forecloses multi-org membership permanently.
2. `role` as a column not a table — a payroll executive who is also a manager is ordinary.
3. Checking roles in code — scatters policy; check permissions instead.
4. A super admin with a tenant id meaning "all" — reduces isolation to an integer comparison in app code.
5. Deleting users — orphans audit records you're required to keep.
6. Conflating the human with the login — rehire, email change, contractor-to-employee all break.

## Appendix A — schema (PostgreSQL 16, tested)

```sql
-- ── roles and schemas ───────────────────────────────────────────────────
create role app_platform login nobypassrls;   -- platform console pool
create role app_auth     login nobypassrls;   -- sign-in, sessions, invitations
create role app_tenant   login nobypassrls;   -- everything inside an Organization
create role app_definer  nologin bypassrls;   -- owns the few SECURITY DEFINER functions

create schema platform;   -- control plane: no employee data
create schema auth;       -- identity plane: logins and sessions
create schema app;        -- tenant plane: tenant_id + FORCE RLS on every table
create extension if not exists citext;

-- ── identity plane ──────────────────────────────────────────────────────
create table auth.identity (
  id                 uuid primary key default gen_random_uuid(),
  email              citext not null unique,
  password_hash      text,                          -- argon2id; null until first set
  mfa_secret_enc     bytea,                         -- TOTP secret, encrypted
  mfa_enrolled_at    timestamptz,
  status             text not null default 'active'
                     check (status in ('active','locked','disabled')),
  failed_login_count int  not null default 0,
  locked_until       timestamptz,
  last_login_at      timestamptz,
  created_at         timestamptz not null default now()
);

create table auth.mfa_recovery_code (
  identity_id uuid not null references auth.identity(id),
  code_hash   bytea not null,
  used_at     timestamptz,
  primary key (identity_id, code_hash)
);

create table auth.password_reset (
  id          uuid primary key default gen_random_uuid(),
  identity_id uuid not null references auth.identity(id),
  token_hash  bytea not null unique,
  expires_at  timestamptz not null,                 -- 30 minutes
  used_at     timestamptz
);

-- ── control plane ───────────────────────────────────────────────────────
create table platform.platform_user (
  id          uuid primary key default gen_random_uuid(),
  identity_id uuid not null unique references auth.identity(id),
  role        text not null check (role in ('super_admin','support','billing')),
  status      text not null default 'active' check (status in ('active','suspended')),
  created_by  uuid references platform.platform_user(id),   -- null for the seeded first Super Admin
  created_at  timestamptz not null default now()
);

create table platform.tenant (
  id           uuid primary key default gen_random_uuid(),
  name         text not null,
  slug         text not null unique check (slug ~ '^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$'),
  status       text not null default 'provisioning'
               check (status in ('provisioning','active','suspended','closed')),
  plan_code    text not null,
  created_by   uuid not null references platform.platform_user(id),
  created_at   timestamptz not null default now(),
  suspended_at timestamptz,
  closed_at    timestamptz
);

create table platform.impersonation_grant (
  id               uuid primary key default gen_random_uuid(),
  platform_user_id uuid not null references platform.platform_user(id),   -- requester
  tenant_id        uuid not null references platform.tenant(id),
  reason           text not null check (length(btrim(reason)) >= 10),
  mode             text not null default 'read_only' check (mode in ('read_only','write')),
  status           text not null default 'requested'
                   check (status in ('requested','approved','rejected','revoked')),
  approved_by      uuid references platform.platform_user(id),
  auto_approved    boolean not null default false,   -- only while one approver exists
  requested_at     timestamptz not null default now(),
  granted_at       timestamptz,
  expires_at       timestamptz,                      -- granted_at + 60 minutes by default
  revoked_at       timestamptz,
  check (approved_by is null or approved_by <> platform_user_id or auto_approved),
  check (status <> 'approved' or (approved_by is not null and granted_at is not null and expires_at is not null))
);

create table platform.platform_audit (
  id               bigint generated always as identity primary key,
  platform_user_id uuid references platform.platform_user(id),
  action           text not null,
  target_type      text,
  target_id        uuid,
  detail           jsonb not null default '{}',
  ip               inet,
  at               timestamptz not null default now()
);

create table auth.session (
  id                     uuid primary key default gen_random_uuid(),
  identity_id            uuid not null references auth.identity(id),
  kind                   text not null check (kind in ('tenant','platform','support')),
  tenant_id              uuid references platform.tenant(id),
  membership_id          uuid,
  impersonation_grant_id uuid references platform.impersonation_grant(id),
  refresh_token_hash     bytea not null unique,
  mfa_at                 timestamptz,
  issued_at              timestamptz not null default now(),
  last_seen_at           timestamptz not null default now(),
  expires_at             timestamptz not null,
  revoked_at             timestamptz,
  ip                     inet,
  user_agent             text,
  check (
    (kind = 'platform' and tenant_id is null and membership_id is null and impersonation_grant_id is null) or
    (kind = 'tenant'   and tenant_id is not null and membership_id is not null and impersonation_grant_id is null) or
    (kind = 'support'  and tenant_id is not null and membership_id is null and impersonation_grant_id is not null)
  )
);
create index session_membership_idx on auth.session (membership_id) where revoked_at is null;

-- ── tenant plane ────────────────────────────────────────────────────────
create table app.membership (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  identity_id   uuid not null references auth.identity(id),
  person_id     uuid,                    -- FK to app.person is added with Core HR
  status        text not null default 'active'
                check (status in ('active','suspended','ended')),
  scope_version int  not null default 1,
  invited_by    uuid references app.membership(id),   -- null = invited by the platform
  joined_at     timestamptz not null default now(),
  suspended_at  timestamptz,
  ended_at      timestamptz,
  unique (tenant_id, identity_id),
  unique (tenant_id, id)
);
create unique index membership_person_uq
  on app.membership (tenant_id, person_id) where person_id is not null;

create table app.role (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid references platform.tenant(id),   -- null = system role, shared by all tenants
  code       text not null,
  name       text not null,
  is_system  boolean not null default false,
  assignable boolean not null default true,         -- false for the two support roles
  check (is_system = (tenant_id is null))
);
create unique index role_code_uq
  on app.role (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), code);

create table app.permission (
  code        text primary key,
  description text not null
);

create table app.role_permission (
  role_id         uuid not null references app.role(id),
  permission_code text not null references app.permission(code),
  scope           text not null check (scope in ('self','team','assigned','tenant')),
  primary key (role_id, permission_code)
);

create table app.membership_role (
  tenant_id     uuid not null,
  membership_id uuid not null,
  role_id       uuid not null references app.role(id),
  granted_by    uuid references app.membership(id),  -- null = granted by the system
  granted_at    timestamptz not null default now(),
  primary key (membership_id, role_id),
  foreign key (tenant_id, membership_id) references app.membership (tenant_id, id)
);

create table app.access_scope (                       -- enforcement deferred
  tenant_id     uuid not null,
  membership_id uuid not null,
  scope_type    text not null check (scope_type in ('legal_entity','work_location','org_unit')),
  scope_id      uuid not null,
  primary key (membership_id, scope_type, scope_id),
  foreign key (tenant_id, membership_id) references app.membership (tenant_id, id)
);

create table app.invitation (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  email       citext not null,
  person_id   uuid,
  role_ids    uuid[] not null check (cardinality(role_ids) >= 1),
  token_hash  bytea not null unique,                  -- SHA-256 of a 32-byte random token
  invited_by  uuid references app.membership(id),     -- null = first Org Admin, invited by the platform
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null,                   -- created_at + 7 days
  accepted_at timestamptz,
  revoked_at  timestamptz
);
create unique index invitation_pending_uq
  on app.invitation (tenant_id, email) where accepted_at is null and revoked_at is null;

-- ── row-level security ──────────────────────────────────────────────────
-- current_setting(..., true) is NULL when never set and '' after a previous
-- transaction's SET LOCAL on a pooled connection; both must match nothing.
create function app.current_tenant() returns uuid language sql stable as
$$ select nullif(current_setting('app.tenant_id', true), '')::uuid $$;

do $$
declare t text;
begin
  foreach t in array array['membership','membership_role','access_scope','invitation'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
  end loop;
end $$;

alter table app.role enable row level security;
alter table app.role force row level security;
create policy role_read on app.role for select
  using (tenant_id is null or tenant_id = app.current_tenant());

-- ── integrity triggers (SECURITY DEFINER so they see the truth under RLS) ─
create function app.bump_scope_version() returns trigger
language plpgsql security definer set search_path = app, pg_temp as $$
begin
  if tg_op = 'DELETE' then
    update app.membership set scope_version = scope_version + 1 where id = old.membership_id;
    return old;
  end if;
  update app.membership set scope_version = scope_version + 1 where id = new.membership_id;
  return new;
end $$;

create trigger membership_role_bump after insert or delete on app.membership_role
  for each row execute function app.bump_scope_version();
create trigger access_scope_bump after insert or delete on app.access_scope
  for each row execute function app.bump_scope_version();

create function app.bump_on_status_change() returns trigger
language plpgsql as $$
begin
  if new.status is distinct from old.status then
    new.scope_version := old.scope_version + 1;
  end if;
  return new;
end $$;
create trigger membership_status_bump before update on app.membership
  for each row execute function app.bump_on_status_change();

-- maker-checker: one membership cannot hold both payroll roles
create function app.assert_not_operator_and_approver() returns trigger
language plpgsql security definer set search_path = app, pg_temp as $$
begin
  if (select count(*) from app.membership_role mr
        join app.role r on r.id = mr.role_id
       where mr.membership_id = new.membership_id
         and r.code in ('payroll_operator','payroll_approver')) > 1 then
    raise exception 'membership % cannot hold both payroll_operator and payroll_approver',
      new.membership_id using errcode = '23514';
  end if;
  return new;
end $$;
create constraint trigger membership_role_maker_checker
  after insert on app.membership_role
  deferrable initially deferred
  for each row execute function app.assert_not_operator_and_approver();

-- an Organization that has had an Org Admin always keeps at least one active one
create function app.assert_tenant_has_owner() returns trigger
language plpgsql security definer set search_path = app, pg_temp as $$
declare t uuid;
begin
  if tg_op = 'DELETE' then t := old.tenant_id; else t := new.tenant_id; end if;
  if not exists (
    select 1 from app.membership m
      join app.membership_role mr on mr.membership_id = m.id
      join app.role r on r.id = mr.role_id
     where m.tenant_id = t and m.status = 'active' and r.code = 'owner') then
    raise exception 'tenant % must keep at least one active owner', t using errcode = '23514';
  end if;
  return null;
end $$;
create constraint trigger last_owner_guard_role
  after delete on app.membership_role
  deferrable initially deferred
  for each row execute function app.assert_tenant_has_owner();
create constraint trigger last_owner_guard_status
  after update of status on app.membership
  deferrable initially deferred
  for each row execute function app.assert_tenant_has_owner();

-- ── functions that cross a plane boundary ───────────────────────────────
-- sign-in: which Organizations can this identity enter?
create function auth.memberships_for_identity(p_identity uuid)
returns table (tenant_id uuid, tenant_name text, tenant_slug text, membership_id uuid, status text)
language sql stable security definer set search_path = app, platform, pg_temp as $$
  select t.id, t.name, t.slug, m.id, m.status
    from app.membership m
    join platform.tenant t on t.id = m.tenant_id
   where m.identity_id = p_identity
     and m.status in ('active','ended')
     and t.status = 'active'
     and exists (select 1 from app.membership_role mr where mr.membership_id = m.id)
$$;

-- accept an invitation: creates (or reactivates) the membership and its roles
create function auth.accept_invitation(p_token_hash bytea, p_identity uuid)
returns table (tenant_id uuid, membership_id uuid)
language plpgsql security definer set search_path = app, platform, auth, pg_temp as $$
declare
  inv app.invitation;
  mid uuid;
begin
  select * into inv from app.invitation i
   where i.token_hash = p_token_hash
     and i.accepted_at is null and i.revoked_at is null and i.expires_at > now()
   for update;
  if not found then
    raise exception 'invitation is invalid, expired or already used' using errcode = 'P0001';
  end if;
  if not exists (select 1 from auth.identity i where i.id = p_identity and i.email = inv.email and i.status = 'active') then
    raise exception 'invitation was issued to a different email' using errcode = 'P0001';
  end if;
  if not exists (select 1 from platform.tenant t where t.id = inv.tenant_id and t.status in ('provisioning','active')) then
    raise exception 'organization is not open' using errcode = 'P0001';
  end if;

  insert into app.membership (tenant_id, identity_id, person_id, invited_by)
  values (inv.tenant_id, p_identity, inv.person_id, inv.invited_by)
  on conflict on constraint membership_tenant_id_identity_id_key do update
     set status = 'active', ended_at = null, joined_at = now(),
         person_id = coalesce(excluded.person_id, app.membership.person_id)
   where app.membership.status = 'ended'
  returning id into mid;
  if mid is null then
    raise exception 'this login already has a membership in the organization' using errcode = 'P0001';
  end if;

  delete from app.membership_role mr where mr.membership_id = mid;      -- rehire: drop ex_employee
  insert into app.membership_role (tenant_id, membership_id, role_id, granted_by)
  select inv.tenant_id, mid, r, inv.invited_by from unnest(inv.role_ids) r;

  update app.invitation set accepted_at = now() where id = inv.id;
  update platform.tenant set status = 'active' where id = inv.tenant_id and status = 'provisioning';
  return query select inv.tenant_id, mid;
end $$;

-- platform console: invite the first Org Admin of a new Organization
create function platform.invite_first_owner(p_tenant uuid, p_email citext, p_token_hash bytea)
returns uuid
language plpgsql security definer set search_path = app, platform, pg_temp as $$
declare inv uuid;
begin
  if exists (select 1 from app.membership m where m.tenant_id = p_tenant) then
    raise exception 'organization already has members; its Org Admins manage invitations' using errcode = 'P0001';
  end if;
  insert into app.invitation (tenant_id, email, role_ids, token_hash, expires_at)
  select p_tenant, p_email, array[r.id], p_token_hash, now() + interval '7 days'
    from app.role r where r.code = 'owner' and r.tenant_id is null
  returning id into inv;
  return inv;
end $$;

-- platform console: counts only, never rows
create function platform.tenant_stats(p_tenant uuid)
returns table (active_members bigint, pending_invitations bigint, owners bigint)
language sql stable security definer set search_path = app, pg_temp as $$
  select
    (select count(*) from app.membership m where m.tenant_id = p_tenant and m.status = 'active'),
    (select count(*) from app.invitation i where i.tenant_id = p_tenant and i.accepted_at is null and i.revoked_at is null),
    (select count(*) from app.membership m join app.membership_role mr on mr.membership_id = m.id
       join app.role r on r.id = mr.role_id
      where m.tenant_id = p_tenant and m.status = 'active' and r.code = 'owner')
$$;

-- user list: emails of this Organization's members only
create function app.member_directory()
returns table (membership_id uuid, email citext, mfa_enrolled boolean, last_login_at timestamptz)
language sql stable security definer set search_path = app, auth, pg_temp as $$
  select m.id, i.email, i.mfa_enrolled_at is not null, i.last_login_at
    from app.membership m join auth.identity i on i.id = m.identity_id
   where m.tenant_id = app.current_tenant()
$$;

-- effective permissions of a membership (suspended memberships have none)
create function app.effective_permissions(p_membership uuid)
returns table (permission_code text, scope text)
language sql stable as $$
  select distinct rp.permission_code, rp.scope
    from app.membership m
    join app.membership_role mr on mr.membership_id = m.id
    join app.role_permission rp on rp.role_id = mr.role_id
   where m.id = p_membership and m.status in ('active','ended')
$$;

-- may this member grant this role? Every non-self permission of the role must
-- already be held by the grantor at the same scope or at tenant scope.
create function app.can_grant(p_grantor uuid, p_role uuid) returns boolean
language sql stable as $$
  select exists (select 1 from app.effective_permissions(p_grantor) g where g.permission_code = 'role.assign')
     and exists (select 1 from app.role r where r.id = p_role and r.assignable)
     and not exists (
       select 1 from app.role_permission rp
        where rp.role_id = p_role and rp.scope <> 'self'
          and not exists (
            select 1 from app.effective_permissions(p_grantor) g
             where g.permission_code = rp.permission_code
               and (g.scope = 'tenant' or g.scope = rp.scope)))
$$;

-- ── grants ──────────────────────────────────────────────────────────────
alter function app.bump_scope_version()               owner to app_definer;
alter function app.assert_not_operator_and_approver() owner to app_definer;
alter function app.assert_tenant_has_owner()          owner to app_definer;
alter function auth.memberships_for_identity(uuid)    owner to app_definer;
alter function auth.accept_invitation(bytea, uuid)    owner to app_definer;
alter function platform.invite_first_owner(uuid, citext, bytea) owner to app_definer;
alter function platform.tenant_stats(uuid)            owner to app_definer;
alter function app.member_directory()                 owner to app_definer;

grant usage on schema app, auth, platform to app_definer;
grant select, insert, update, delete on all tables in schema app to app_definer;
grant select on auth.identity to app_definer;
grant select, update on platform.tenant to app_definer;

revoke execute on all functions in schema app, auth, platform from public;

grant usage on schema platform to app_platform;
grant select, insert, update on platform.platform_user, platform.tenant, platform.impersonation_grant to app_platform;
grant select, insert on platform.platform_audit to app_platform;          -- append only
grant usage on schema auth to app_platform;
grant select (id, email, status) on auth.identity to app_platform;
grant execute on function platform.invite_first_owner(uuid, citext, bytea), platform.tenant_stats(uuid) to app_platform;

grant usage on schema auth, platform to app_auth;
grant select, insert, update on all tables in schema auth to app_auth;
grant select on platform.tenant, platform.platform_user, platform.impersonation_grant to app_auth;
grant execute on function auth.memberships_for_identity(uuid), auth.accept_invitation(bytea, uuid) to app_auth;

grant usage on schema app to app_tenant;
grant select, insert, update on app.membership, app.invitation to app_tenant;   -- no delete
grant select, insert, delete on app.membership_role, app.access_scope to app_tenant;
grant select on app.role, app.permission, app.role_permission to app_tenant;     -- custom roles deferred
grant execute on function app.current_tenant(), app.member_directory(),
  app.effective_permissions(uuid), app.can_grant(uuid, uuid), app.bump_on_status_change() to app_tenant;
grant execute on function app.current_tenant() to app_definer, app_auth, app_platform;
```

## Appendix B — seed: permissions, roles, role permissions (tested)

```sql
insert into app.permission (code, description) values
  ('employee.read',      'View employee records'),
  ('employee.write',     'Create and change employee records'),
  ('compensation.read',  'View salary structures, payslips and tax documents'),
  ('compensation.write', 'Change salary structures and pay inputs'),
  ('payroll.read',       'View payroll runs, registers and reports'),
  ('payroll.prepare',    'Enter inputs and calculate a payroll run'),
  ('payroll.approve',    'Approve and lock a payroll run'),
  ('payroll.release',    'Release the bank payment file'),
  ('statutory.file',     'Generate statutory output files'),
  ('user.read',          'View the user list and pending invitations'),
  ('user.invite',        'Invite users; resend and revoke invitations'),
  ('user.manage',        'Suspend, reactivate and end memberships'),
  ('role.assign',        'Grant and remove roles'),
  ('audit.read',         'View the audit log'),
  ('tenant.manage',      'Change Organization settings; close the account'),
  ('billing.manage',     'Manage plan, seats and invoices');

insert into app.role (code, name, is_system, assignable) values
  ('owner',            'Org Admin',        true, true),
  ('hr_admin',         'HR Admin',         true, true),
  ('payroll_operator', 'Payroll Operator', true, true),
  ('payroll_approver', 'Payroll Approver', true, true),
  ('manager',          'Manager',          true, true),
  ('employee',         'Employee',         true, true),
  ('auditor',          'Auditor',          true, true),
  ('ex_employee',      'Ex-employee',      true, false),
  ('support_read',     'Support (read)',   true, false),
  ('support_write',    'Support (write)',  true, false);

insert into app.role_permission (role_id, permission_code, scope)
select r.id, p.code, 'tenant' from app.role r cross join app.permission p where r.code = 'owner';

insert into app.role_permission (role_id, permission_code, scope)
select r.id, v.perm, v.scope
from (values
  ('hr_admin','employee.read','tenant'), ('hr_admin','employee.write','tenant'),
  ('hr_admin','user.read','tenant'), ('hr_admin','user.invite','tenant'),
  ('hr_admin','user.manage','tenant'), ('hr_admin','role.assign','tenant'),
  ('hr_admin','audit.read','assigned'),

  ('payroll_operator','employee.read','assigned'), ('payroll_operator','compensation.read','assigned'),
  ('payroll_operator','compensation.write','assigned'), ('payroll_operator','payroll.read','assigned'),
  ('payroll_operator','payroll.prepare','assigned'), ('payroll_operator','statutory.file','assigned'),
  ('payroll_operator','audit.read','assigned'),

  ('payroll_approver','employee.read','assigned'), ('payroll_approver','compensation.read','assigned'),
  ('payroll_approver','payroll.read','assigned'), ('payroll_approver','payroll.approve','assigned'),
  ('payroll_approver','payroll.release','assigned'), ('payroll_approver','audit.read','tenant'),

  ('manager','employee.read','team'),

  ('employee','employee.read','self'), ('employee','employee.write','self'),
  ('employee','compensation.read','self'),

  ('auditor','employee.read','tenant'), ('auditor','compensation.read','tenant'),
  ('auditor','payroll.read','tenant'), ('auditor','audit.read','tenant'),

  ('ex_employee','employee.read','self'), ('ex_employee','compensation.read','self'),

  ('support_read','employee.read','tenant'), ('support_read','compensation.read','tenant'),
  ('support_read','payroll.read','tenant'), ('support_read','user.read','tenant'),
  ('support_read','audit.read','tenant'),

  ('support_write','employee.read','tenant'), ('support_write','compensation.read','tenant'),
  ('support_write','payroll.read','tenant'), ('support_write','user.read','tenant'),
  ('support_write','audit.read','tenant'), ('support_write','employee.write','tenant'),
  ('support_write','compensation.write','tenant'), ('support_write','payroll.prepare','tenant'),
  ('support_write','user.invite','tenant'), ('support_write','user.manage','tenant')
) as v(role, perm, scope)
join app.role r on r.code = v.role;
```
