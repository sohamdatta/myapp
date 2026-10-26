# Security incident response

What to do when something goes wrong with the security of the HRMS or the data in it.
Written for a small team: one person leads, everyone else helps. Keep it short enough to follow at 2 a.m.

**The two clocks.** Both start when you *notice* the incident, not when you finish understanding it.

| Who | By when | What |
| --- | --- | --- |
| CERT-In | **6 hours** | Report the incident (directions of 28 April 2022 under section 70B(6), IT Act). Send what you know; add the rest later. |
| Data Protection Board of India, and each affected person | **Without delay**; a detailed report to the Board within **72 hours** | When personal data is involved (Digital Personal Data Protection Rules, Rule 7). |

Check the current wording before relying on this page: [cert-in.org.in](https://www.cert-in.org.in) and the
Ministry of Electronics and IT notices change. This page is an engineering aid, not legal advice.

## 1. Who does what

Fill in names and phone numbers and keep a printed or offline copy; the system may be the thing that is down.

| Role | Does | Person | Phone |
| --- | --- | --- | --- |
| Incident lead | Decides, keeps the timeline, makes the 6-hour report | _to fill in_ | |
| Point of Contact (registered with CERT-In) | The name CERT-In has on record; receives their calls | _to fill in_ | |
| Engineer on call | Looks at logs, contains, fixes | _to fill in_ | |
| Customer contact | Tells the affected Organizations | _to fill in_ | |
| Legal / advisor | Reads the DPDP notice before it is sent | _to fill in_ | |

## 2. What counts as an incident

CERT-In lists the types it wants reported (Annexure I of the directions). Those that apply to us:

- Unauthorised access to an IT system or data; compromise of an account (an Org Admin, a Super Admin, a server login)
- Data breach or data leak; theft of a laptop, phone or key that held access
- Attacks on the application or servers: malware, ransomware, defacement, denial of service, a vulnerable service being exploited
- Attacks on cloud accounts and servers (AWS, Oracle Cloud, Cloudflare, GitHub)
- Identity theft, spoofing and phishing aimed at us or our users
- Unauthorised access to social media or email accounts used by the company
- Attacks on or through IoT devices, which for us includes attendance devices that push punches (`/iclock`)
- Supply-chain attacks: a malicious or compromised dependency or build action

When unsure, treat it as an incident. Closing a false alarm costs an hour; a missed clock costs more.

## 3. The first hour

1. **Write down the time you noticed it** and who noticed. Start the timeline (section 8). Everything else is measured from this.
2. **Tell the incident lead.** If you are the lead, tell the Point of Contact.
3. **Do not delete anything.** No clean-up, no restarts that wipe logs, no "fixing" yet. Copy first.
4. **Contain**, choosing the least that stops the harm:
   - One account: suspend the user (Org Admin console), sign out their sessions, reset their password.
   - A Super Admin account or the platform: rotate `APP_DATA_KEY` only on advice (it makes stored data unreadable if done wrongly; see [the deployment guide](../deploy/README.md)), change the Super Admin password, revoke the server's deploy key.
   - A server: take a Lightsail/OCI snapshot first (evidence), then block the firewall except your own address.
   - A dependency or build: pin the previous version, stop the deploy job.
5. **Keep the evidence**: snapshot, and copy the container logs and the server's sign-in log to the log bucket now (`deploy/logs/ship-logs.sh`, give today's date).
6. **Decide whether personal data could be involved** (employee records, PAN, Aadhaar, bank details, salary, documents). If yes or maybe, both clocks apply.

## 4. Telling CERT-In (within 6 hours)

Send by email to **incident@cert-in.org.in** (check the address and the form on cert-in.org.in; phone and fax are also listed there).
Send what you have; do not wait to be sure. Say it is a first report.

Template:

```
Subject: Incident report - <company> - <type from Annexure I> - <date>

1. Reporter: <name>, <role>, <phone>, <email>. Registered Point of Contact: <name>.
2. Organisation: <legal name>, <address>, <product: Nexvance HRMS (PeopleOS)>.
3. Time noticed: <date, time, IST>.   Time it started, if known: <...>.
4. Type (Annexure I): <e.g. unauthorised access to a system and data>.
5. What happened, in plain words: <2-5 sentences>.
6. Systems affected: <hostnames, IP addresses, cloud account, region>.
7. Data involved: <kinds of data, number of people if known, whether personal data>.
8. Evidence kept: <log bucket path, snapshot ids>.
9. Done so far: <containment steps and times>.
10. Next: <what you are doing, when the next update will come>.
```

Keep the sent email and any reference number in the incident folder. Send updates as facts change.

## 5. Personal data: the Board and the people affected

If personal data was, or may have been, accessed, lost or disclosed:

1. **Each affected person** is told without delay, in plain language: what happened, which of their data, what could follow,
   what we have done, what they can do (for example change passwords, watch bank statements), and whom to contact.
   Employers (our customers) are the ones who hold the relationship with their employees; agree with the customer who sends it.
2. **The Data Protection Board** is told without delay, and a **detailed report within 72 hours**: the nature, extent, timing and
   location of the breach, the likely impact, the measures taken, the findings about who caused it and why, and the steps to prevent a repeat.
3. Whether we are the *data fiduciary* or the customer is, a legal question: in most set-ups the customer Organization is the
   fiduciary and we are the processor, who must tell them at once so they can meet their own duties. Settle this with the advisor in advance (see the checklist in [cert-in.md](cert-in.md)).

## 6. After containment

- Find the cause from the logs (Caddy access log, application log, server sign-in log, database log). They are kept 180 days in the Indian-region bucket.
- Fix it, test the fix, deploy by the normal route.
- Rotate anything that may have been seen: passwords, API keys, deploy keys, tokens.
- Restore from backup if data was altered ([backup and restore](../deploy/README.md)).
- Write the review (section 7) within a week.

## 7. Review

Blameless, one page: what happened, timeline, what worked, what did not, and changes with an owner and a date.
File it with the timeline in the incident folder. Add any new detection or test that would have caught it to the backlog.

## 8. Timeline (copy per incident)

| Time (IST) | Event | By |
| --- | --- | --- |
| | Noticed | |
| | Lead told | |
| | Contained | |
| | CERT-In report sent (due: noticed + 6 h) | |
| | Board told / people told | |
| | Detailed report to the Board (due: noticed + 72 h) | |
| | Fixed and verified | |

## 9. Rehearsals

Once a year, and after any change of Point of Contact, rehearse with a made-up incident: someone plays the one who notices,
the lead walks the first hour, and the CERT-In email is *drafted* (not sent). Time it; the report should be ready in well
under an hour. Record each rehearsal here.

| Date | Scenario | Time to report draft | What we changed |
| --- | --- | --- | --- |
| _none yet_ | | | |
