-- V50: payout of verified expense claims by bank file (docs/expenses/design.md, stage X3, first part).
--   * app.expense_batch: finance gathers verified claims into a batch, downloads the bank file, and marks the batch paid
--     with the bank's reference. Claims in an open batch are 'scheduled'; paying the batch makes them 'paid'.
--   * app.expense_payment: one row per claim in a batch, so a claim can only be in one live batch.
-- Additive only. By hand: drop the three tables.
set lock_timeout = '5s';

create table app.expense_batch_counter (
  tenant_id uuid not null references platform.tenant(id),
  year      int  not null,
  last      int  not null,
  primary key (tenant_id, year)
);

create table app.expense_batch (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  number         text not null,
  status         text not null default 'open' check (status in ('open','paid','cancelled')),
  total          numeric(12,2) not null check (total > 0),
  created_by     uuid not null references app.membership(id),
  created_at     timestamptz not null default now(),
  paid_on        date,
  paid_by        uuid references app.membership(id),
  bank_reference text,
  cancelled_by   uuid references app.membership(id),
  cancelled_at   timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, number),
  check (status <> 'paid' or (paid_on is not null and paid_by is not null and bank_reference is not null))
);

create table app.expense_payment (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid not null references platform.tenant(id),
  claim_id   uuid not null,
  batch_id   uuid not null,
  amount     numeric(12,2) not null check (amount > 0),
  status     text not null default 'scheduled' check (status in ('scheduled','paid','cancelled')),
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, claim_id) references app.expense_claim (tenant_id, id),
  foreign key (tenant_id, batch_id) references app.expense_batch (tenant_id, id)
);
-- A claim is in at most one live batch.
create unique index expense_payment_live on app.expense_payment (claim_id) where status in ('scheduled','paid');
create index expense_payment_batch on app.expense_payment (batch_id);

do $$
declare t text;
begin
  foreach t in array array['expense_batch_counter','expense_batch','expense_payment'] loop
    execute format('alter table app.%I enable row level security', t);
    execute format('alter table app.%I force row level security', t);
    execute format('create policy tenant_isolation on app.%I using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant())', t);
    execute format('grant select, insert, update on app.%I to app_tenant', t);
    execute format('grant select, insert, update, delete on app.%I to app_definer', t);
  end loop;
end $$;
