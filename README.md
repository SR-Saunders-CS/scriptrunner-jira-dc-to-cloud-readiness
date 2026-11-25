# ScriptRunner for Jira: Data Center usage report

> **Best-effort proof of concept.** Not supported, not maintained, not an official Adaptavist or Atlassian tool. Read-only for Jira. Writes one file (the saved report, see below). No network calls. Results are indicative: verify before acting.

> **The point:** see which ScriptRunner scripts are actually being used on Data Center. Retire or archive the ones nobody needs, so you have fewer scripts to convert with the [ScriptRunner Migration Suite](https://docs.adaptavist.com/sms/latest).

> **For a real analysis of your instance, use the [ScriptRunner Migration Suite](https://docs.adaptavist.com/sms/latest).** Its Analyse and Assess tool shows how each script moves to Cloud; its Migration Agent converts them. It doesn't show which scripts are actually used. That's the gap this report fills, so you don't convert scripts nobody needs.

## What it shows

For each ScriptRunner script on Data Center:

- on how many days it ran in the last N days (default 90)
- the date it last ran (UTC)

It shows usage only. It never says whether a script can move to Cloud.

[![Example report: ScriptRunner scripts grouped by usage](report_migration.png)](report_migration.png)

## Where it fits

1. Run this report and review the "Archive or delete" list with script owners.
2. Disable or delete scripts that are no longer needed.
3. Script Registry export ("Export active scripts only").
4. [ScriptRunner Migration Suite Analyse and Assess](https://docs.adaptavist.com/sms/latest/scriptrunner-migration-suite-web-app/scriptrunner-migration-analyse-and-assess-tool) (always do this).
5. Convert with the [ScriptRunner Migration Suite Migration Agent](https://docs.adaptavist.com/sms/latest/scriptrunner-migration-suite-web-app/scriptrunner-migration-agent), deploy with the [Dev and Deployment Tool](https://docs.adaptavist.com/sms/latest/scriptrunner-dev-and-deployment-tool).

Best used before the Script Registry export. Already ran Analyse and Assess? Use both together, then export and analyse again after retiring scripts.

## Before you start

- Jira Data Center with ScriptRunner for Jira installed.
- A Jira administrator who can open the ScriptRunner **Script Console**.
- Tested on Jira 10.3.24 with ScriptRunner 9.30.0, and Jira 10.3.27 with ScriptRunner 9.46.0. Run on a single node and on a large multi-node production cluster. Other versions may work but are untested.
- Try it on a non-production instance first if you can.

**Is it safe?** It changes no Jira data or configuration and makes no network calls. It writes one file: the finished report, saved to `$JIRA_HOME/scriptrunner/migration-readiness/last-report.html` and overwritten on each run. The file lists script, workflow and project names, and gets the Jira home folder's normal file permissions. Nothing else is written.

### On a cluster (multi-node)

Run it once, from any node. You don't need to run it on each node.
The report reads every node's usage files from the shared home and combines them.
Check the "Nodes read" line (under "How this data was collected") matches Jira admin → System → Clustering.
If a node is missing, nothing is marked unused and the report tells you which node.
Tip: run it on one node's own address rather than through the load balancer, so a second click can't start a run on another node.

## How to run

1. Jira admin → ScriptRunner → **Console**.
2. Paste the whole of [`migration-readiness.groovy`](migration-readiness.groovy) and click **Run**. The options form (below) appears above the script.
3. Change options, or leave them blank for the defaults. Click **Run** again.
4. Read the HTML report under the console. Start with "Read this first".

### Form options

| # | Option | Default | What it does |
|---|---|---|---|
| 1 | Maximum run time (seconds) | 50 | Browsers and proxies often give up after about 60 s. The report stops reading at this limit and marks anything unread as "Not read". Capped at 300 unless `TIME_BUDGET_MAX_SEC` is raised in the script. On large instances, use 300. |
| 2 | Treat a script as unused after (days) | 90 | Window for "days it ran". Widen it (e.g. 365) to catch quarterly or yearly scripts. |
| 3 | Script Listener names | blank | Usually not needed: since 2.2.0 listeners are named automatically. Only use it if the report says ScriptRunner's own script list couldn't be read. Format: `uuid=Name; uuid=Name`. |
| 4 | Show the last saved report | blank | Type `yes` to show the last saved report straight away, without running anything. Use it when the page timed out or showed an error before the report appeared. |

## Reading the report

The top shows **Keep**, **Archive or delete**, **Check first**, and, when there are any, **No action: left over**. Below that, every row is in exactly one group:

| Group | Meaning | What to do |
|---|---|---|
| Archive or delete | No run recorded in the window. | Ask the owner if it's still needed. If not, disable it (keeps a copy) or delete it. |
| Keep | Ran at least once in the window. | Keep. Analyse and Assess decides how it moves. |
| Check first: not read | Usage couldn't be read (e.g. time limit hit, cluster node missing). Unknown, not zero. | Follow the fix in "Problems in this run" and re-run. |
| Check first: no data | A behaviour with no recorded run for any of its scripts, or a workflow condition with no recorded run. Unknown, not zero. | Check with the owner. |
| Check first: left over, but ran recently or not read | A usage file whose ID matches no current ScriptRunner script, that ran in the window or couldn't be read. Usually a script deleted or changed recently. | Check it isn't a current script before ignoring it. |
| No action: left over | A usage file whose ID matches no current ScriptRunner script, with no run in the window. ScriptRunner keeps these files after a script is deleted or changed. | Nothing to convert, archive or delete. If you recognise one as a current script, treat it as Archive or delete. |

If ScriptRunner's own script list can't be read on your version, the report works as in 2.1.0 and shows a "Check first: identify" group instead of the left-over groups.

Each row links to where that script is managed in ScriptRunner admin.

**Copy the full list into a spreadsheet:** open "Copy the full list", select all in the box, copy, paste into Excel or Google Sheets. It lands in columns, with empty Owner and Final decision columns to fill in.

## What's covered

| Feature type | Usage shown? |
|---|---|
| Scheduled Jobs, Escalation Services | Yes |
| Workflow Post-Functions, Validators | Yes |
| Script Fields | Yes |
| REST Endpoints, JQL Functions | Yes, once called at least once |
| Script Listeners | Yes, named automatically, including ones that never ran |
| Workflow Conditions | Only where ScriptRunner has recorded a run |
| Behaviours | Yes, from the scripts inside each behaviour (initialiser and field scripts). A behaviour with no recorded script run shows as "No data". |
| Script Fragments, Mail Handlers, Resources, built-in scripts | Not included |

## Limits

- No run counts. ScriptRunner's stored data can't give an exact number of runs, so the report shows days instead.
- Days and dates are UTC. A run within about 5 minutes of midnight UTC may count on the next day.
- Runs from the last few minutes may not show yet. The ScriptRunner Performance tab is real-time.
- When there's no time to open a usage file that hasn't changed for longer than the window (at least 90 days), it's judged from its file date: ScriptRunner only writes the file when the script runs. The last-run column then says "file last changed" instead of an exact date.
- "0 days" isn't proof a script is unneeded. Usage isn't value either: a script that runs once a year can be critical. The owner decides.
- "Projects / scope" is where a script is configured to apply, not which projects triggered it.

How the data is read, and why: [docs/how-it-works.md](docs/how-it-works.md).

## Troubleshooting

| Problem | Fix |
|---|---|
| Page times out, goes blank, or says "Failed to post update" | Don't click Run again. The run carries on on the server and saves its report. Wait for the time limit plus a few minutes, then type `yes` in option 4 and click Run. |
| Scripts show "Not read" | Follow the fix in "Problems in this run". If the time limit was reached, raise option 1 and re-run. |
| Large instance: many rows "Not read" | Set option 1 to 300 and re-run, off-peak. Files are read newest first, so the most useful results come first. If the page times out, use option 4 after the time limit plus a few minutes. |
| Many "left over" rows | Normal on older instances: ScriptRunner keeps usage files after scripts are deleted. Check only the "ran recently" ones. |
| "No usage folder found for cluster node(s)" | A node in the cluster has no usage files, so nothing is marked unused. Check that node with your Jira administrator; a brand-new node that hasn't run a script yet also causes this. |
| "RRD directory not found" | ScriptRunner usage files weren't found, so nothing is marked unused. Check ScriptRunner is installed and has run scripts. On a cluster, check the shared home is mounted on that node. |
| "Another run is still in progress" | Wait the minutes shown. The last saved report (from an earlier run) is shown below the message, clearly dated. When the run finishes, use option 4 to see its report. |
| "Report could not be saved" in the log | The Jira home folder isn't writable by Jira. The report on screen is unaffected. |

## Version

2.3.0 (large instances):
- A usage file not changed for longer than the window (at least 90 days) can't hold a recent run, so when there's no time to open it, it's judged from its file date. Left-over files of that age are never opened.
- Files are read newest first, by 4 parallel workers (`READ_THREADS` in the script's advanced settings).
- Listing scripts gets its own share of the time limit; finding files no longer uses it up.
- Key progress lines (time per step, RUN COMPLETE) are logged at WARN, so they reach atlassian-jira.log.

2.2.0:
- Usage files are matched using ScriptRunner's own list of scripts, so listeners are named automatically.
- Behaviours show usage, from the scripts inside each behaviour.
- Usage files of deleted or changed scripts are shown as "left over" instead of "Identify". Before, a deleted REST endpoint or JQL function could appear under "Archive or delete".
- "No data" has its own colour in the bar.

2.1.0:
- Scripts are listed first, then their usage files are read first. Files that match no configured script are read only if time remains, so large instances spend their time on scripts that matter.
- A script with a recent run in any file that was read shows as Keep, even if another node's file for it wasn't read. Before, it showed as "Not read".
- The finished report is saved to one file and can be shown again with option 4.
- Workflow conditions whose usage file was skipped by the time limit now show as "Not read", not "No data".
- 40-character hash IDs are listed under "Identify" instead of being labelled "JQL Function".
- The time limit covers the whole run: listing gets at most 30% of it, and reading stops early enough to build and save the report.
- Time taken by each step is shown in the report and the Jira log.

2.0.0: ScriptRunner usage only. The optional wider Jira readiness checks (apps, users, directories, inventory) were removed; they're still in [v1.1.0](https://github.com/SR-Saunders-CS/scriptrunner-jira-dc-to-cloud-readiness/tree/v1.1.0). For Jira-wide counts, use ScriptRunner's [Instance Audit](https://docs.adaptavist.com/sr4js/latest/best-practices/audit-your-jira-instance-using-scriptrunner) built-in script and Atlassian's [pre-migration checklist](https://support.atlassian.com/migration/docs/jira-pre-migration-checklist/).
