-- Errors the browser caught (a page that crashed while drawing), reported by the app so we hear of them
-- before a customer does. Nothing about who: no user, no Organization, no stack, no address. A page id,
-- the part of the screen, a short message with ids and email addresses blanked out, and the build.
-- Counts of these feed the usage and health page (U1).
--
-- Breaking: no. It only adds a table.
-- To undo by hand: drop table auth.client_error.

set lock_timeout = '5s';

create table auth.client_error (
  id           bigint generated always as identity primary key,
  occurred_at  timestamptz not null default now(),
  page         text not null check (page ~ '^[a-z0-9_-]{1,40}$'),
  region       text not null check (region ~ '^[a-z0-9_-]{1,40}$'),
  message      text not null check (char_length(message) <= 300),
  build        text check (build is null or char_length(build) <= 40)
);

create index client_error_occurred_at on auth.client_error (occurred_at);

-- Sign-out and sign-in pages report too, so the endpoint writes as app_auth; it can also clear old rows.
grant select, insert, delete on auth.client_error to app_auth;
