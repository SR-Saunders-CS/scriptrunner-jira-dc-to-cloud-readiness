# ScriptRunner for Jira: Data Center usage report

> **Best-effort proof of concept.** Not supported, not maintained, not an official Adaptavist or Atlassian tool. Read-only. No network calls. Results are indicative: verify before acting.

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
- Tested on Jira 10.3.24 and ScriptRunner 9.30.0, single node. Other versions may work but are untested.
- Try it on a non-production instance first if you can.

**Is it safe?** It only reads. It changes no data or configuration and makes no network calls.

### On a cluster (multi-node)

Run it once, from any node. You don't need to run it on each node.
The report reads every node's usage files from the shared home and combines them.
Check the "Nodes read" line (under "How this data was collected") matches Jira admin → System → Clustering.
If a node is missing, nothing is marked unused and the report tells you which node.
Not yet tested on a cluster: please report what you see.

## How to run

1. Jira admin → ScriptRunner → **Console**.
2. Paste the whole of [`migration-readiness.groovy`](migration-readiness.groovy) and click **Run**. The options form (below) appears above the script.
3. Change options, or leave them blank for the defaults. Click **Run** again.
4. Read the HTML report under the console. Start with "Read this first".

### Form options

| # | Option | Default | What it does |
|---|---|---|---|
| 1 | Maximum run time (seconds) | 50 | Browsers and proxies often give up after about 60 s. The report stops reading at this limit and marks anything unread as "Not read". |
| 2 | Treat a script as unused after (days) | 90 | Window for "days it ran". Widen it (e.g. 365) to catch quarterly or yearly scripts. |
| 3 | Script Listener names | blank | Labels listeners, which ScriptRunner can't list by name. Format: `uuid=Name; uuid=Name`. Find a UUID in ScriptRunner admin → Listeners → Edit → last part of the browser URL. |

## Reading the report

The top shows three numbers: **Keep**, **Archive or delete**, **Check first**. Below that, every script is in exactly one group:

| Group | Meaning | What to do |
|---|---|---|
| Archive or delete | No run recorded in the window. | Ask the owner if it's still needed. If not, disable it (keeps a copy) or delete it. |
| Keep | Ran at least once in the window. | Keep. Analyse and Assess decides how it moves. |
| Check first: identify | Usage files that match no configured script: listeners, other ScriptRunner features, or deleted scripts. Never judged unused. | Name listeners with option 3 and re-run. Look up the rest before deciding anything. |
| Check first: not read | Usage couldn't be read (e.g. time limit hit, cluster node missing). Unknown, not zero. | Follow the fix in "Problems in this run" and re-run. |
| Check first: no data | A type ScriptRunner doesn't record usage for (e.g. Behaviours), or a workflow condition with no recorded run. Unknown, not zero. | Check with the owner. |

Each row links to where that script is managed in ScriptRunner admin.

**Copy the full list into a spreadsheet:** open "Copy the full list", select all in the box, copy, paste into Excel or Google Sheets. It lands in columns, with empty Owner and Final decision columns to fill in.

## What's covered

| Feature type | Usage shown? |
|---|---|
| Scheduled Jobs, Escalation Services | Yes |
| Workflow Post-Functions, Validators | Yes |
| Script Fields | Yes |
| REST Endpoints, JQL Functions | Yes, once called at least once |
| Script Listeners | Yes, under "Check first: identify" unless named with option 3. Listeners that never ran don't appear. |
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

| Problem | Fix |
|---|---|
| Page times out, no report | Wait 5 minutes (the run carries on on the server), then re-run once with option 1 lower (e.g. 40). |
| Scripts show "Not read" | Follow the fix in "Problems in this run". If the time limit was reached, raise option 1 and re-run. |
| Many "Identify" rows | Mostly listeners. Name them with option 3. |
| "No usage folder found for cluster node(s)" | A node in the cluster has no usage files, so nothing is marked unused. Check that node with your Jira administrator; a brand-new node that hasn't run a script yet also causes this. |
| "RRD directory not found" | ScriptRunner usage files weren't found, so nothing is marked unused. Check ScriptRunner is installed and has run scripts. On a cluster, check the shared home is mounted on that node. |
| "Another run is still in progress" | Wait the minutes shown, then run once. |

## Version

2.0.0: ScriptRunner usage only. The optional wider Jira readiness checks (apps, users, directories, inventory) were removed; they're still in [v1.1.0](https://github.com/SR-Saunders-CS/scriptrunner-jira-dc-to-cloud-readiness/tree/v1.1.0). For Jira-wide counts, use ScriptRunner's [Instance Audit](https://docs.adaptavist.com/sr4js/latest/best-practices/audit-your-jira-instance-using-scriptrunner) built-in script and Atlassian's [pre-migration checklist](https://support.atlassian.com/migration/docs/jira-pre-migration-checklist/).
