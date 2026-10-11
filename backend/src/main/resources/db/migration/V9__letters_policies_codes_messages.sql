-- Letters, policies, employee codes and in-app messages in the joiner flow.

-- How long a joiner has to answer an offer, by default.
alter table app.onboarding_settings
  add column offer_expiry_days smallint not null default 7 check (offer_expiry_days between 1 and 60);

-- When the offer was accepted (the trigger for tasks that wait for it), and when the joiner and HR
-- were last reminded or told the joiner is ready, so nobody is told twice.
alter table app.onboarding
  add column offer_accepted_at timestamptz,
  add column reminded_at       timestamptz,
  add column ready_notified_at timestamptz;

-- An issued letter belongs to an onboarding when issued during one, and keeps its own title and kind,
-- so an uploaded letter (no template) still says what it is.
alter table app.issued_letter
  add column onboarding_id uuid,
  add column kind  text not null default 'other' check (kind in ('offer','appointment','confirmation','other')),
  add column title text not null default 'Letter' check (length(btrim(title)) between 1 and 80),
  add column response_needed text not null default 'accept' check (response_needed in ('none','accept','accept_or_decline')),
  add foreign key (tenant_id, onboarding_id) references app.onboarding (tenant_id, id);
create index issued_letter_person on app.issued_letter (person_id, issued_at desc);

-- The Organization's name, for letters and messages written inside the Organization's own plane.
create function app.organization_name() returns text
language sql stable security definer set search_path = app, platform, pg_temp as $$
  select t.name from platform.tenant t where t.id = app.current_tenant()
$$;
alter function app.organization_name() owner to app_definer;
grant execute on function app.organization_name() to app_tenant;
