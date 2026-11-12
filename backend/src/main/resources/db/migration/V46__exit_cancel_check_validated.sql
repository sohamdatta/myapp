set lock_timeout = '5s';

-- Validates the exit_case_check that V45 added NOT VALID, in a transaction of its own so the validation
-- does not run while V45's lock on app.exit_case is held. Every existing row passes: the check only allows
-- more than the one it replaced.
--
-- Not breaking. Nothing to undo.

alter table app.exit_case validate constraint exit_case_check;
