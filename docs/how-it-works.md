# How it works

> Best-effort proof of concept. Not part of ScriptRunner and not covered by Adaptavist Support. It reads internal ScriptRunner data that may change between versions. Shows on how many days each script ran, not how many times. Verify before acting.

This page describes version 2.4.0.

## Where the usage data comes from

ScriptRunner records script executions in its own files, the same data behind
the **Performance** tab in ScriptRunner admin:

```
$JIRA_HOME/scriptrunner/rrd/{nodeId}/{usageId}.rrd4j   (one folder per node)
$JIRA_HOME/scriptrunner/rrd/{usageId}.rrd4j            (flat layout)
```

These are RRD (round-robin database) files.

- **Plain English:** a fixed-size logbook that keeps detailed recent history and
  rolls older history into daily summaries.
- **Technical:** rrd4j archives storing consolidated (averaged) values per time
  step, read with the `org.rrd4j` library bundled with ScriptRunner.

The script reads the files from every node and combines them. No node name is needed.

## What usage is shown, and why

Each usage file keeps four levels of detail (as seen on Jira 10.3 / ScriptRunner 9.30;
other versions may differ):

| Rows of | Kept for | What a row means |
|---|---|---|
| 5 minutes | ~2 days | Runs in that 5 minutes, time-weighted (see below) |
| 30 minutes | 15 days | Average of the 5-minute slots that had runs |
| 2 hours | 60 days | Same, averaged |
| 1 day | ~2 years | Same, averaged |

Empty slots are left blank, not zero, and averages skip blanks. That suits
performance graphs, but it means a run count can't be worked back out of an
average (a daily job and a job running every 5 minutes can both average 1).

- **Plain English:** beyond the last 2 days, the file records "it ran, about this
  busy", which is what a performance graph needs, rather than "it ran this many times".
- **Technical:** rrd4j AVERAGE consolidation ignores unknown (NaN) steps, so the
  number of runs behind a consolidated row is lost.

The 5-minute rows aren't designed as exact counts either. Tallies are written every
5 minutes, not on the step boundaries, and rrd4j spreads each tally across the rows
it overlaps.

- **Plain English:** the files are built to draw performance graphs, not to count
  runs, so the report doesn't try to count them.
- **Technical:** GAUGE values are time-weighted across step boundaries, and updates
  stop when nothing runs, so no consolidation function sums to the true count.

So the report shows what the files can answer reliably:

| Column | Source | Reliable? |
|---|---|---|
| Days it ran, last N days | Any non-blank row counts that UTC day once | Yes |
| Last run | Latest non-blank row, as a UTC date | Yes (date only) |

A run within about 5 minutes of midnight UTC may count on the next day. A failed
run counts as a run. rrd4j timestamps mark the end of each row.

### Why the 5-minute rows are read from the last write, not from now

rrd4j only files a summary row when the file is next written, and a usage file is
only written when something runs. So a script's latest run day is missing from the
daily rows until it runs again. Only the 5-minute rows ending at the file's last
write still hold it.

- **Plain English:** the logbook files each day's summary only when it's next
  opened, so the report reads the detailed page from the last time the book was
  written, not from today.
- **Technical:** consolidation happens on `update()`. The finest archive ends at
  `getLastUpdateTime()`, so the fetch is `[lastWrite - 2 days, lastWrite]`.

## How scripts are matched to their usage files

The report lists configured scripts, then matches each one to its usage file by ID.
It also reads ScriptRunner's own list of current scripts (the list behind its
execution history), which gives the usage ID and name of each one.

| Feature type | Listed from | Usage file name |
|---|---|---|
| Scheduled Jobs, Escalation Services | ScriptRunner job settings | Job ID |
| Workflow Post-Functions, Validators | Workflow definitions | Function ID |
| Workflow Conditions | Workflow definitions | Function ID (usage shown only where recorded) |
| Script Fields | Custom field configuration schemes | Configuration scheme ID |
| Script Listeners | ScriptRunner's own list | Listener ID (named automatically) |
| REST Endpoints | ScriptRunner's own list | Method and name, e.g. `GET-myEndpoint` |
| JQL Functions | ScriptRunner's own list | Function name |
| Behaviours | Behaviour settings | One ID per script inside the behaviour (see below) |

REST endpoints and JQL functions are listed once they've been called, so built-in
JQL functions that were never used don't fill the report.

### Behaviours

Usage is recorded for each script inside a behaviour (its initialiser and field
scripts), under that script's own ID, not the behaviour's ID. The report reads
those IDs from the behaviour's configuration.

- A behaviour counts as run when any of its scripts ran.
- If none of its scripts has a usage file, it shows as **"No data"**, never as
  unused, because not every kind of behaviour script is confirmed to record usage.

### Left-over usage files

A usage file stays on disk after its script is deleted, or after the script's ID
changes. So an older instance can have many usage files that belong to no current
script.

- **Plain English:** the logbook outlives the script it was written for.
- **Technical:** a usage ID that isn't in ScriptRunner's list of current scripts,
  and isn't found by the report's own listing, is treated as left over.

Left-over files are split in two:

| Group | When | Why |
|---|---|---|
| No action: left over | No run in the window | Nothing to convert, archive or delete |
| Check first: left over, but ran recently or not read | A run in the window, or not read | Usually a script deleted or changed recently. A recent run could also mean a current script of a type the report doesn't list, so it's flagged, not ignored |

If ScriptRunner's own list can't be read on your version, or doesn't contain most
of the scripts the report found by itself, the list isn't used. The report then
works as in 2.1.0: unmatched usage files are listed under "Check first: identify",
and listeners can be named with form option 3.

## Large instances: what's read, and in what order

On large instances, opening every usage file can take longer than a browser waits.
So the report decides what to open first.

### File date as evidence

A usage file is only written when its script runs. So a file that hasn't changed
for longer than the window can't hold a run inside it.

- **Plain English:** if the logbook hasn't been touched in 90 days, nothing was
  written in it in those 90 days.
- **Technical:** the file's last-modified time is a lower bound on its last write.
  If it's older than the window, no row inside the window can exist.

How it's used:

- The window for this rule is the chosen period, but never less than 90 days, plus
  one day for clock differences between servers.
- A script whose files are all older than that, and which couldn't be opened in
  time, is judged "no run in the window". Its last-run column says
  "file last changed" with a date, instead of an exact last-run date.
- Left-over files of that age are never opened. They go straight to "No action".
- The only way a file date can mislead is by looking newer than it should (for
  example after files are copied). That just means a file gets opened that didn't
  need to be. It never makes a recent run look old.

### Reading order

1. Scripts with a recently changed file, newest first, so recent runs (Keep) are
   found first.
2. Scripts with only old files, for exact last-run dates.
3. Left-over files with a recent change.

Files are read by 4 parallel workers (`READ_THREADS` in the advanced settings,
1 to 8). Fewer workers put less read load on the shared home at once.

## Rules that protect against wrong conclusions

- **Unread is never unused.** If the time limit stops the read and the file is
  recent, the script shows as "Not read", not "Archive (disable)".
- **Unreadable is never unused.** A recent usage file that can't be read puts that
  script under "Not read".
- **A recent run wins.** A run in any file that was read shows the script as Keep,
  even if another node's file for it wasn't read.
- **Every cluster node must be read.** The report compares Jira's node list with
  the node folders found. ScriptRunner cleans up node IDs before using them as
  folder names, so both are compared with only letters and digits kept. Nodes Jira
  lists as offline don't need a folder (any folder they left is still read). If an
  online node has no folder, nothing is judged unused and a warning names the node.
  A node that has never run a script also has no folder, so this can be
  over-cautious.
- **A failed check is never a silent gap.** If the check fails part-way, anything
  that would look unused becomes "Not read", and the error is listed under
  "Problems in this run" with its fix.
- **No migratability judgements.** The report never says whether a script can move
  to Cloud. That's the ScriptRunner Migration Suite's job.
- **Conditions** only show usage where it has been recorded. Otherwise they show
  "No data", never "unused".

## Protecting the Jira instance

- **Read-only for Jira.** No Jira data or configuration is changed, and no network
  calls are made.
- **One file written:** the finished report, saved to
  `$JIRA_HOME/scriptrunner/migration-readiness/last-report.html` under a temporary
  name and then renamed, so a half-written file is never shown. Form option 4 shows
  it again without running anything.
- **Treat the saved report as sensitive.** It lists every script found, including
  custom REST endpoint names and where each script applies. Share it only with
  people who are allowed to see that configuration, and delete it when you're done.
- **One run at a time per node.** A run keeps going on the server even if the
  browser gives up. A JVM system property marks a run in progress, so a second
  click shows a "still running" message (and the last saved report) instead of
  starting another run. It clears at the end of the run, or after that run's own time
  limit plus 5 minutes if a run was cut short. It's per node: on a cluster, run it on one
  node's own address rather than through the load balancer.
- **Time limit set by option 1 only**, 50 seconds by default, up to 1200. The report
  travels back in the same browser request, so a long run can lose the page; the run
  still finishes and saves its report, which option 4 shows. A run uses one Jira
  request thread for its whole time, so run long ones off-peak.
- **Incomplete runs are flagged at the top.** If anything wasn't read, a banner says
  so and points to the fix. Keep and Archive (disable) stay safe to use, because
  anything unread is under Check first.
- **Every check is wrapped**, including against out-of-memory errors, so one failure
  doesn't lose the whole report. If the report itself can't be drawn, a plain
  version with the decision lists is shown instead.
- **Log output is capped** at 200 script lines. Key progress lines (time per step,
  usage files read, REPORT SAVED, RUN COMPLETE) are logged at WARN so they reach
  `atlassian-jira.log`.

## Time limit

The time limit covers the whole run:

1. **Finding usage files:** folder listing and file dates only. No file is opened.
2. **Listing scripts:** gets its own share, 30% of the limit (at least 10 seconds),
   counted from when listing starts.
3. **Reading usage files:** until the limit minus a reserve (5 to 30 seconds).
4. **Building and saving the report:** uses the reserve.

The time taken by each step is shown in the report, in the saved report's header,
and in the log. Scripts not listed because of the time limit have their usage files
shown under "Check first", never as unused. A single slow step can't be interrupted
once started, so set the limit below your proxy timeout.

## Limits

- No run counts: the report shows on how many days a script ran.
- Days and dates are UTC calendar days.
- Runs from the last few minutes may not show yet; the Performance tab is real-time.
- "Last run" is a date only, and looks back up to 2 years.
- Not included: Script Fragments, Mail Handlers, Resources, built-in scripts.
- "Projects / scope" is where a script is configured to apply, not which project
  triggered each run.
