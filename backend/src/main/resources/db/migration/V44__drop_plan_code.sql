-- The plan label (essential, growth, ...) was only stored and shown; nothing used it. What an Organization
-- has is decided by its modules and its employee licences.
alter table platform.tenant drop column plan_code;
