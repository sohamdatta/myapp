-- Branding: an Organization's own logo, shown in the app in place of the product's (Nexvance).
-- The logo is checked and drawn again as a PNG on upload; only an Org Admin changes it.
--
-- Breaking: no. It only adds tables, columns, permissions and allowed values.
-- To undo by hand: drop app.organization_branding.

set lock_timeout = '5s';

create table app.organization_branding (
  tenant_id     uuid primary key references platform.tenant(id),
  logo          bytea not null,
  logo_type     text not null check (logo_type = 'image/png'),
  logo_width    int not null check (logo_width between 1 and 1200),
  logo_height   int not null check (logo_height between 1 and 1200),
  version       int not null default 1 check (version >= 1),   -- changes with each logo, so browsers fetch the new one
  updated_by    uuid references app.membership(id),
  updated_at    timestamptz not null default now()
);

alter table app.organization_branding enable row level security;
alter table app.organization_branding force row level security;
create policy tenant_isolation on app.organization_branding
  using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant());
grant select, insert, update, delete on app.organization_branding to app_tenant;
grant select, insert, update, delete on app.organization_branding to app_definer;
