-- A fingerprint of each bank account (bank code and number, keyed per Organization, as statutory_id.value_hash is for PAN), so the
-- payroll check for "the same account on two people" can look across every pay group and former employee of the Organization without
-- decrypting every account each time. The hash cannot be reversed or compared across Organizations. Rows made before this are
-- filled in by the application shortly after it starts (BankHashes), so the column is nullable.
-- Additive only, so an older build keeps running against it. By hand: drop the index and the column.
set lock_timeout = '5s';

alter table app.bank_account add column account_hash bytea;

-- Small table (a few rows per employee); the index is built in moments, so the lock it takes is short.
-- squawk-ignore require-concurrent-index-creation
create index bank_account_hash on app.bank_account (tenant_id, account_hash) where account_hash is not null;
