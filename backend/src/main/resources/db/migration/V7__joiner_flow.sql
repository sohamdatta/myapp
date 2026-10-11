-- The joiner flow: what HR decides about a joiner's attendance before they have a login,
-- applied as an attendance assignment on activation; and two details the default form asks for.

alter table app.onboarding
  add column work_location_id uuid,
  add column shift_id         uuid,
  add column weekly_offs      smallint[] not null default '{0}' check (weekly_offs <@ array[0,1,2,3,4,5,6]::smallint[]),
  add column previous_income  numeric(12,2) check (previous_income >= 0),   -- salary from a previous employer this tax year
  add foreign key (tenant_id, work_location_id) references app.work_location (tenant_id, id),
  add foreign key (tenant_id, shift_id) references app.shift (tenant_id, id);

alter table app.employment add column official_email citext;
