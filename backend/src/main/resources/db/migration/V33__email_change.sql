-- Changing the address a login signs in with. The link goes to the NEW address (so it is shown to be one the person reads);
-- once it is used the login's address changes and a link to undo it goes to the OLD address, good for seven days.
set lock_timeout = '5s';

create table auth.email_change (
  id           uuid primary key default gen_random_uuid(),
  identity_id  uuid not null references auth.identity(id),
  old_email    citext not null,
  new_email    citext not null,
  confirm_hash bytea not null unique,
  requested_at timestamptz not null default now(),
  expires_at   timestamptz not null,
  cancelled_at timestamptz,
  confirmed_at timestamptz,
  undo_hash    bytea unique,
  undo_until   timestamptz,
  undone_at    timestamptz
);

create index email_change_identity on auth.email_change (identity_id);

grant select, insert, update, delete on auth.email_change to app_auth;
