-- V62: a two-step challenge remembers how the person got that far (password, or an account at Google or Microsoft), so the
-- session made once the code is right records the same method.
set lock_timeout = '5s';

alter table auth.mfa_challenge
  add column auth_method text not null default 'password' check (auth_method in ('password','google','microsoft')),
  add column provider_tenant text;
