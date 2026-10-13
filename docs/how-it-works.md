# How it works

> Best-effort proof of concept. Shows on how many days each script ran, not how many times. Verify before acting.

## Where the usage data comes from

ScriptRunner records script executions in its own files, the same data behind
the **Performance** tab in ScriptRunner admin:

```
$JIRA_HOME/scriptrunner/rrd/{nodeId}/{scriptId}.rrd4j   (one folder per node)
$JIRA_HOME/scriptrunner/rrd/{scriptId}.rrd4j            (flat layout)
```

These are RRD (round-robin database) files.

- **Plain English:** a fixed-size logbook that keeps detailed recent history and
  rolls older history into daily summaries.
- **Technical:** rrd4j archives storing consolidated (averaged) values per time
  step, read with the `org.rrd4j` library bundled with ScriptRunner.

The script reads every file on every node and adds the nodes together. No node
name is needed.

## What usage is shown, and why

Each usage file keeps four levels of detail (as seen on Jira 10.3.24 / ScriptRunner 9.30.0):

| Rows of | Kept for | What a row means |
|---|---|---|
| 5 minutes | ~2 days | Runs in that 5 minutes, time-weighted (see below) |
| 30 minutes | 15 days | Average of the 5-minute slots that had runs |
| 2 hours | 60 days | Same, averaged |
| 1 day | ~2 years | Same, averaged |

Empty slots are left blank, not zero, and averages skip blanks. So an average
cannot be turned back into an exact count (a daily job and a job running every
5 minutes can both average 1).

- **Plain English:** past 2 days, ScriptRunner keeps "it ran, about this busy",
  not "it ran this many times".
- **Technical:** rrd4j AVERAGE consolidation with xff 0.999 ignores unknown (NaN)
  steps, so the number of runs behind a consolidated row is lost.

Even the 5-minute rows are not exact counts. ScriptRunner writes its tally every
5 minutes but not on the step boundaries (e.g. at :51:56), and rrd4j spreads each
tally across the rows it overlaps, so even these rows don't add up to the real number
of runs.

- **Plain English:** no part of the file gives a trustworthy number of runs.
- **Technical:** GAUGE values are time-weighted across step boundaries, updates are
  offset and stop when nothing runs, so no consolidation function sums to the true count.

So the report shows only what is reliable:

| Column | Source | Reliable? |
|---|---|---|
| Days it ran, last N days | Any non-blank row counts that UTC day once | Yes |
| Last run | Latest non-blank row, as a UTC date | Yes (date only) |

A run within about 5 minutes of midnight UTC may count on the next day. A failed
run counts as a run.

rrd4j timestamps mark the end of each row.

### Why the 5-minute rows are read from the last write, not from now

rrd4j only files a row (a 30-minute, 2-hour or daily summary) when the file is next
written to, and ScriptRunner only writes when something runs. So a script's latest
run day is missing from the daily rows until the script runs again. Only the
5-minute rows ending at the file's last write still hold it.

- **Plain English:** the logbook files each day's summary only when it's next
  opened. A weekly job's last run sits unfiled for a week, so the report reads the
  detailed page from the last time the book was written, not from today.
- **Technical:** consolidation happens on `update()`. The finest archive ends at
  `getLastUpdateTime()`, so the fetch is `[lastWrite - 2 days, lastWrite]`.

## How scripts are matched to their usage files

| Feature type | Listed from | Usage file name |
|---|---|---|
| Scheduled Jobs, Escalation Services | ScriptRunner job manager | Job ID |
| Workflow Post-Functions | Workflow definitions | Function ID |
| Workflow Validators | Workflow definitions | Function ID (recorded on first run) |
| Workflow Conditions | Workflow definitions | Function ID (usage shown only where recorded) |
| Script Fields | Custom field configuration schemes | Configuration scheme ID |
| Behaviours | ScriptRunner behaviour manager | No usage recorded |
| REST Endpoints | Usage files on disk | Method and name, e.g. `GET-myEndpoint` |
| JQL Functions | Usage files on disk | Function name |
| Script Listeners | Usage files on disk | ID (named via form field 4) |

Usage files that match nothing are listed as **"Not matched to a configured script"**,
always in the "identify these" group, never in "Consider retiring". This list is not
just listeners: it can include listeners, other ScriptRunner features, and deleted
scripts. Name listeners with form option 4.

## Rules that protect against wrong conclusions

- **Unread is never unused.** If the time limit stops the read, or the usage
  folder is missing, affected scripts show as "Not read", not "Consider
  retiring".
- **Every cluster node must be read.** On a cluster, the report asks Jira for its
  node list and compares it with the node folders found. If any node has no folder,
  scripts with no recorded run show as "Not read", and a warning names the node.
  (A node that has never run a script also has no folder, so this can be over-cautious.)
- **Not checked is never not found.** Apps not looked up on the Marketplace
  show as "not checked" (amber), never "not found" (red).
- **No migratability judgements.** The report never says a script can't move to
  Cloud. That's the ScriptRunner Migration Suite's job.
- **Conditions** only show usage where ScriptRunner has recorded it. Otherwise
  they show "no usage data", never "unused". (Validators are recorded.)

## Limits

- No run counts: the report shows on how many days a script ran.
- Days and dates are UTC calendar days.
- Very recent runs may not show yet; the SR Performance tab is real-time.
- "Last run" is a date only, and looks back up to 2 years.
- Listeners can't be listed by API. Listeners that never ran don't appear.
- REST endpoints and JQL functions appear only once they've been called.
- Not included: Script Fragments, Mail Handlers, Resources, built-in scripts.
- "Projects / scope" is where a script is configured to apply, not which
  project triggered each run.
- Disabled apps aren't listed in the apps check.

## Time limit

The time limit is checked before each check, and inside the two long loops
(reading usage files, and Marketplace lookups). ScriptRunner usage runs first.
A single slow step can't be interrupted once started, so set the limit well
below your proxy timeout.

On a large instance, make the first run Quick with form options 5, 6 and 7 ticked
(ScriptRunner usage only). Fastest run, least chance of a timeout.
