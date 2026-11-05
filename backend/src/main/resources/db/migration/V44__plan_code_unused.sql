-- The plan label (essential, growth, ...) was only stored and shown; nothing uses it any more, and the
-- application no longer writes it. Expand first: give the column a default so inserts that leave it out
-- work, and an older build still running (a rollback) keeps working. A later migration drops the column
-- once no older build can run (docs/database.md, expand then contract).
alter table platform.tenant alter column plan_code set default 'essential';
