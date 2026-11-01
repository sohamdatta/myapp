-- The EPF wage ceiling rose from Rs 15,000 to Rs 25,000 a month on 17 Sep 2026 (Gazette S.O. 5109(E) under the Code on
-- Social Security, 2020; Ministry of Labour and PIB releases of 16 to 23 Sep 2026). The figures are rule data, so this is a
-- new rule version, not a code change. The old version is closed, never edited, so wage months before September keep
-- the figures they were calculated with.
--
-- Why the new version starts on 1 Sep and not on 17 Sep: payroll reads its rules as of the period's last day, but the
-- PF return (ECR) reads them as of the period's first day. A version starting on the 17th would make the two disagree for
-- September (payslips at 25,000, return blocked at 15,000). Starting on the 1st makes September a whole month at 25,000, which
-- is the reading that cannot under-contribute. The real date is kept in "notifiedFrom".
--
-- It stays a draft: a payroll consultant must sign it off, and must confirm the September reading, the EDLI ceiling and
-- the administrative-charge minimum (see "transitionBasis" and the notes in the source).
update app.payroll_rule
   set valid_to = date '2026-09-01'
 where statute = 'pf' and jurisdiction = 'IN' and valid_from = date '2025-04-01' and valid_to is null;

insert into app.payroll_rule (statute, jurisdiction, valid_from, data, source)
select 'pf', 'IN', date '2026-09-01',
       data || '{"ceiling":25000,"epsCeiling":25000,"edliCeiling":25000,"previousCeiling":15000,
                 "notifiedFrom":"2026-09-17","transitionMonth":"2026-09","transitionBasis":"whole_month"}'::jsonb,
       'EPF wage ceiling Rs 25,000 from 17 Sep 2026, Gazette S.O. 5109(E); PIB and Ministry of Labour releases 16 to 23 Sep 2026; '
       || 'EPS maximum Rs 2,083 per PIB; EPS and EDLI ceilings assumed to follow (not verified); September applied as a whole month '
       || '(Argus Partners paper of 21 Sep 2026, first reading; no EPFO circular found); awaiting consultant sign-off'
  from app.payroll_rule
 where statute = 'pf' and jurisdiction = 'IN' and valid_from = date '2025-04-01';
