-- A managed database's admin login: may create roles and databases, but is not a superuser
-- and has no BYPASSRLS (AWS calls it the master user).
create role dbmasteruser login password 'ci-master' createrole createdb;
create database hrms owner dbmasteruser;
