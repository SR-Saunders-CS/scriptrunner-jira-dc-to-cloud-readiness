# How it works

> Best-effort proof of concept. Not part of ScriptRunner and not covered by Adaptavist Support. It relies on how ScriptRunner stores usage on disk, which may change between versions. Verify before acting.

This page describes version 2.5.0.

## Where the usage data comes from

ScriptRunner keeps a usage file for each script that has run, the same data behind
the **Performance** tab in ScriptRunner admin:

```
$JIRA_HOME/scriptrunner/rrd/{nodeId}/{usageId}.rrd4j   (one folder per node)
$JIRA_HOME/scriptrunner/rrd/{usageId}.rrd4j            (flat layout)
```

The report never opens these files. It only reads their names and last-changed
dates from the folder listing.

- **Plain English:** we look at when each logbook was last written in, not at what's
  written inside it.
- **Technical:** one `readAttributes` call per file during the directory listing;
  the newest `lastModifiedTime` per usage ID across all node folders is used.

## What usage is shown, and why

A usage file appears to be written only when its script runs (not confirmed in
ScriptRunner's documentation). So the date a script's file last changed is taken as
when the script last ran, give or take a few minutes.

| Shown | How it's worked out |
|---|---|
| Last run | Newest last-changed date of the script's usage files, across all nodes (UTC date) |
| Ran in last N days | That date is inside the window (option 2, default 90 days) |
| No run in last N days, archive candidate | The script has a usage file, but it last changed before the window, or the script has no usage file at all |

Not shown, on purpose: run counts and "days it ran". Getting those means opening
ScriptRunner's files while Jira may be writing them. Version 2.5.0 stopped doing
that, so the report can't interfere with ScriptRunner.

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
- If none of its scripts has a usage file, it shows as **"Usage unknown"**, never as
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
| Leftover usage files | No run in the window | No script behind them to convert or archive |
| Usage unknown: leftover file, but ran recently or not read | A run in the window, or not read | Usually a script deleted or changed recently. A recent run could also mean a current script of a type the report doesn't list, so it's flagged, not ignored |

If ScriptRunner's own list can't be read on your version, or doesn't contain most
of the scripts the report found by itself, the list isn't used. The report then
works as in 2.1.0: unmatched usage files are listed under "Usage unknown: not matched to a script",
and listeners can be named with form option 3.

## Rules that protect against wrong conclusions

- **Unknown is never unused.** A script whose usage can't be worked out shows under
  "Usage unknown", never under "No run".
- **Unreadable is never unused.** If a usage file's date can't be read, that script
  shows as "Not read".
- **A recent date wins.** A recent file date on any node shows the script under
  "Ran in last N days", even if another node's file is older.
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
- **Data, not verdicts.** Groups are named after what the data shows. The customer
  and each script's owner decide what to archive.
- **No migratability judgements.** The report never says whether a script can move
  to Cloud. That's the ScriptRunner Migration Suite's job.

## Protecting the Jira instance

- **Read-only for Jira.** No Jira data or configuration is changed, and no network
  calls are made.
- **ScriptRunner's usage files are never opened.** Only their names and
  last-changed dates are read from the folder listing, so the report can't get in
  the way of ScriptRunner writing them.
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
  so and points to the fix. The "Ran in last" and "No run in last" lists stay
  reliable, because anything unread is under Usage unknown.
- **Every check is wrapped**, so one failure doesn't lose the whole report. If the
  report itself can't be drawn, a plain version with the usage lists is shown instead.
- **Log output is capped** at 200 script lines. Key progress lines (time per step,
  usage IDs with a file date, REPORT SAVED, RUN COMPLETE) are logged at WARN so they reach
  `atlassian-jira.log`.

## Time limit

The time limit covers the whole run:

1. **Finding usage files:** folder listing and file dates only. No file is opened.
2. **Reading ScriptRunner's own script list.**
3. **Listing scripts:** up to 80% of the limit, counted from when listing starts,
   and never past the limit minus the reserve.
4. **Building and saving the report:** uses the reserve (5 to 30 seconds).

The time taken by each step is shown in the report, in the saved report's header,
and in the log. Scripts not listed because of the time limit are still matched
through ScriptRunner's own list where possible. A usage file that matches no current
script is shown as left over, so if the banner says the time limit was reached,
re-run with a higher limit before relying on the Leftover list. A single slow step
can't be interrupted once started, so set the limit below your proxy timeout, or run
on one node's own address.

## Limits

- Last run is a file date, so it can be a few minutes after the actual run. Dates are UTC.
- No run counts and no "days it ran".
- If the shared home was copied or restored without keeping file dates, dates may be
  wrong. Treat a surprising result as a reason to check, not to act.
- Runs from the last few minutes may not show yet; the Performance tab is real-time.
- Usage only appears where ScriptRunner has written a usage file the report can
  match to a script. No match means usage is unknown, not zero.
- Not included: Script Fragments, Mail Handlers, Resources, built-in scripts.
- "Projects / scope" is where a script is configured to apply, not which project
  triggered each run.
