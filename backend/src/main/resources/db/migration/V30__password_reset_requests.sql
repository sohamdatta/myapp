-- "I forgot my password": the request only records the address and answers the same for everyone. A worker later decides,
-- out of the caller's sight, whether an account exists and sends the link, so the answer (and how long it takes) cannot
-- tell a real address from a made-up one.
set lock_timeout = '5s';

create table auth.reset_request (
  id           bigint generated always as identity primary key,
  email        citext not null,
  requested_at timestamptz not null default now(),
  handled_at   timestamptz
);

create index reset_request_waiting on auth.reset_request (id) where handled_at is null;

grant select, insert, update, delete on auth.reset_request to app_auth;
