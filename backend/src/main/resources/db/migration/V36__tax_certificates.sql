-- Form 130 Part B (release R3, last part): the employee's salary computation for a tax year, made from the year's approved
-- payslips and their last tax projection, then released by a second person to the employee's My pay. The figures are
-- frozen with the certificate (part_b) and the PDF is stored encrypted like every other generated document.
-- Part A (from TRACES) is not in this version: the certificate is Part B only until Part A can be read and matched.
-- Additive only (one new table and its guards), so an older build keeps running against it. By hand: drop the table.
set lock_timeout = '5s';

create table app.tax_certificate (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  employment_id uuid not null,
  membership_id uuid references app.membership(id),                  -- who may open it once released; null for someone never given a login
  tax_year      text not null check (tax_year ~ '^[0-9]{4}-[0-9]{2}$'),
  status        text not null default 'draft' check (status in ('draft', 'released')),
  part_b        jsonb not null,                                       -- the figures, as built
  draft_rules   boolean not null,                                     -- the tax rules behind them were not yet signed off
  file_name     text not null,
  pdf_enc       bytea not null,
  generated_by  uuid references app.membership(id),
  generated_at  timestamptz not null default now(),
  released_by   uuid references app.membership(id),
  released_at   timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, employment_id, tax_year),
  foreign key (tenant_id, employment_id) references app.employment (tenant_id, id),
  constraint tax_certificate_maker_checker check (released_by is null or released_by is distinct from generated_by),
  check ((status = 'released') = (released_by is not null and released_at is not null))
);
create index tax_certificate_member on app.tax_certificate (membership_id, tax_year desc) where status = 'released';

-- A draft can be rebuilt (its figures, file and maker change); once released nothing changes, and a certificate is never deleted.
create function app.tax_certificate_guard() returns trigger language plpgsql as $$
begin
  if old.status = 'released' then
    raise exception 'A released tax certificate cannot be changed' using errcode = 'check_violation';
  end if;
  if (new.tenant_id, new.employment_id, new.tax_year) is distinct from (old.tenant_id, old.employment_id, old.tax_year) then
    raise exception 'A tax certificate stays with its employee and tax year' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger tax_certificate_guard before update on app.tax_certificate
  for each row execute function app.tax_certificate_guard();
create function app.tax_certificate_no_delete() returns trigger language plpgsql as $$
begin
  raise exception 'A tax certificate, once built, is kept' using errcode = 'check_violation';
end $$;
create trigger tax_certificate_no_delete before delete on app.tax_certificate
  for each row execute function app.tax_certificate_no_delete();

alter table app.tax_certificate enable row level security;
alter table app.tax_certificate force row level security;
create policy tenant_isolation on app.tax_certificate
  using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant());
grant select, insert, update on app.tax_certificate to app_tenant;
grant select, insert, update, delete on app.tax_certificate to app_definer;
