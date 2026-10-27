-- Step-up: the time a session last showed a fresh code, for the few actions that ask for one again
-- (approving a pay run, the bank file, showing a full identifier, changing roles).
set lock_timeout = '5s';

alter table auth.session add column step_up_at timestamptz;
