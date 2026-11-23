# ScriptRunner for Jira: Data Center to Cloud readiness

> **Best-effort proof of concept.** Not supported, not maintained, not an official Adaptavist or Atlassian tool. Read-only. Results are indicative: verify before acting.

> **The point:** see which ScriptRunner scripts are actually being used on Data Center. Retire or archive the ones nobody needs, so you have fewer scripts to convert with the [ScriptRunner Migration Suite](https://docs.adaptavist.com/sms/latest).

> **For a real analysis of your instance, use the [ScriptRunner Migration Suite](https://docs.adaptavist.com/sms/latest).** Its Analyse and Assess tool shows how each script moves to Cloud; its Migration Agent converts them. This report doesn't replace it. It exists to answer one question before you start: which scripts are still in use, so you don't spend time converting ones nobody needs.

## What it is for

Before converting ScriptRunner scripts to Cloud, find the ones nobody uses, so you can archive or delete them first and convert less.

The report puts every ScriptRunner script into one of three decisions:

| Decision | Meaning |
|---|---|
| Keep | Ran in the last N days. Keep it and convert it with the ScriptRunner Migration Suite. |
| Archive or delete | No run in the last N days. If the owner agrees, disable it (keeps a copy) or delete it on Data Center before the Script Registry export. |
| Check first | Usage unknown. Don't archive or delete until it's checked. |

For each script, it also shows:

- on how many days it ran in the last N days (default 90)
- the date it last ran (UTC)

It shows usage only. It never says whether a script can move to Cloud. That is the ScriptRunner Migration Suite's job.

![Example report: ScriptRunner scripts grouped by usage](report_migration.png)

## Where it fits

1. Run this report and review the "Archive or delete" list with script owners.
2. Delete or disable scripts that are no longer needed.
3. Script Registry export ("Export active scripts only").
4. [ScriptRunner Migration Suite Analyse and Assess](https://docs.adaptavist.com/sms/latest/scriptrunner-migration-suite-web-app/scriptrunner-migration-analyse-and-assess-tool) (always do this).
5. Convert with the [ScriptRunner Migration Suite Migration Agent](https://docs.adaptavist.com/sms/latest/scriptrunner-migration-suite-web-app/scriptrunner-migration-agent), deploy with the [Dev and Deployment Tool](https://docs.adaptavist.com/sms/latest/scriptrunner-dev-and-deployment-tool).

Best used before the Script Registry export. Already ran Analyse and Assess? Use both together, then export and analyse again after retiring scripts.

## Before you start

- Jira Data Center with ScriptRunner for Jira installed.
- A Jira administrator who can open the ScriptRunner **Script Console**.
- Tested on Jira 10.3.24 and ScriptRunner 9.30.0, single node. Other versions may work but are untested.
- Try it on a non-production instance first if you can.

**Is it safe?** It only reads. It changes no Jira data or configuration. It makes no network calls, unless option 7 is ticked, which sends app keys to marketplace.atlassian.com to look them up.

Built-in protections:

- **One run at a time per node.** Closing or reloading the browser doesn't stop a run; it carries on on the server. Clicking Run again shows "Another run is still in progress" instead of starting a second run. (This uses a temporary JVM system property, not Jira data. It clears at the end of the run, or after 10 minutes.)
- **Time limit capped at 300 seconds** by default. Not a Jira limit: the report is lost once the browser or proxy stops waiting. If your proxy waits longer, raise `TIME_BUDGET_MAX_SEC` in the script's advanced settings.
- **User email checks are skipped above 50,000 users**, because they load every user into memory. An advanced setting in the script (`USER_EMAIL_SCAN_MAX_USERS`) changes the limit.
- **A failed check doesn't stop the report.** It's listed under "Problems in this run" with a fix.
- **Unknown usage is never shown as "Archive or delete".** Files not read, unreadable files, missing cluster node folders and failed checks all go to "Check first".

## How to run

1. Jira admin → ScriptRunner → **Console**.
2. Paste the whole of [`migration-readiness.groovy`](migration-readiness.groovy) and click **Run**. The options form (below) appears above the script.
3. Leave everything blank for a ScriptRunner-only scan, or tick "Include" options to add more. Click **Run** again.
4. Read the HTML report under the console. Start with "Read this first".

Large instance? Run with the defaults first (ScriptRunner only). Fastest, least chance of a timeout. Add "Include" options in a second run if needed.

### Form options

| # | Option | Default | What it does |
|---|---|---|---|
By default the report checks ScriptRunner only, plus the Jira version for the header.

| # | Option | Default | What it does |
|---|---|---|---|
| 1 | Maximum run time (seconds) | 50 | Browsers and proxies often give up after about 60 s. The report stops starting new checks at this limit and lists what it skipped. Maximum 300, unless `TIME_BUDGET_MAX_SEC` is raised in the script. |
| 2 | Treat a script as unused after (days) | 90 | Window for "days it ran". Widen it (e.g. 365) to catch quarterly or yearly scripts. |
| 3 | Script Listener names | blank | Labels listeners, which ScriptRunner can't list by name. Format: `uuid=Name; uuid=Name`. Find a UUID in ScriptRunner admin → Listeners → Edit → last part of the browser URL. |
| 4 | Include: Jira areas that host scripts | not included | Workflows, custom fields, projects. |
| 5 | Include: wider migration readiness | not included | Apps, application links, users, user directories, issue volume, issue security, server timezone. |
| 6 | With option 5: check every user's email | not included | Users with a missing or shared email. Loads every user into memory; skipped above 50,000 users. |
| 7 | With option 5: look up apps on the Marketplace | not included | Needs internet from the Jira server; sends app keys to marketplace.atlassian.com. |
| 8 | Include: instance inventory | not included | Groups, issue types, schemes, field configurations, screens (counts only). |

## Reading the report

### Problems in this run

Shown at the top only when something went wrong. Each line says what happened and how to fix it.

### Decision summary

Three tiles: Keep, Archive or delete, Check first. The "Archive or delete" list opens first, with steps for doing it safely.

### ScriptRunner scripts, in groups

| Group | Decision | Meaning | What to do |
|---|---|---|---|
| Archive or delete | Archive or delete | No run recorded in the window. | Ask the owner if it's still needed. If not, disable it (keeps a copy) or delete it. |
| Identify | Check first | Usage files that match no configured script: listeners, other ScriptRunner features, or deleted scripts. Never judged unused. | Name listeners with option 3 and re-run. Look up the rest before deciding anything. |
| Keep | Keep | Ran at least once in the window. | Keep. Analyse and Assess decides how it moves. |
| No usage data | Check first | A type ScriptRunner doesn't record usage for (e.g. Behaviours), or a workflow condition with no recorded run. Unknown, not zero. | Check with the owner. |
| Not read | Check first | Usage couldn't be read (e.g. time limit hit). Unknown, not zero. | Raise option 1 and re-run. |

Each row has a link to manage that script in ScriptRunner admin.

**Copy the full list into a spreadsheet:** open that section, select all in the box, copy, paste into Excel or Google Sheets. It lands in columns: suggested decision first, then empty Owner and Final decision columns to fill in.

### Other sections

- **Wider Jira migration readiness:** Jira areas that affect the overall migration plan (apps, application links, users, directories, issue security). Each line is marked Review, Plan, OK or Info.
- **Instance inventory:** counts for a complete record of the instance.
- **About this run:** settings used and how long it took.

## What's covered

| Feature type | Usage shown? |
|---|---|
| Scheduled Jobs, Escalation Services | Yes |
| Workflow Post-Functions, Validators | Yes |
| Script Fields | Yes |
| REST Endpoints, JQL Functions | Yes, once called at least once |
| Script Listeners | Yes, under "Identify" unless named with option 3. Listeners that never ran don't appear. |
| Workflow Conditions | Only where ScriptRunner has recorded a run |
| Behaviours | Listed, no usage (ScriptRunner doesn't record it) |
| Script Fragments, Mail Handlers, Resources, built-in scripts | Not included |

## Limits

- No run counts. ScriptRunner's stored data can't give an exact number of runs, so the report shows days instead.
- Days and dates are UTC. A run within about 5 minutes of midnight UTC may count on the next day.
- Runs from the last few minutes may not show yet. The ScriptRunner Performance tab is real-time.
- "0 days" isn't proof a script is unneeded. Usage isn't value either: a script that runs once a year can be critical. The owner decides.
- "Projects / scope" is where a script is configured to apply, not which projects triggered it.


How the data is read, and why: [docs/how-it-works.md](docs/how-it-works.md).

## Troubleshooting

Most problems are explained in the report itself, under "Problems in this run", with the fix.

| Problem | Fix |
|---|---|
| Page times out, no report | The run carries on on the server. Don't click Run again. Wait 5 minutes, then re-run with option 1 lowered (e.g. 40) and every "Include" option unticked. |
| "Another run of this report is still in progress" | A run is still going on this node (possibly from a page that timed out). Wait the minutes shown, then click Run once. |
| "The full report could not be drawn" | A simple version with the decision lists is shown instead. Nothing was changed. Send the error text to your Adaptavist contact. |
| "Ran low on memory" problem | Re-run with every "Include" option unticked, off-peak. If it repeats, don't re-run; send the report and Jira log to your Adaptavist contact. |
| "Email checks were skipped to protect Jira" | Expected above 50,000 users. To force it, raise `USER_EMAIL_SCAN_MAX_USERS` in the script's advanced settings and run off-peak. |
| Scripts show "Not read" | The time limit was reached. Raise option 1, or untick the "Include" options, and re-run. |
| Many "Identify" rows | Mostly listeners. Name them with option 3. |
| One yellow type-checking hint in the console (`getAllApplicationUsers`) | Expected. Doesn't affect the result. |
| "No usage folder found for cluster node(s)" warning | A node in the cluster has no usage files, so nothing is marked unused. Check that node with your Jira administrator; a brand-new node that hasn't run a script yet also causes this. |
| "RRD directory not found" warning | ScriptRunner usage files weren't found, so nothing is marked unused. Check ScriptRunner is installed and has run scripts. |



## Version

1.1.0: ScriptRunner-only by default with opt-in "Include" options, safety guards, "Problems in this run" with fixes, Keep / Archive or delete / Check first decisions. Not yet tested on a live instance; test on staging first.

1.0.0: tested on Jira 10.3.24 and ScriptRunner 9.30.0, single node.
