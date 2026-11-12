set lock_timeout = '5s';

-- An accepted exit could not be cancelled. The check tying "accepted or closed" to a fixed last day and
-- acceptance time let a withdrawn exit keep them, but not a cancelled one, so cancelling any exit HR had
-- started (they are accepted at once) failed with "One of the values is outside the allowed range". A
-- cancelled exit now keeps its last day and acceptance time too, as a record of what was agreed.
--
-- Not breaking: the check only allows more. To undo by hand (only while no accepted exit has been
-- cancelled): drop exit_case_check and add it again with `or status in ('withdrawn')`.
--
-- The new check is added NOT VALID here and validated by V46, in its own transaction.

-- squawk-ignore ban-drop-constraint
-- Replaced by a wider check in the next statement: every row the old check allowed, the new one allows too.
alter table app.exit_case drop constraint exit_case_check;
alter table app.exit_case add constraint exit_case_check
  check ((status in ('accepted','closed')) = (last_working_day is not null and accepted_at is not null)
         or status in ('withdrawn','cancelled')) not valid;
