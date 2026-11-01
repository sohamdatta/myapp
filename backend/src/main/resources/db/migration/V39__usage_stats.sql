-- Usage statistics (release U1): how each Organization uses each feature, as daily counts only, so the platform team can see a
-- stalling pilot early and order later work by real use. A nightly job counts inside each Organization (under that
-- Organization's own row-level security, or by its tenant id from the sign-in and mail records) and writes only numbers here:
-- no names, amounts, identifiers or content leave the Organization. The tables below have no column that could hold any.
--
-- The platform console's database role reads and writes these two tables and, as before, nothing in any Organization's own tables.
-- Additive only (two new tables), so an older build keeps running against it. By hand: drop the two tables.
set lock_timeout = '5s';

-- What is counted. Rows are written by the application from its list of metrics, so a new metric is code, not a migration.
create table platform.usage_metric (
  code        text primary key check (code ~ '^[a-z][a-z0-9_]{2,59}$'),
  module      text not null check (module in ('core', 'attendance', 'leave', 'payroll')),
  area        text not null check (length(btrim(area)) between 1 and 40),
  description text not null check (length(btrim(description)) between 1 and 200),
  kind        text not null check (kind in ('count', 'level', 'median', 'share')),   -- how days add up into a week
  is_use      boolean not null                                                       -- whether it shows the module being used
);

-- One number per Organization, day and metric.
create table platform.usage_daily (
  tenant_id   uuid not null references platform.tenant(id),
  day         date not null,
  metric      text not null references platform.usage_metric(code),
  value       numeric(14, 2) not null check (value >= 0),
  computed_at timestamptz not null default now(),
  primary key (tenant_id, day, metric)
);
create index usage_daily_by_metric on platform.usage_daily (metric, day);

grant select, insert, update on platform.usage_metric to app_platform;
grant select, insert, update, delete on platform.usage_daily to app_platform;
