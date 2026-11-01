-- How an Organization's Form 130 Part B tax certificate looks: an optional header line, footer text, a signatory block and whether
-- its logo is shown. Each save is a new numbered version and versions are never changed or deleted, so a certificate can say
-- which layout it was built with and a released certificate never changes when the layout does.
-- The figures and the statutory wording are not part of the layout and cannot be changed from it.
-- Additive only (one table and one nullable column), so an older build keeps running against it.
-- By hand: drop the table and the column.
set lock_timeout = '5s';

create table app.certificate_layout (
  tenant_id             uuid not null references platform.tenant(id),
  version               integer not null check (version >= 1),
  header_line           text check (header_line is null or length(header_line) between 1 and 120),
  footer_text           text check (footer_text is null or length(footer_text) between 1 and 300),
  signatory_name        text check (signatory_name is null or length(signatory_name) between 1 and 80),
  signatory_designation text check (signatory_designation is null or length(signatory_designation) between 1 and 80),
  show_logo             boolean not null default true,
  created_by            uuid references app.membership(id),
  created_at            timestamptz not null default now(),
  primary key (tenant_id, version)
);

create function app.certificate_layout_append_only() returns trigger language plpgsql as $$
begin
  raise exception 'A certificate layout version is kept as it was saved; save a new version' using errcode = 'check_violation';
end $$;
create trigger certificate_layout_append_only before update or delete on app.certificate_layout
  for each row execute function app.certificate_layout_append_only();

alter table app.certificate_layout enable row level security;
alter table app.certificate_layout force row level security;
create policy tenant_isolation on app.certificate_layout
  using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant());
grant select, insert on app.certificate_layout to app_tenant;
grant select, insert, update, delete on app.certificate_layout to app_definer;

-- The layout a certificate was built with (null: built before layouts, or none was set).
alter table app.tax_certificate add column layout_version integer;
alter table app.tax_certificate add constraint tax_certificate_layout_fk foreign key (tenant_id, layout_version)
  references app.certificate_layout (tenant_id, version) not valid;
-- Left NOT VALID on purpose: validating in the same transaction blocks reads, and every existing row has no layout version, so there is nothing to check.
