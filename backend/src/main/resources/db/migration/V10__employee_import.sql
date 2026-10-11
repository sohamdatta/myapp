-- Bulk import of existing employees: the column mapping each Organization used last, so the next
-- file from the same spreadsheet needs no mapping.
create table app.import_mapping (
  tenant_id  uuid not null references platform.tenant(id),
  kind       text not null check (kind in ('employees')),
  mapping    jsonb not null check (jsonb_typeof(mapping) = 'object'),
  updated_by uuid references app.membership(id),
  updated_at timestamptz not null default now(),
  primary key (tenant_id, kind)
);
alter table app.import_mapping enable row level security;
alter table app.import_mapping force row level security;
create policy tenant_isolation on app.import_mapping using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant());
grant select, insert, update on app.import_mapping to app_tenant;
grant select, insert, update, delete on app.import_mapping to app_definer;

-- Employees imported from a file are marked, so the bulk invitation can list who has not been invited.
alter table app.employment add column imported_at timestamptz;
