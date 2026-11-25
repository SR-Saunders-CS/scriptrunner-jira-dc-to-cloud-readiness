// ═══════════════════════════════════════════════════════════════════════════
// ScriptRunner usage report for Jira Data Center
// https://github.com/SR-Saunders-CS/scriptrunner-jira-dc-to-cloud-readiness
// Run from: ScriptRunner Script Console (Jira Data Center)  |  Output: HTML
//
// ⚠ BEST-EFFORT PROOF OF CONCEPT — NOT A SUPPORTED PRODUCT
//   • Provided as-is, on a best-effort basis. Not maintained, not supported,
//     and not an official Adaptavist or Atlassian tool. No warranty.
//   • Read-only for Jira: makes no changes to Jira data or configuration.
//   • Writes one file: the finished report, saved to
//     $JIRA_HOME/scriptrunner/migration-readiness/last-report.html
//     (overwritten on each run). Nothing else is written.
//   • No network calls.
//   • Advice only: results are indicative, not authoritative. Verify before acting.
//
// WHAT IT DOES
//   Shows which ScriptRunner scripts are actually used on Data Center: on how
//   many days each one ran in the last N days, and when it last ran. Use it to
//   retire scripts nobody needs BEFORE converting anything, so you convert fewer.
//   ScriptRunner Migration Suite Analyse and Assess shows how each script moves
//   to Cloud, but not whether it is used. This report fills that gap. It never
//   judges what can or cannot move to Cloud.
//
// HOW TO RUN
//   1. Paste this whole file into the Script Console and click Run. The options
//      form appears above the script.
//   2. Leave the options blank for the defaults, or change them. Click Run again.
//   3. Read the HTML report below the console. Start with "Read this first".
//   On a cluster, run it once, from any node. It reads every node's usage files.
//
// SAFETY BUILT IN (v2.3.0)
//   • Only one run at a time per node: clicking Run again while a run is still going
//     shows a "still running" message instead of starting a second run.
//   • Maximum run time is capped at 300 seconds by default (TIME_BUDGET_MAX_SEC in
//     ADVANCED SETTINGS). Only raise it if your proxy waits longer than that.
//   • A check that fails is reported with a fix; the rest of the report still shows.
//   • Unknown usage is never shown as unused.
//   • The finished report is saved to a file, so it is not lost if the browser stops
//     waiting. Type yes in option 4 to show it without running anything.
//   • Scripts are listed first, then their usage files are read first, so time is not
//     spent on files that match no configured script.
//
// NEW IN v2.2.0
//   • Usage files are matched using ScriptRunner's own list of scripts (the list behind
//     its execution history), so listeners are named automatically.
//   • ScriptRunner keeps a script's usage file after the script is deleted or its ID
//     changes. Files that match no current script are shown as "Left over": no action
//     if they have not run recently, "Check first" if they ran recently or were not read.
//   • If ScriptRunner's list cannot be read, the report works as in v2.1.0.
//   • Behaviours now show usage: ScriptRunner records each script inside a behaviour
//     under its own ID, read from the behaviour's configuration.
//
// NEW IN v2.3.0 (large instances)
//   • A usage file is only written when its script runs. A file not changed for longer
//     than the chosen period (at least 90 days) cannot hold a recent run, so it is judged
//     "no recent run" from its file date when there is no time to open it. Left-over files
//     of that age are never opened.
//   • Files are read newest first, in parallel (READ_THREADS in ADVANCED SETTINGS).
//   • Listing scripts gets its own share of the time limit (finding files no longer uses it up).
//   • Key progress lines are logged at WARN, so they reach atlassian-jira.log.
//
// IF SOMETHING GOES WRONG
//   • Page hangs, goes blank or says "Failed to post update": the run carries on on the
//     server until it finishes. Do not click Run again. Wait for the time limit plus a few
//     minutes, reload the console, paste, type yes in option 4, then click Run once.
//   • "Another run is still in progress": wait the minutes shown, then run once.
//   • Report shows "Problems in this run": each problem lists its fix.
//   • Red error text instead of a report: copy it and send it to your Adaptavist contact.
//
// v1.1.0 (with optional wider Jira readiness checks) is still available as a tag
// on GitHub.
// ═══════════════════════════════════════════════════════════════════════════

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.util.JiraHome
import com.atlassian.jira.project.Project
import com.atlassian.jira.util.BuildUtilsInfo
import com.atlassian.jira.workflow.JiraWorkflow
import com.atlassian.jira.workflow.WorkflowManager
import com.atlassian.jira.workflow.WorkflowSchemeManager
import com.onresolve.jira.behaviours.BehaviourManager
import com.onresolve.scriptrunner.canned.util.OutputFormatter
import com.onresolve.scriptrunner.parameters.annotation.NumberInput
import com.onresolve.scriptrunner.parameters.annotation.ShortTextInput
import com.onresolve.scriptrunner.scheduled.ScheduledScriptJobManager
import com.onresolve.scriptrunner.scheduled.model.AbstractScheduledJobCommand
import com.opensymphony.workflow.loader.ActionDescriptor
import com.opensymphony.workflow.loader.ConditionalResultDescriptor
import com.opensymphony.workflow.loader.ConditionsDescriptor
import org.rrd4j.ConsolFun
import org.rrd4j.core.FetchData
import org.rrd4j.core.RrdDb

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files
import java.nio.file.StandardCopyOption


// ╔══════════════════════════════════════════════════════════════════════╗
// ║  FORM — appears as fields above the Script Console when you paste    ║
// ║  this script (ScriptRunner Dynamic Forms). Every field is optional:  ║
// ║  leave it blank and the default shown in its description is used.   ║
// ╚══════════════════════════════════════════════════════════════════════╝
@NumberInput(label = '1. Maximum run time (seconds)',
        description = 'Why we ask: on large instances the report can take a while, and browsers or proxies often stop waiting ' +
                '(commonly after about 60 seconds), losing the result. After this many seconds the report stops reading, ' +
                'shows what it has, and marks anything it did not read as "Not read" so you can re-run. ' +
                'Effect: lower = safer but may leave scripts unread; higher = more complete but riskier. Blank = 50. Maximum 300 unless raised in the script\'s advanced settings (TIME_BUDGET_MAX_SEC). ' +
                'Only go above 60 if your proxy or load balancer waits longer. If the browser gives up first, the run still finishes and saves its report: use option 4 to see it.')
Integer FORM_TIME_BUDGET

@NumberInput(label = '2. Treat a ScriptRunner script as unused after (days)',
        description = 'Why we ask: scripts that have not run recently are worth considering for archiving or deleting ' +
                'instead of converting, which can shrink the migration. Scripts with no recorded run in this many days are listed under Archive or delete. ' +
                'Effect: shorter = more scripts flagged as unused; longer = fewer. Widen it (e.g. 365) to catch yearly scripts. Blank = 90.')
Integer FORM_DORMANT_DAYS

@ShortTextInput(label = '3. Script Listener names (optional)',
        description = 'Why we ask: since v2.2.0 listeners are named automatically from ScriptRunner\'s own list. ' +
                'Only needed if the report says that list could not be read; names entered here are used first. ' +
                'Format: uuid=Name; uuid=Name. Find a UUID in SR admin → Listeners → Edit → the last part of the browser URL. Blank = skip.')
String FORM_LISTENER_NAMES

@ShortTextInput(label = '4. Show the last saved report instead of running (optional)',
        description = 'Why we ask: if the page timed out or showed an error before the report appeared, the run usually still finished on the server and saved its report. ' +
                'Effect: type yes to show that saved report straight away, without running anything. Blank = run normally.')
String FORM_SHOW_LAST

def  SR_DORMANT_DAYS = (FORM_DORMANT_DAYS && FORM_DORMANT_DAYS > 0) ? FORM_DORMANT_DAYS : 90
Map<String, String> SR_LISTENER_NAMES = [:]
(FORM_LISTENER_NAMES ?: '').split(/[;\n]/).each { String pair ->
    def parts = pair.split('=', 2)
    if (parts.size() == 2 && parts[0].trim()) SR_LISTENER_NAMES[parts[0].trim()] = parts[1].trim()
}

// ╔══════════════════════════════════════════════════════════════════════╗
// ║  ADVANCED SETTINGS — most users never need to change these           ║
// ╚══════════════════════════════════════════════════════════════════════╝
// Upper limit for form option 1 (maximum run time). Not a Jira or ScriptRunner limit:
// the report is returned in the same browser request, so it is lost once the browser,
// proxy or load balancer stops waiting (often about 60 seconds), while the run keeps
// using a Jira request thread. Raise only if you know your proxy waits longer.
// Raising it also lengthens how long the one-run-at-a-time lock waits after a cut-short run.
// Since v2.1.0 the finished report is also saved to a file (form option 4 shows it), so a
// report lost by the browser can still be seen once the run finishes.
int  TIME_BUDGET_MAX_SEC = 300
int  TIME_BUDGET_SEC = (FORM_TIME_BUDGET && FORM_TIME_BUDGET > 0) ? Math.min(FORM_TIME_BUDGET as int, TIME_BUDGET_MAX_SEC) : 50
boolean TIME_BUDGET_CAPPED = FORM_TIME_BUDGET && FORM_TIME_BUDGET > TIME_BUDGET_MAX_SEC

// v2.3.0: usage files are read by this many parallel workers. 1 = one at a time (as before v2.3.0).
// More workers finish sooner on large instances but put more read load on the shared home at once.
int  READ_THREADS = 4

// Long tables show this many rows, then "…and N more", so the page stays fast and scannable.
// Every script is always in the "Copy the full list" box.
def MAX_ROWS = 50

// ── Time budget helpers ─────────────────────────────────────────────────────
// The budget is checked inside the long loops (reading usage files, listing
// workflow scripts and script fields). A single slow API call cannot be
// interrupted once it has started.
final long RUN_START = System.currentTimeMillis()
def elapsedSec  = { -> ((System.currentTimeMillis() - RUN_START) / 1000L) as long }
def overBudget  = { -> elapsedSec() >= TIME_BUDGET_SEC }
// v2.1.0: the time limit covers the whole run. Listing scripts gets at most 30% of it, and
// reading stops early enough to leave time to build and save the report.
long LIST_BUDGET_SEC   = Math.max(10L, (long) (TIME_BUDGET_SEC * 0.3d))
long BUILD_RESERVE_SEC = Math.min(30L, Math.max(5L, (long) (TIME_BUDGET_SEC * 0.1d)))
long srListStartSec = 0L   // v2.3.0: set when listing starts, so finding files does not use up the listing share
def overListBudget = { -> elapsedSec() - srListStartSec >= LIST_BUDGET_SEC }
def overReadBudget = { -> elapsedSec() >= TIME_BUDGET_SEC - BUILD_RESERVE_SEC }
// Time per step, shown in the report and the log (helps compare runs and instances).
Map<String, Double> srPhaseSec = [:]
long srLapStart = RUN_START
def lap = { String step -> long now = System.currentTimeMillis(); srPhaseSec[step] = (now - srLapStart) / 1000d; srLapStart = now }
def phasesText = { -> srPhaseSec.collect { String k, Double v -> "${k} ${String.format('%.1f', v)}s" }.join('  |  ') }

// ── One run at a time (per node) ─────────────────────────────────────────────
// Closing or reloading the browser does NOT stop a run: it carries on on the server.
// Clicking Run again would start a second run alongside it, doubling the load.
// A JVM system property marks a run in progress on this node. It is not Jira data or
// configuration, and it clears itself at the end of the run, or after the time below
// if a run was cut short.
final String RUN_LOCK_KEY = 'adaptavist.srMigrationReadiness.runningSince'
final long   RUN_LOCK_MAX_AGE_MS = (TIME_BUDGET_MAX_SEC + 300) * 1000L   // the cap plus 5 minutes (10 minutes by default)
String prevRunStart = System.getProperty(RUN_LOCK_KEY)
boolean runInProgress = prevRunStart?.isLong() && (RUN_START - prevRunStart.toLong()) < RUN_LOCK_MAX_AGE_MS

// ── Saved report (v2.1.0) ────────────────────────────────────────────────────
// Each finished run saves its report to one file in the Jira home, overwritten each time.
// If the browser stops waiting, the run still finishes on the server, and option 4 shows it.
File srJiraHome   = ComponentAccessor.getComponent(JiraHome).home
File SAVED_REPORT = new File(srJiraHome, 'scriptrunner/migration-readiness/last-report.html')
def showSaved = { String note ->
    if (!SAVED_REPORT.isFile()) return null
    String saved = SAVED_REPORT.getText('UTF-8')
    String when  = new Date(SAVED_REPORT.lastModified()).format('yyyy-MM-dd HH:mm:ss z')
    return OutputFormatter.markupBuilder {
        div(style: 'font-family:Arial,sans-serif;border:2px solid #0052CC;background:#F4F8FF;border-radius:6px;padding:12px 16px;max-width:1100px;margin:0 auto 12px') {
            div(style: 'font-size:18px;font-weight:bold;margin-bottom:6px') { mkp.yield "Saved report from ${when}".toString() }
            p(style: 'margin:4px 0') { mkp.yield note }
            p(style: 'margin:4px 0;font-size:12px;color:#5E6C84') { mkp.yield "Shown from ${SAVED_REPORT}. Nothing was run. Clear option 4 and click Run for a new report.".toString() }
        }
        mkp.yieldUnescaped(saved)
    }
}
if ((FORM_SHOW_LAST ?: '').trim().toLowerCase(Locale.ENGLISH) in ['yes', 'y', 'true', '1']) {
    String note = runInProgress
            ? "A run started at ${new Date(prevRunStart.toLong()).format('HH:mm:ss')} is still going on this node. This saved report is from an EARLIER run, not the one in progress.".toString()
            : 'No run is in progress on this node.'
    def savedHtml = showSaved(note)
    if (savedHtml) return savedHtml
    return OutputFormatter.markupBuilder {
        div(style: 'font-family:Arial,sans-serif;border:2px solid #FF8B00;background:#FFFAE6;border-radius:6px;padding:12px 16px;max-width:760px') {
            mkp.yield "No saved report was found (${SAVED_REPORT}). Clear option 4 and click Run once.".toString()
        }
    }
}

if (runInProgress) {
    long waitMin = Math.max(1L, (long) Math.ceil((RUN_LOCK_MAX_AGE_MS - (RUN_START - prevRunStart.toLong())) / 60000d))
    log.warn "Another run of this report is still in progress on this node (started ${new Date(prevRunStart.toLong())}). Not starting a second one."
    def lockMsg = OutputFormatter.markupBuilder {
        div(style: 'font-family:Arial,sans-serif;border:2px solid #FF8B00;background:#FFFAE6;border-radius:6px;padding:12px 16px;max-width:760px;margin-bottom:12px') {
            div(style: 'font-size:15px;font-weight:bold;margin-bottom:6px') { mkp.yield 'Another run of this report is still in progress on this Jira node' }
            p(style: 'margin:4px 0') { mkp.yield "It started at ${new Date(prevRunStart.toLong()).format('HH:mm:ss')}. To protect Jira, a second run was not started." }
            ol(style: 'margin:4px 0;padding-left:20px') {
                li { mkp.yield "Wait about ${waitMin} minute(s). An earlier run keeps going on the server even if its page was closed or timed out." }
                li { mkp.yield 'Then type yes in option 4 and click Run to see its saved report. Nothing new is run.' }
            }
        }
    }
    def savedHtml = showSaved("A run started at ${new Date(prevRunStart.toLong()).format('HH:mm:ss')} is still going. This saved report is from an EARLIER run, not the one in progress.".toString())
    return savedHtml ? (lockMsg.toString() + savedHtml.toString()) : lockMsg
}
System.setProperty(RUN_LOCK_KEY, RUN_START.toString())

// ── Service lookups ──────────────────────────────────────────────────────────
def appProps        = ComponentAccessor.getApplicationProperties()
def buildInfo       = ComponentAccessor.getComponent(BuildUtilsInfo)
def projectManager  = ComponentAccessor.getProjectManager()
def workflowManager = ComponentAccessor.getComponent(WorkflowManager)
def wfSchemeManager = ComponentAccessor.getComponent(WorkflowSchemeManager)
def pluginAccessor  = ComponentAccessor.getPluginAccessor()

// ── Progress logging helpers ─────────────────────────────────────────────────
def warn     = { String msg -> log.warn "    ⚠ ${msg}" }
def done     = { String msg -> log.info "    ✔ ${msg}" }

log.info "============================================================"
log.warn "  SCRIPTRUNNER USAGE REPORT — BEST-EFFORT POC (v2.3.0)"
log.info "  ${new Date().format('yyyy-MM-dd HH:mm:ss z')}"
log.info "============================================================"

String jiraVersion = '(unknown)'
String buildNumber = '(unknown)'
String baseUrl     = ''
try {
    jiraVersion = buildInfo.getVersion() as String
    buildNumber = buildInfo.getCurrentBuildNumber() as String
    baseUrl     = ((appProps.getString('jira.baseurl') ?: appProps.jiraBaseUrl) ?: '') as String
} catch (Throwable ignored) {}

// ScriptRunner usage (RRD)
// Each ScriptRunner script that has executed has a file:
//   $JIRA_HOME/scriptrunner/rrd/{nodeId}/{scriptId}.rrd4j   (node folders)
//   $JIRA_HOME/scriptrunner/rrd/{scriptId}.rrd4j            (flat layout)
// Each file keeps rows of 5 min (~2 days), 30 min, 2 h and 1 day (~2 years).
// Run counts are NOT shown: ScriptRunner writes its tally every 5 minutes but off the
// step boundaries, and rrd4j time-weights each tally across the rows it overlaps, so no
// row type sums to the true number of runs. What IS reliable (see readRrd):
//   - on how many UTC days each script ran (any non-blank row = at least one run)
//   - the last day it ran
// As seen on Jira 10.3.24 / SR 9.30.0: step 300s, data source 'count' (GAUGE),
// blank rows = no run (no zeros are written).
def srRows      = [] as List<Map>   // [type, name, where, key, d30, d90, dX, lastRun, status]
final String SR_LEFTOVER_TYPE = 'Left over: no current script'   // v2.2.0
List<String> srWarnings = Collections.synchronizedList(new ArrayList<String>())
def srNodeNames = [] as List<String>
int srRrdFiles  = 0                              // usage files opened and read
int srAgeJudged = 0                              // v2.3.0: usage IDs judged from file date only (not opened)
Map srRrdMeta = [:]                              // detected value type and step sizes of the usage files
Set<String> srMissingDs = ConcurrentHashMap.newKeySet()   // usage files without a 'count' data source
Set<String> srNotReadKeys = [] as Set<String>   // RRD files skipped because the time budget ran out
boolean srRrdUnavailable = false                 // RRD folder missing or unreadable
boolean srBudgetHit = false
List<String> srNodesMissing = []                 // cluster nodes Jira knows about with no usage folder
List<String> srNodesOffline = []                 // cluster nodes Jira lists as offline (not required to have a folder)
Set<String> srUnreadableKeys = [] as Set<String> // usage files that exist but could not be read: usage unknown, never unused
Set<String> srListCut = [] as Set<String>        // script lists cut short by the time limit
boolean srFailed = false                         // the ScriptRunner check itself failed part-way
String  srError  = null                          // error text when srFailed
boolean srClusterCheckFailed = false             // the cluster node check could not run: usage on other nodes unknown
int srRrdFilesFound    = 0                       // v2.1.0: usage files found on disk (read or not)
int srMatchedNotRead   = 0                       // configured scripts with a usage file not read (time limit)
int srUnmatchedNotRead = 0                       // usage file IDs matching no configured script, not read (time limit)
Map<String, Map> srMap = null                    // v2.2.0: ScriptRunner's own list: functionId -> [cat, name]; null if it could not be read
String srMapError = null                         // why ScriptRunner's own list could not be read
try {
    log.info "[1] ScriptRunner usage (RRD)"

    long nowSec        = (System.currentTimeMillis() / 1000L) as long
    long DAY           = 86_400L
    long from30        = nowSec - 30L * DAY
    long from90        = nowSec - 90L * DAY
    long todayKey      = Math.floorDiv(nowSec, DAY)          // today's UTC day number
    // A UTC day number to a date. Days and dates in this section are UTC calendar days.
    def fmtDay   = { long dayKey -> new Date(dayKey * DAY * 1000L).format('yyyy-MM-dd', TimeZone.getTimeZone('UTC')) }
    // Days-it-ran in the last n days (today and the n-1 days before it).
    def daysIn   = { Set<Long> days, long n -> days.count { Long k -> k > todayKey - n } as int }

    // ── Read every RRD file on every node, combining nodes ──
    Map<String, Map> rrd = [:]
    // What is read from each file (see the note above srRows):
    //   finest rows (one per primary step, kept ~2 days), ending at the file's last write:
    //     holds the latest run day, which the daily rows only get once the file is written again.
    //   daily rows (kept ~2 years).
    //   Any non-blank row with a value above 0 means "ran at least once in that row".
    //   Values are never turned into counts (see the note above srRows).
    // rrd4j timestamps mark the END of each row, so a row ending at t covers (t - step, t]
    // and belongs to UTC day floor((t - 1) / 86400).
    def readRrd = { File f ->
        RrdDb db = null
        try {
            db = RrdDb.getBuilder().setPath(f.absolutePath).readOnly().build()
            if (!db.containsDs('count')) { srMissingDs << f.name; return null }
            long dbStep   = db.getHeader().getStep()
            String dsType = db.getDatasource('count').getType().toString()
            // Finest rows are read for the 2 days before the file's LAST WRITE, not before now.
            // rrd4j only files (consolidates) rows when the file is written, and ScriptRunner only
            // writes when something runs. So a script's latest run day is missing from the daily
            // rows until it runs again, and only the finest rows ending at the last write hold it.
            long lastWrite  = db.getLastUpdateTime()
            FetchData fine  = db.createFetchRequest(ConsolFun.AVERAGE, lastWrite - 2L * DAY, lastWrite, dbStep).fetchData()
            FetchData daily = db.createFetchRequest(ConsolFun.AVERAGE, nowSec - 730L * DAY, nowSec, DAY).fetchData()
            boolean dailyOk = daily.getStep() <= DAY
            synchronized (srMissingDs) {
                if (!srRrdMeta) srRrdMeta = [dsType: dsType, dbStep: dbStep, fineStep: fine.getStep(), dailyStep: daily.getStep(),
                                             dailyOk: dailyOk]
            }
            Set<Long> days = [] as Set<Long>
            long last = 0L                                  // end time of the latest row with a run
            long[] fts = fine.getTimestamps(); double[] fv = fine.getValues('count'); long fStep = fine.getStep()
            for (int i = 0; i < fts.length; i++) {
                double c = fv[i]
                if (Double.isNaN(c) || c <= 0d || fts[i] > nowSec) continue
                if (fStep <= DAY) days << Math.floorDiv(fts[i] - 1L, DAY)
                if (fts[i] > last) last = fts[i]
            }
            if (dailyOk) {
                long[] dts = daily.getTimestamps(); double[] dv = daily.getValues('count')
                for (int i = 0; i < dts.length; i++) {
                    double c = dv[i]
                    if (Double.isNaN(c) || c <= 0d || dts[i] > nowSec) continue
                    days << Math.floorDiv(dts[i] - 1L, DAY)
                    if (dts[i] > last) last = dts[i]
                }
            }
            return [days: days, last: last]
        } catch (Exception e) {
            srWarnings << "Could not read ${f.name}: ${e.message}".toString()
            return null
        } finally {
            try { db?.close() } catch (ignored) {}
        }
    }

    // ── Step 1 (v2.1.0): find the usage files. Folder listing only: no file is opened. ──
    // Scripts are listed next and matched against these names, so reading (the slow part)
    // starts with the files that belong to configured scripts.
    Map<String, List<File>> rrdFilesByKey = [:]
    Map<String, Long> rrdNewestByKey = [:]           // v2.3.0: newest file date (ms) per usage ID, across nodes
    try {
        File rrdRoot = new File(srJiraHome, 'scriptrunner/rrd')
        if (!rrdRoot.exists()) {
            srRrdUnavailable = true
            srWarnings << "RRD directory not found (${rrdRoot}). Usage unavailable, so no script is marked as unused.".toString()
        } else {
            List<File> dirs = (rrdRoot.listFiles()?.findAll { it.isDirectory() }?.sort { it.name } ?: []) as List<File>
            if (rrdRoot.listFiles()?.any { it.isFile() && it.name.endsWith('.rrd4j') }) dirs.add(0, rrdRoot)
            dirs.each { File dir ->
                srNodeNames << (dir == rrdRoot ? '(flat layout)' : dir.name)
                // v2.3.0: one attribute read per file gives both "is a file" and its last-changed date.
                DirectoryStream<Path> ds = Files.newDirectoryStream(dir.toPath(), '*.rrd4j')
                try {
                    for (Path pth : ds) {
                        BasicFileAttributes a
                        try { a = Files.readAttributes(pth, BasicFileAttributes) } catch (Exception ignored) { continue }
                        if (!a.isRegularFile()) continue
                        String fname = pth.fileName.toString()
                        String key = fname.substring(0, fname.length() - '.rrd4j'.length())
                        List<File> fs = rrdFilesByKey[key]
                        if (fs == null) { fs = [] as List<File>; rrdFilesByKey[key] = fs }
                        fs << pth.toFile()
                        long mt = a.lastModifiedTime().toMillis()
                        Long prev = rrdNewestByKey[key]
                        if (prev == null || mt > prev) rrdNewestByKey[key] = mt
                    }
                } finally {
                    try { ds.close() } catch (ignored) {}
                }
            }
            // Cluster check: every node Jira knows about should have a usage folder. If any is missing,
            // usage on that node is unknown, so no script is judged unused (see addRow).
            // ScriptRunner cleans up node IDs before using them as folder names, so
            // names are compared after removing everything except letters and digits.
            // Offline nodes are not required to have a folder: any folder they left is still read.
            try {
                def cm = ComponentAccessor.getComponent(com.atlassian.jira.cluster.ClusterManager)
                if (cm?.isClustered()) {
                    def normNode = { String n -> (n ?: '').toLowerCase(Locale.ENGLISH).replaceAll(/[^a-z0-9]/, '') }
                    Set<String> folders = srNodeNames.collect { normNode(it as String) } as Set<String>
                    def nodes = (cm.getAllNodes() ?: []) as Collection
                    nodes.each { node ->
                        String id = node?.getNodeId() as String
                        if (!id) return
                        String state = ''
                        try { state = (node.getAt('state') ?: '').toString().toUpperCase(Locale.ENGLISH) } catch (ignored) {}
                        if (state.contains('OFFLINE')) { srNodesOffline << id; return }
                        if (!folders.contains(normNode(id))) srNodesMissing << id
                    }
                    srNodesMissing = srNodesMissing.sort()
                    if (srNodesMissing) srWarnings << "No usage folder found for cluster node(s): ${srNodesMissing.join(', ')}. Usage on those nodes is unknown, so no script is marked as unused. A node that has never run a script also has no folder.".toString()
                }
            } catch (Exception e) {
                // Fail safe: if the node list cannot be checked, a missing node folder would go unnoticed,
                // so no script is judged unused.
                srClusterCheckFailed = true
                srWarnings << "Could not check cluster nodes: ${e.message}. No script is marked as unused. Compare \"Nodes read\" with Jira admin → System → Clustering.".toString()
            }
        }
    } catch (Exception e) {
        srRrdUnavailable = true
        srWarnings << "RRD folder could not be listed: ${e.message}. No script is marked as unused.".toString()
    }
    Set<String> rrdKeysOnDisk = rrdFilesByKey.keySet()
    srRrdFilesFound = (rrdFilesByKey.values().collect { List<File> fs -> fs.size() }.sum() ?: 0) as int
    lap('Finding usage files')
    done("Nodes: ${srNodeNames.join(', ') ?: 'none'}  |  Usage files found: ${srRrdFilesFound} (${rrdKeysOnDisk.size()} IDs)")

    // ── Step 1b (v2.2.0): ScriptRunner's own list of scripts, by usage file ID ──
    // The same list behind ScriptRunner's execution history: each current script's usage
    // file ID (functionId) and name. Read-only. Loaded by name, so an older ScriptRunner
    // without it still runs: the report then works as in v2.1.0.
    // ScriptRunner does not delete a usage file when its script is deleted or its ID changes,
    // so a file whose ID is not in this list is left over from a script that no longer exists.
    try {
        ClassLoader cl = this.class.classLoader
        Class cpCls  = Class.forName('com.onresolve.scriptrunner.runner.diag.rrd.CategoryProvider', true, cl)
        Class sbaCls = Class.forName('com.onresolve.spring.StaticBeanAccessor', true, cl)
        def cp = sbaCls.getMethod('getBean', Class).invoke(null, cpCls)
        Map<String, Map> m = [:]
        (cp.getCategories() ?: []).each { cat ->
            String cn = (cat?.getAt('name') ?: '') as String
            (cat?.getAt('items') ?: []).each { item ->
                String id = item?.getAt('functionId') as String
                if (id) m[id] = [cat: cn, name: (item?.getAt('name') ?: '') as String]
            }
        }
        srMap = m
        done("ScriptRunner's own script list: ${m.size()} entries")
    } catch (Throwable e) {
        srMap = null
        srMapError = "${e.class.simpleName}: ${e.message ?: 'no message'}".toString()
        srWarnings << "ScriptRunner's own script list could not be read (${srMapError}). Listeners are not named and unmatched usage files are labelled as in v2.1.0.".toString()
    }
    lap('Reading ScriptRunner list')

    // ── Step 2: list configured scripts. Each one is recorded here and judged in step 4,
    // once its usage has been read. ──
    srListStartSec = elapsedSec()
    Set<String> attributed = [] as Set<String>
    List<Map> srPending = [] as List<Map>
    // keys: v2.2.0, for scripts with several usage files (a behaviour has one per script inside it).
    def addRow = { String type, String name, String where, String key, boolean tracked, List<String> keys = null ->
        if (key) attributed << key
        if (keys) attributed.addAll(keys)
        srPending << [type: type, name: name, where: where, key: key, tracked: tracked, keys: keys]
    }
    def finalizeRow = { Map p ->
        String type = p.type as String; String name = p.name as String; String where = p.where as String
        String key = p.key as String; boolean tracked = p.tracked as boolean
        List<String> ks = (p.keys ?: (key ? [key] : [])) as List<String>
        // Several usage files (v2.2.0, behaviours): days combined, latest run wins.
        Map d = null
        ks.each { String k ->
            Map x = rrd[k]
            if (x == null) return
            d = d == null ? x : [days: ((d.days as Set<Long>) + (x.days as Set<Long>)) as Set<Long>, last: Math.max(d.last as long, x.last as long),
                                 ageBefore: (d.ageBefore || x.ageBefore) ? Math.max((d.ageBefore ?: 0L) as long, (x.ageBefore ?: 0L) as long) : null,
                                 ageOnly: (d.ageOnly && x.ageOnly)]
        }
        boolean ranRecently = d != null && (d.last as long) > 0L &&
                Math.floorDiv((d.last as long) - 1L, DAY) > todayKey - (SR_DORMANT_DAYS as long)
        String status
        if (!tracked)               status = 'NOT TRACKED'
        // v2.1.0: a recent run in any file that WAS read proves the script is in use, even if
        // another node's file for it was not read. Only "unused" needs every file.
        else if (ranRecently)       status = 'ACTIVE'
        // Any unread file for this script (e.g. one node read, another cut off by the time limit)
        // means usage is incomplete, so it is never judged unused.
        else if (srRrdUnavailable || ks.any { String k -> srNotReadKeys.contains(k) || srUnreadableKeys.contains(k) }) status = 'NOT READ'
        // No usage file and a file with no recorded run mean the same thing: no run found.
        else if (d == null || (d.last as long) == 0L) status = 'NEVER RUN'
        else                        status = 'DORMANT'

        // A cluster node's usage folder is missing: "no run found" can't be trusted, so not judged unused.
        if ((srNodesMissing || srClusterCheckFailed) && status in ['DORMANT', 'NEVER RUN']) status = 'NOT READ'
        Set<Long> days  = d ? (d.days as Set<Long>) : ([] as Set<Long>)
        Long lastDay    = d && (d.last as long) > 0L ? Math.floorDiv((d.last as long) - 1L, DAY) : null
        int dXn         = daysIn(days, SR_DORMANT_DAYS as long)
        srRows << [
                type   : type, name: (name ?: '(unnamed)').trim(), where: where ?: '—', key: key ?: (ks ? ks.join(', ') : '—'),
                d30    : d ? "${daysIn(days, 30L)}".toString() : (tracked ? '0' : '—'),
                d90    : d ? "${daysIn(days, 90L)}".toString() : (tracked ? '0' : '—'),
                dX     : d ? "${dXn}".toString() : (tracked ? '0' : '—'),
                lastRun: lastDay != null ? fmtDay(lastDay)
                        : (d?.ageOnly ? "Not in the last ${Math.max(SR_DORMANT_DAYS as int, 90)} days (file last changed ${new Date(d.ageBefore as long).format('yyyy-MM-dd', TimeZone.getTimeZone('UTC'))})".toString()
                        : (tracked ? 'No run recorded' : 'Not tracked')),
                daysAgo: lastDay != null ? (todayKey - lastDay) : null,
                sortDays: dXn,
                status : status,
        ]
    }

    // Scheduled Jobs & Escalation Services
    try {
        def jobMgr = ComponentAccessor.getOSGiComponentInstanceOfType(ScheduledScriptJobManager)
        jobMgr?.load()?.each { AbstractScheduledJobCommand j ->
            boolean esc = j.class.simpleName == 'EscalationServiceCommand'
            addRow(esc ? 'Escalation Service' : 'Scheduled Job', (j.notes ?: j.id) + (j.disabled ? ' (disabled)' : ''),
                    j.disabled ? 'Disabled in SR' : 'Not project-scoped', j.id, true)
        }
    } catch (Exception e) {
        srWarnings << "Scheduled jobs could not be listed: ${e.message}".toString()
    }

    // Workflow Post-Functions
    try {
        Map<String, List<String>> wfToProjects = [:]
        projectManager.getProjectObjects().each { Project proj ->
            if (overListBudget()) { srListCut << 'workflow scripts'; return }
            wfSchemeManager.getWorkflowMap(proj).values().toUnique().each { String wfName ->
                wfToProjects.get(wfName, []) << proj.key
            }
        }
        Set<String> seen = [] as Set<String>
        workflowManager.getWorkflows().each { JiraWorkflow wf ->
            if (overListBudget()) { srListCut << 'workflow scripts'; return }
            List<String> keys = (wfToProjects[wf.name] ?: []).unique().sort()
            String where = keys ? keys.join(', ') : (wf.active ? 'No projects' : 'Workflow inactive')
            // Adds one ScriptRunner workflow item. Conditions are only marked as having usage
            // data if a usage file exists for them; otherwise they show as "no usage data"
            // (never as unused), because it is not confirmed that ScriptRunner records them.
            // Validators ARE recorded (a usage file appears on first run), so a missing file
            // means "no run recorded".
            def addWfItem = { String type, def descriptor, String actionName, boolean mustHaveFile ->
                Map args   = (descriptor?.getAt('args') ?: [:]) as Map   // getAt: property read the type checker accepts
                String cls = (args['class.name'] ?: '') as String
                if (!cls.contains('onresolve') && !cls.contains('scriptrunner')) return
                String fid = args['FIELD_FUNCTION_ID'] as String
                if (fid && !seen.add(fid)) return
                // v2.1.0: checked against usage-file names found in step 1, so it works before any file is read,
                // and a condition whose file was skipped by the time limit shows "Not read", not "No data".
                boolean tracked = fid != null && (!mustHaveFile || rrdKeysOnDisk.contains(fid))
                addRow(type, "${wf.name} → ${actionName}".toString(), where, fid, tracked)
            }
            Closure walkConditions
            walkConditions = { ConditionsDescriptor cd, String actionName ->
                (cd?.conditions ?: []).each { Object c ->
                    if (c instanceof ConditionsDescriptor) walkConditions((ConditionsDescriptor) c, actionName)
                    else addWfItem('Workflow Condition', c, actionName, true)
                }
            }
            wf.allActions?.each { ActionDescriptor action ->
                try { (action.unconditionalResult?.postFunctions ?: []).each { addWfItem('Workflow Post-Function', it, action.name, false) } } catch (ignored) {}
                try { (action.conditionalResults ?: []).each { Object r -> ((r as ConditionalResultDescriptor)?.postFunctions ?: []).each { addWfItem('Workflow Post-Function', it, action.name, false) } } } catch (ignored) {}
                try { (action.validators ?: []).each { addWfItem('Workflow Validator', it, action.name, false) } } catch (ignored) {}
                try { walkConditions(action.restriction?.conditionsDescriptor, action.name) } catch (ignored) {}
            }
        }
    } catch (Exception e) {
        srWarnings << "Workflow scripts could not be listed: ${e.message}".toString()
    }

    // Script Fields (RRD key = field configuration scheme ID, NOT the custom field ID)
    try {
        ComponentAccessor.getCustomFieldManager().getCustomFieldObjects()
                .findAll { cf ->
                    String tk = cf.customFieldType?.key ?: ''
                    (tk.contains('onresolve') || tk.contains('scriptrunner')) && !tk.contains('jqlFunctions')
                }
                .each { cf ->
                    if (overListBudget()) { srListCut << 'script fields'; return }
                    cf.getConfigurationSchemes().each { scheme ->
                        String where = scheme.isGlobal() ? 'All projects (global)'
                                : (scheme.getAssociatedProjectObjects()*.key.sort().join(', ') ?: 'No projects')
                        addRow('Script Field', cf.name, where, scheme.id?.toString(), true)
                    }
                }
    } catch (Exception e) {
        srWarnings << "Script fields could not be listed: ${e.message}".toString()
    }

    // Behaviours (v2.2.0): ScriptRunner records usage per script INSIDE a behaviour (its
    // initialiser and field scripts), under that script's own ID, not the behaviour's ID.
    // Those IDs are read from the behaviour's configuration. A behaviour counts as run when any
    // of its scripts ran. If none of its scripts has a usage file, it shows as "No usage data",
    // never as unused, because not every behaviour script is confirmed to record usage.
    Set<String> srBehaviourKeys = [] as Set<String>
    try {
        String B_UUID_RE = /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/
        def bm = ComponentAccessor.getOSGiComponentInstanceOfType(BehaviourManager)
        (bm?.getAllConfigs() as Map)?.each { id, state ->
            List<String> inner = (((state?.toString() ?: '') =~ B_UUID_RE).collect { it as String }.unique() - [id?.toString()]) as List<String>
            boolean hasFile = inner.any { String u -> rrdKeysOnDisk.contains(u) }
            srBehaviourKeys.addAll(inner)
            addRow('Behaviour', (state?.getAt('name') ?: id) as String,
                    state?.getAt('disabled') ? 'Disabled in SR' : (hasFile ? 'Where the behaviour is mapped' : 'No usage recorded for its scripts'),
                    null, hasFile, inner)
        }
    } catch (Throwable e) {
        srWarnings << "Behaviours could not be listed: ${e.message}".toString()
    }

    // v2.2.0 sanity check: ScriptRunner's own list must contain most of the scripts listed above
    // that have a usage file. If it does not (empty, or a different format on this version), it is
    // not trusted, so no usage file is wrongly shown as left over. The report then works as in v2.1.0.
    if (srMap != null) {
        Set<String> ownKeys = attributed.findAll { String k -> rrdKeysOnDisk.contains(k) && !srBehaviourKeys.contains(k) } as Set<String>
        int inMap = ownKeys.count { String k -> srMap.containsKey(k) } as int
        if ((srMap.isEmpty() && rrdKeysOnDisk) || (ownKeys.size() >= 5 && inMap < ownKeys.size() * 0.5d)) {
            srMapError = "it matched only ${inMap} of ${ownKeys.size()} scripts this report found".toString()
            srWarnings << "ScriptRunner's own script list was not used (${srMapError}). Listeners are not named and unmatched usage files are labelled as in v2.1.0.".toString()
            srMap = null
        }
    }

    // v2.2.0: from ScriptRunner's own list. Listeners are listed whether or not they ran.
    // REST endpoints and JQL functions only once called (as before), so built-in JQL functions
    // that were never used are not listed. Any other current script with a usage file that the
    // lists above missed (e.g. cut short by the time limit) is added under its own type.
    if (srMap != null) {
        Map<String, String> SR_CAT_TYPE = ['Script Listeners': 'Script Listener', 'REST Endpoints': 'REST Endpoint',
                                           'JQL Functions': 'JQL Function', 'Jobs': 'Scheduled Job',
                                           'Script Fields': 'Script Field', 'Workflow Functions': 'Workflow Function']
        srMap.each { String id, Map e ->
            if (attributed.contains(id)) return
            String cat  = e.cat as String
            String type = SR_CAT_TYPE[cat] ?: (cat ?: 'ScriptRunner script')
            String name = (SR_LISTENER_NAMES[id] ?: e.name ?: id) as String
            if (cat == 'Script Listeners') {
                addRow(type, name, 'Scope not listed for listeners', id, true)
            } else if (rrdKeysOnDisk.contains(id)) {
                addRow(type, name, cat in ['REST Endpoints', 'JQL Functions'] ? 'Not project-scoped' : 'Found in ScriptRunner\'s execution history', id, true)
            }
        }
    }

    lap('Listing scripts')

    // ── Step 3 (v2.1.0): read usage files. Scripts first: configured scripts, named listeners,
    // and REST endpoints and JQL functions (which are known only by their file name).
    // IDs that match no script (unnamed UUIDs, numbers, hashes) only if time remains. ──
    String UUID_RE = /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/
    String REST_RE = /(?i)(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)-.+/
    String HASH_RE = /[0-9a-fA-F]{40}/
    // v2.2.0: with ScriptRunner's own list, a file is a script's file only if its ID is a current
    // script. Without the list, the v2.1.0 name rules are used.
    def isScriptKey = { String k ->
        if (attributed.contains(k) || SR_LISTENER_NAMES.containsKey(k)) return true
        if (srMap != null) return false
        (k ==~ REST_RE) || !((k ==~ UUID_RE) || (k ==~ /\d+/) || (k ==~ HASH_RE))
    }
    List<String> matchedKeys   = rrdKeysOnDisk.findAll { String k -> isScriptKey(k) }.sort()
    List<String> unmatchedKeys = rrdKeysOnDisk.findAll { String k -> !isScriptKey(k) }.sort()
    // ── v2.3.0 reading plan ──
    // A usage file is only written when its script runs, so a file whose last change is older
    // than the window (the chosen period, at least 90 days, plus one day for clock differences)
    // cannot hold a run inside it. Such IDs are judged "no recent run" from the file date if
    // there is no time to open them; left-over files of that age are never opened.
    // Order: scripts with a recent file (newest first), then scripts with only old files (for
    // exact last-run dates), then left-over files with a recent file.
    long ageWindowDays = Math.max(SR_DORMANT_DAYS as long, 90L)
    long oldBeforeMs   = (nowSec - (ageWindowDays + 1L) * DAY) * 1000L
    def isOld = { String k -> Long mt = rrdNewestByKey[k]; mt != null && mt < oldBeforeMs }
    def newestFirst = { List<String> ks -> ks.sort { String a, String b -> ((rrdNewestByKey[b] ?: 0L) <=> (rrdNewestByKey[a] ?: 0L)) ?: (a <=> b) } }
    List<String> plan = []
    plan.addAll(newestFirst(matchedKeys.findAll { !isOld(it) }))
    plan.addAll(newestFirst(matchedKeys.findAll { isOld(it) }))
    plan.addAll(newestFirst(unmatchedKeys.findAll { !isOld(it) }))
    Set<String> oldLeftoverKeys = unmatchedKeys.findAll { isOld(it) } as Set<String>

    // Files to read, in plan order. Workers take the next file until the reading time is used up.
    ConcurrentLinkedQueue<List> queue = new ConcurrentLinkedQueue<List>()
    plan.each { String k -> (rrdFilesByKey[k] ?: []).each { File f -> queue.add([k, f]) } }
    Map<String, Map> fileResults = new ConcurrentHashMap<String, Map>()   // file path -> result, or [failed: true]
    AtomicInteger filesRead = new AtomicInteger(0)
    AtomicBoolean budgetHit = new AtomicBoolean(false)
    int workers = Math.max(1, Math.min(READ_THREADS, 8))
    ExecutorService pool = Executors.newFixedThreadPool(workers, { Runnable r ->
        Thread t = new Thread(r, 'sr-usage-report-reader'); t.daemon = true; t
    } as ThreadFactory)
    try {
        workers.times {
            pool.submit({
                while (true) {
                    if (overReadBudget()) { if (!queue.isEmpty()) budgetHit.set(true); break }
                    List item = queue.poll()
                    if (item == null) break
                    File f = item[1] as File
                    Map r = null
                    try { r = readRrd(f) as Map } catch (Throwable t) { srWarnings << "Could not read ${f.name}: ${t.message}".toString() }
                    filesRead.incrementAndGet()
                    fileResults[f.absolutePath] = r != null ? r : [failed: true]
                }
            } as Runnable)
        }
        pool.shutdown()
        // Workers stop themselves at the reading limit; wait a little past it for files in progress.
        long waitSec = Math.max(5L, (TIME_BUDGET_SEC as long) - BUILD_RESERVE_SEC - elapsedSec() + 20L)
        if (!pool.awaitTermination(waitSec, TimeUnit.SECONDS)) {
            budgetHit.set(true)
            srWarnings << 'Some usage files were still being read at the time limit and were left out.'
        }
    } finally {
        pool.shutdownNow()
    }
    srRrdFiles  = filesRead.get()
    srBudgetHit = budgetHit.get()

    // Combine per ID (single thread). A day counts once, whichever node ran it.
    rrdFilesByKey.each { String key, List<File> fs ->
        boolean anyUnread = false, anyFailed = false
        Map comb = null
        fs.each { File f ->
            Map r = fileResults[f.absolutePath]
            if (r == null) { anyUnread = true; return }
            if (r.failed) { anyFailed = true; return }
            comb = comb == null ? r : [days: ((comb.days as Set<Long>) + (r.days as Set<Long>)) as Set<Long>,
                                       last: Math.max(comb.last as long, r.last as long)]
        }
        // Not opened (or not every node's file opened), but every file is older than the window:
        // no run inside the window. A recent run in a file that WAS read still wins.
        boolean ranInWindow = comb != null && (comb.last as long) > 0L &&
                Math.floorDiv((comb.last as long) - 1L, DAY) > todayKey - (SR_DORMANT_DAYS as long)
        if ((anyUnread || anyFailed) && !ranInWindow && isOld(key)) {
            rrd[key] = comb != null && (comb.last as long) > 0L
                    ? (comb + [ageBefore: rrdNewestByKey[key]])
                    : [days: ([] as Set<Long>), last: 0L, ageBefore: rrdNewestByKey[key], ageOnly: true]
            srAgeJudged++
            return
        }
        if (comb != null) rrd[key] = comb
        if (anyFailed) srUnreadableKeys << key
        if (anyUnread) srNotReadKeys << key
    }
    srMatchedNotRead   = matchedKeys.count { String k -> srNotReadKeys.contains(k) } as int
    srUnmatchedNotRead = unmatchedKeys.count { String k -> srNotReadKeys.contains(k) } as int
    if (srMatchedNotRead) srWarnings << "Time limit reached: usage files for ${srMatchedNotRead} script(s) were not read. Those scripts are shown as \"Not read\", never as unused. Re-run with a larger time limit.".toString()
    if (srUnmatchedNotRead) srWarnings << "${srUnmatchedNotRead} usage file ID(s) that match no configured script were not read (time limit). They are listed under \"Check first\" as not read, never as unused.".toString()
    if (srUnreadableKeys) srWarnings << "${srUnreadableKeys.size()} usage file(s) could not be read. Those scripts are shown as \"Not read\", never as unused.".toString()
    if (srMissingDs) srWarnings << "${srMissingDs.size()} usage file(s) had no 'count' data source and were skipped (e.g. ${srMissingDs.take(3).join(', ')}). The file format may differ on this ScriptRunner version.".toString()
    lap('Reading usage files')
    log.warn "  Usage files read: ${srRrdFiles} of ${srRrdFilesFound} (${workers} worker(s)) | IDs judged from file date: ${srAgeJudged} | Value type: ${srRrdMeta.dsType ?: 'n/a'}"

    // Everything left on disk: REST endpoints, JQL functions, listeners, deleted scripts
    // Every usage file found is listed, read or not. Unread ones show as "Not read".
    (rrdKeysOnDisk - attributed).sort().each { String k ->
        if (srMap != null) {
            // v2.2.0: no current ScriptRunner script has this ID.
            if (SR_LISTENER_NAMES.containsKey(k)) addRow('Script Listener', SR_LISTENER_NAMES[k], 'Scope not listed for listeners', k, true)
            else addRow(SR_LEFTOVER_TYPE, k, 'No current ScriptRunner script found with this ID: most likely left over from a deleted or changed script', k, true)
        } else if (k ==~ REST_RE) {
            addRow('REST Endpoint', k, 'Not project-scoped', k, true)
        } else if (k ==~ UUID_RE) {
            if (SR_LISTENER_NAMES.containsKey(k)) addRow('Script Listener', SR_LISTENER_NAMES[k], 'Scope not listed for listeners', k, true)
            else addRow('Not matched to a configured script', k, 'Could be a listener, another ScriptRunner feature, or a deleted script', k, true)
        } else if (k ==~ /\d+/) {
            addRow('Not matched to a configured script', k, 'Numeric ID: could be a deleted script field', k, true)
        } else if (k ==~ HASH_RE) {
            // v2.1.0: 40-character hash IDs were labelled JQL Function; what they are is not confirmed.
            addRow('Not matched to a configured script', k, 'Hash-style ID: could be a JQL function or another ScriptRunner feature', k, true)
        } else {
            addRow('JQL Function', k, 'Not project-scoped', k, true)
        }
    }

    // ── Step 4: judge every script now that usage has been read ──
    srPending.each { Map p -> finalizeRow(p) }

    if (srListCut) srWarnings << "Time limit reached while listing ${srListCut.join(' and ')}: some are not listed. Their usage files show under \"Check first\", never as unused.".toString()
    def byStatus = srRows.countBy { it.status }
    done("Scripts: ${srRows.size()}  |  " + ['ACTIVE', 'DORMANT', 'NEVER RUN', 'NO DATA', 'NOT TRACKED']
            .collect { "${it}: ${byStatus[it] ?: 0}" }.join('  |  '))
    srWarnings.each { warn(it) }
    srRows.take(200).each { Map r -> log.info "    [SR] ${r.status} | ${r.type} | ${r.name} | days ran ${SR_DORMANT_DAYS}d ${r.dX} | 30d ${r.d30} | 90d ${r.d90} | last ${r.lastRun}${r.daysAgo != null ? " (${r.daysAgo} days ago)" : ''}" }
    if (srRows.size() > 200) log.info "    [SR] …and ${srRows.size() - 200} more (log capped at 200 lines; every script is in the report)"
} catch (Throwable e) {
    // Fail safe: rows built before the failure keep their status only if it is "in use".
    // Anything that would read as unused becomes "Not read".
    srFailed = true
    srError  = "${e.class.simpleName}: ${e.message ?: 'no message'}".toString()
    log.error("    ✖ ScriptRunner usage check failed: ${srError}", e)
    srRows.each { Map r -> if (r.status in ['DORMANT', 'NEVER RUN']) r.status = 'NOT READ' }
}

// ═══════════════════════════════════════════════════════════════════════════
// REPORT DATA
// ═══════════════════════════════════════════════════════════════════════════
def srIsLeftover  = { Map r -> (r.type as String) == SR_LEFTOVER_TYPE }
def srIsUnmatched = { Map r -> (r.type as String).startsWith('Not matched') || srIsLeftover(r) }
int srUnattributed = srRows.count { srIsUnmatched(it) } as int
int srScripts      = srRows.size() - srUnattributed

// ScriptRunner version → which official ScriptRunner tools this instance can use.
// Script Registry export: 8.54+, 9.19+, 10.0+. Instance Audit built-in script: 8.54+, 9.25+, 10.1+.
// Source: docs.adaptavist.com (Script Registry; Audit your Jira instance using ScriptRunner).
String srVersion = pluginAccessor.getPlugin('com.onresolve.jira.groovy.groovyrunner')?.pluginInformation?.version ?: 'unknown'
def versionAtLeast = { String v, Map minMinorByMajor, int alwaysFromMajor ->
    def m = (v =~ /^(\d+)\.(\d+)/)
    if (!m.find()) return null
    int major = m.group(1) as int
    int minor = m.group(2) as int
    if (major >= alwaysFromMajor) return true
    if (minMinorByMajor.containsKey(major)) return minor >= (minMinorByMajor[major] as int)
    return false
}
Boolean exportAvailable        = versionAtLeast(srVersion, [8: 54, 9: 19, 10: 0], 11)
Boolean instanceAuditAvailable = versionAtLeast(srVersion, [8: 54, 9: 25, 10: 1], 11)

def srByType = srRows.groupBy { it.type as String }.collect { String type, List rs ->
    [type: type, total: rs.size(),
     active: rs.count { !srIsUnmatched(it) && it.status == 'ACTIVE' }, retire: rs.count { !srIsUnmatched(it) && it.status in ['DORMANT', 'NEVER RUN'] }]
}.sort { -(it.total as int) }

long RUN_END = System.currentTimeMillis()
log.warn "============================================================"
log.warn "  DATA COLLECTION COMPLETE in ${String.format('%.1f', (RUN_END - RUN_START) / 1000d)}s"
log.warn "  Time per step: ${phasesText()}"
log.warn "  Usage files read: ${srRrdFiles} of ${srRrdFilesFound} | IDs judged from file date: ${srAgeJudged}"
log.warn "  See the HTML result panel for the full report."
log.warn "============================================================"


// ═══════════════════════════════════════════════════════════════════════════
// DRILL-DOWN GROUPS — usage only (how the scripts move to Cloud is ScriptRunner
// Migration Suite's job). Every script is in exactly one group, and the bar chart
// uses the same groups and colours, so what you see in the bar is what you can
// open underneath it.
// ═══════════════════════════════════════════════════════════════════════════
def srGroupOf = { Map r ->
    // Unmatched usage files are not scripts: they always need identifying first.
    if ((r.type as String).startsWith('Not matched')) return 'IDENTIFY'
    // v2.2.0: left over from a script that no longer exists. No action only when read and no
    // recent run; a recent run or unread file needs checking (fail safe).
    if (srIsLeftover(r)) return r.status in ['DORMANT', 'NEVER RUN'] ? 'LEFTOVER' : 'LEFTCHECK'
    if (r.status in ['DORMANT', 'NEVER RUN']) return 'RETIRE'
    if (r.status == 'NOT READ') return 'NOTREAD'
    if (r.status == 'ACTIVE') return 'INUSE'
    return 'NODATA'
}
String SR_DAYS_TXT = (SR_DORMANT_DAYS as int) == 1 ? '1 day' : "${SR_DORMANT_DAYS} days".toString()
// Decision first: Keep, Archive or delete, or Check first. Every script is in exactly one group.
List<Map> SR_GROUPS = [
        [key: 'RETIRE', decision: 'ARCHIVE', word: 'ARCHIVE / DELETE', colour: '#FF8B00', text: '#172B4D', title: 'Archive or delete (after the owner agrees)',
         action: 'Archive or delete if the owner agrees',
         explain: "No run recorded in the last ${SR_DAYS_TXT}. If the owner agrees it is no longer needed, archive it (disable it, which keeps a copy) or delete it, on Data Center, before the Script Registry export.".toString()],
        [key: 'INUSE', decision: 'KEEP', word: 'KEEP', colour: '#00875A', text: '#FFFFFF', title: 'Keep (in use)',
         action: 'Keep',
         explain: "Ran at least once in the last ${SR_DAYS_TXT}. Keep it. ScriptRunner Migration Suite Analyse and Assess decides how it moves to Cloud.".toString()],
        [key: 'IDENTIFY', decision: 'CHECK', word: 'IDENTIFY', colour: '#6554C0', text: '#FFFFFF', title: 'Check first: identify these',
         action: 'Check first: identify what this is',
         explain: 'Usage files that match no configured script: listeners (name them with form option 3), other ScriptRunner features, or deleted scripts. Identify them before deciding anything.'],
        [key: 'NOTREAD', decision: 'CHECK', word: 'NOT READ', colour: '#DFE1E6', text: '#172B4D', title: 'Check first: usage not read',
         action: 'Check first: fix the problem shown and re-run',
         explain: 'Usage could not be read, so it is unknown, not zero. See Problems in this run at the top for the fix, then re-run.'],
        [key: 'NODATA', decision: 'CHECK', word: 'NO DATA', colour: '#C1C7D0', text: '#172B4D', title: 'Check first: no usage data',
         action: 'Check first: ask the owner',
         explain: 'A behaviour with no recorded run for any of its scripts, or a workflow condition with no recorded run. Usage is unknown, not zero. Ask the owner whether it is still needed.'],
        [key: 'LEFTCHECK', decision: 'CHECK', word: 'LEFT OVER?', colour: '#8777D9', text: '#FFFFFF', title: 'Check first: left over, but ran recently or not read',
         action: 'Check first: confirm the script was deleted',
         explain: "Usage files whose ID matches no current ScriptRunner script, that ran in the last ${SR_DAYS_TXT} or could not be read. Usually a script deleted or changed recently. Check before ignoring.".toString()],
        [key: 'LEFTOVER', decision: 'NONE', word: 'LEFT OVER', colour: '#B3D4FF', text: '#172B4D', title: 'No action: left over from deleted or changed scripts',
         action: 'No action: left-over usage file',
         explain: "Usage files whose ID matches no current ScriptRunner script, with no run in the last ${SR_DAYS_TXT}. Most likely left over: ScriptRunner keeps these files after a script is deleted or changed. Nothing to convert, archive or delete in ScriptRunner. If you recognise one as a current script, treat it as Archive or delete.".toString()],
]
Map<String, List<Map>> srRowsByGroup = SR_GROUPS.collectEntries { [(it.key): []] }
srRows.each { Map r -> srRowsByGroup[srGroupOf(r)] << r }

// Where each type is managed in ScriptRunner admin (links open on this Jira instance)
Map<String, String> SR_ADMIN_PATHS = [
        'Scheduled Job'         : '/plugins/servlet/scriptrunner/admin/jobs',
        'Escalation Service'    : '/plugins/servlet/scriptrunner/admin/jobs',
        'Script Listener'       : '/plugins/servlet/scriptrunner/admin/listeners',
        'Workflow Post-Function': '/secure/admin/workflows/ListWorkflows.jspa',
        'Workflow Condition'    : '/secure/admin/workflows/ListWorkflows.jspa',
        'Workflow Validator'    : '/secure/admin/workflows/ListWorkflows.jspa',
        'Script Field'          : '/plugins/servlet/scriptrunner/admin/scriptfields',
        'REST Endpoint'         : '/plugins/servlet/scriptrunner/admin/restendpoints',
        'JQL Function'          : '/plugins/servlet/scriptrunner/admin/jqlfunctions',
        'Behaviour'             : '/plugins/servlet/scriptrunner/admin/behaviours',
        'Not matched to a configured script': '/plugins/servlet/scriptrunner/admin/listeners',
]
String srBase = (baseUrl ?: '').replaceAll('/+$', '')

// ═══════════════════════════════════════════════════════════════════════════
// PROBLEMS IN THIS RUN — each with a plain fix, shown at the top of the report
// ═══════════════════════════════════════════════════════════════════════════
List<Map> problems = []   // [level: 'error'|'warn'|'info', what, fix]
if (srFailed) problems << [level: 'error', what: "The ScriptRunner usage check failed (${srError}).".toString(),
        fix: 'Re-run once. If it fails again, send this error text to your Adaptavist contact. Scripts that would have looked unused are shown as "Not read", so nothing is wrongly marked for archiving.']
if (srRrdUnavailable && !srFailed) problems << [level: 'error', what: 'ScriptRunner usage files were not found or could not be opened, so no usage is shown.',
        fix: 'Check that ScriptRunner is installed, enabled, and has run scripts. On a cluster, ask your Jira administrator to check that the shared home is mounted on this node.']
if (srNodesMissing) problems << [level: 'warn', what: "No usage folder was found for cluster node(s) ${srNodesMissing.join(', ')}, so no script is marked as unused.".toString(),
        fix: 'Ask your Jira administrator to check those nodes in Jira admin → System → Clustering. A node that is new, or has never run a script, has no folder yet: re-run once it has. A node removed for good should be removed from the cluster list.']
if (srClusterCheckFailed) problems << [level: 'warn', what: 'The list of cluster nodes could not be checked, so no script is marked as unused.',
        fix: 'Compare "Nodes read" (under "How this data was collected") with Jira admin → System → Clustering, then re-run. If it happens again, send this report to your Adaptavist contact.']
if (srBudgetHit || srListCut) problems << [level: 'warn',
        what: ([srMatchedNotRead ? "usage of ${srMatchedNotRead} script(s) not read" : null,
                srUnmatchedNotRead ? "${srUnmatchedNotRead} unmatched usage ID(s) not read" : null,
                srListCut ? "some ${srListCut.join(' and ')} not listed" : null].findAll().join(', ') + " — the ${TIME_BUDGET_SEC}s time limit was reached.").toString(),
        fix: ("Raise the time limit (option 1, up to ${TIME_BUDGET_MAX_SEC}s) and re-run, off-peak. " +
              'If the page times out before the report appears, wait for the run to finish, then type yes in option 4 to see the saved report.').toString()]
if (srUnreadableKeys) problems << [level: 'warn', what: "${srUnreadableKeys.size()} usage file(s) could not be read. Those scripts show as \"Not read\".".toString(),
        fix: 'Usually a file being written at that moment: re-run. If the same files fail again, send the Jira log to your Adaptavist contact.']
if (srMissingDs) problems << [level: 'warn', what: "${srMissingDs.size()} usage file(s) were in an unexpected format and were skipped.".toString(),
        fix: "This ScriptRunner version (${srVersion}) may store usage differently. Send this report to your Adaptavist contact.".toString()]
if (srMap == null && srMapError && !srFailed && !srRrdUnavailable) problems << [level: 'info', what: "ScriptRunner's own script list could not be read (${srMapError}).".toString(),
        fix: 'Listeners are not named and left-over usage files are not separated. Name listeners with option 3, or send this report to your Adaptavist contact.']
if (TIME_BUDGET_CAPPED) problems << [level: 'info', what: "The time limit asked for was above ${TIME_BUDGET_MAX_SEC}s, so ${TIME_BUDGET_MAX_SEC}s was used.".toString(),
        fix: 'The browser usually gives up long before that, losing the report. If your proxy waits longer, raise TIME_BUDGET_MAX_SEC in the script\'s advanced settings.']

// ═══════════════════════════════════════════════════════════════════════════
// HTML REPORT
// OutputFormatter escapes values passed as text, so names from the instance
// are rendered safely. Only fixed badge markup is written unescaped.
// ═══════════════════════════════════════════════════════════════════════════
String BOX_WARN = 'background:#FFFAE6;border-left:4px solid #FF8B00;padding:8px 12px;margin:12px 0;font-size:12px'
String DIM = 'color:#5E6C84;font-size:12px'

def reportHtml
try {
reportHtml = OutputFormatter.markupBuilder { builder ->
    // ── One visual rule for "clickable" ──────────────────────────────────────
    //   blue ▸ / ▾ = the line opens and closes (click anywhere on it)
    //   blue underlined text with ↗ = a real link (opens in a new tab)
    //   everything else = plain text, not clickable
    mkp.yieldUnescaped("""<style>
.mri { font-family: Arial, sans-serif; max-width: 1100px; margin: 0 auto; color: #172B4D; }
.mri a.mri-link { color: #0052CC; text-decoration: underline; }
.mri a.mri-link::after { content: ' \\2197'; font-size: 10px; }
.mri details > summary { cursor: pointer; list-style: none; position: relative; padding-right: 120px !important; background: #F4F8FF; border-radius: 4px; }
.mri details > summary::-webkit-details-marker { display: none; }
.mri details > summary::before { content: '\\25B6'; display: inline-block; width: 20px; color: #0052CC; font-size: 12px; font-weight: bold; }
.mri details[open] > summary::before { content: '\\25BC'; }
.mri details > summary::after { content: 'Click to open'; position: absolute; right: 10px; top: 50%; transform: translateY(-50%);
  font-size: 11px; font-weight: bold; color: #FFFFFF; background: #0052CC; border-radius: 10px; padding: 2px 10px; white-space: nowrap; }
.mri details[open] > summary::after { content: 'Click to close'; color: #0052CC; background: #FFFFFF; border: 1px solid #0052CC; }
.mri details > summary:hover { background: #DEEBFF; }
.mri details > summary:hover::after { text-decoration: underline; }
.mri details > summary:focus-visible { outline: 2px solid #0052CC; outline-offset: 1px; }
.mri details > summary[data-open]::after { content: attr(data-open); }
.mri details[open] > summary[data-close]::after { content: attr(data-close); }
.mri details.mri-group { border-left-width: 8px; margin: 10px 0; }
.mri details.mri-group > summary { font-size: 15px; padding: 12px 12px; }
.mri details[open] > summary { border-bottom: 1px solid #B3D4FF; border-radius: 4px 4px 0 0; }
.mri details.mri-row { border: 1px solid #4C9AFF; border-left-width: 4px; border-radius: 4px; margin: 6px 0; background: #fff; }
.mri details.mri-row > summary { padding: 8px 10px; font-size: 13px; }
.mri details.mri-sub { margin: 6px 0; border: 1px solid #4C9AFF; border-left-width: 4px; border-radius: 4px; background: #fff; }
.mri details.mri-sub > summary { padding: 8px 10px; font-size: 13px; font-weight: bold; }
.mri details.mri-sub > div { padding: 4px 10px 8px 24px; }
.mri .mri-hint { color: #5E6C84; font-size: 12px; font-weight: normal; }
</style>""")

    def subGroup = { String title, int count, Closure body ->
        mkp.yieldUnescaped("<details class='mri-sub'><summary>")
        mkp.yield "${title} (${String.format('%,d', count)})"
        mkp.yieldUnescaped("</summary><div>")
        if (count == 0) p(style: 'color:#5E6C84;font-size:12px') { mkp.yield 'None.' } else body()
        mkp.yieldUnescaped("</div></details>")
    }
    def moreNote = { int total, String extra ->
        if (total > MAX_ROWS) p(style: 'color:#5E6C84;font-size:12px;font-style:italic') {
            mkp.yield "…and ${String.format('%,d', total - MAX_ROWS)} more not shown (showing the first ${MAX_ROWS}).${extra ? ' ' + extra : ''}"
        }
    }

    def extLink = { String href, String text -> a(href: href, target: '_blank', class: 'mri-link') { mkp.yield text } }
    def groupPill = { Map g -> "<span style='display:inline-block;min-width:62px;text-align:center;background:${g.colour};color:${g.text};border-radius:10px;padding:1px 8px;font-size:11px;font-weight:bold;white-space:nowrap'>${g.word}</span>" }

    div(class: 'mri') {

        // ── Header ────────────────────────────────────────────────────────────
        h1(style: 'color:#172B4D;margin:0 0 4px') { mkp.yield 'ScriptRunner usage on Data Center' }
        p(style: DIM + ';margin:0 0 8px') {
            mkp.yield([baseUrl ?: null, "Jira ${jiraVersion} (build ${buildNumber})".toString(),
                       "ScriptRunner ${srVersion}", "Generated ${new Date().format('yyyy-MM-dd HH:mm z')}"].findAll().join('  ·  '))
        }
        // ── Problems in this run, each with its fix (only when there are any) ──
        if (problems) {
            boolean anyError = problems.any { it.level == 'error' }
            boolean anyWarn  = problems.any { it.level == 'warn' }
            String border = anyError ? '#DE350B' : (anyWarn ? '#FF8B00' : '#0052CC')
            String bg     = anyError ? '#FFEBE6' : (anyWarn ? '#FFFAE6' : '#F4F8FF')
            div(style: "border:2px solid ${border};background:${bg};border-radius:6px;padding:10px 16px;margin:6px 0 14px") {
                div(style: 'font-size:15px;font-weight:bold;margin-bottom:4px') {
                    mkp.yield(((anyError || anyWarn) ? 'Problems in this run, and how to fix them' : 'Notes on this run') as String)
                }
                p(style: 'font-size:12px;margin:0 0 6px') { mkp.yield 'Nothing in Jira was changed. Scripts with unknown usage are never shown as "Archive or delete".' }
                table(style: 'width:100%;border-collapse:collapse;font-size:13px') {
                    thead { tr { th(scope: 'col', style: 'text-align:left;padding:4px;width:45%', 'What happened'); th(scope: 'col', style: 'text-align:left;padding:4px', 'How to fix it') } }
                    tbody {
                        problems.sort { [error: 0, warn: 1, info: 2][it.level as String] }.each { Map pr ->
                            tr(style: 'border-top:1px solid rgba(0,0,0,0.1)') {
                                td(style: 'padding:4px;vertical-align:top') { b([error: 'Error: ', warn: 'Check: ', info: 'Note: '][pr.level as String] as String); mkp.yield pr.what as String }
                                td(style: 'padding:4px;vertical-align:top') { mkp.yield pr.fix as String }
                            }
                        }
                    }
                }
            }
        }
        div(style: 'border:2px solid #0052CC;border-radius:6px;padding:12px 16px;margin:6px 0 14px;background:#F4F8FF') {
            div(style: 'font-size:15px;font-weight:bold;margin-bottom:6px') { mkp.yield 'Read this first: what this report is' }
            ol(style: 'margin:0 0 6px;padding-left:20px;font-size:13px;line-height:1.5') {
                li { b('It shows how much your ScriptRunner scripts are used on Data Center '); mkp.yield '— on how many days each one ran, and when it last ran.' }
                li { b('Use it to decide which scripts you still need, before converting any. '); mkp.yield 'Scripts nobody uses are worth considering for retirement first.' }
                li {
                    b('It is advice only and does not replace the ScriptRunner Migration Suite. ')
                    mkp.yield 'ScriptRunner Migration Suite Analyse and Assess shows how each script moves to Cloud, but not whether it is used. This report fills that gap. You still analyse, convert and deploy in ScriptRunner Migration Suite. '
                    extLink('https://docs.adaptavist.com/sms/latest/scriptrunner-migration-suite-web-app/scriptrunner-migration-analyse-and-assess-tool', 'ScriptRunner Migration Suite Analyse and Assess')
                }
            }
            p(style: 'font-size:11px;color:#5E6C84;margin:0') {
                mkp.yield "Best-effort proof of concept: not supported or maintained, read-only for Jira (nothing in Jira was changed), results indicative. Saves this report to ${SAVED_REPORT}.".toString()
            }
            div(style: 'font-size:13px;margin:8px 0 0;padding:6px 10px;background:#DEEBFF;border-radius:4px') {
                b('How to use this page: ')
                mkp.yield 'boxes with a blue edge and a '
                mkp.yieldUnescaped("<span style='font-size:11px;font-weight:bold;color:#fff;background:#0052CC;border-radius:10px;padding:1px 8px;white-space:nowrap'>Click to open</span>")
                mkp.yield ' button expand when you click anywhere on the line. Click again to close. '
                span(style: 'color:#0052CC;text-decoration:underline') { mkp.yield 'Blue underlined ↗' }
                mkp.yield ' text is a link. Nothing else is clickable.'
            }
        }

        // ═════ 1. SCRIPTRUNNER ═════════════════════════════════════════════
        h2(style: 'color:#172B4D;font-size:18px;margin:0 0 6px') { mkp.yield 'Your ScriptRunner scripts' }
        if (!srRows) {
            p { mkp.yield (srRrdUnavailable || srFailed) ? 'ScriptRunner usage could not be read, so no usage can be shown. See Problems in this run at the top.'
                    : 'No ScriptRunner scripts or script activity were found on this instance.' }
        } else {
            // Decision summary: the three answers the customer needs
            int nKeep    = srRowsByGroup['INUSE'].size()
            int nArchive = srRowsByGroup['RETIRE'].size()
            int nCheck   = srRowsByGroup['IDENTIFY'].size() + srRowsByGroup['NOTREAD'].size() + srRowsByGroup['NODATA'].size() + srRowsByGroup['LEFTCHECK'].size()
            int nLeft    = srRowsByGroup['LEFTOVER'].size()
            int nLeftAll = srRows.count { srIsLeftover(it) } as int
            p(style: 'font-size:13px;margin:0 0 8px') {
                mkp.yield "${srScripts} ScriptRunner scripts found" +
                        (nLeftAll ? ", plus ${nLeftAll} usage files most likely left over from deleted or changed scripts" : '') +
                        ((srUnattributed - nLeftAll) ? ", plus ${srUnattributed - nLeftAll} usage files not matched to a script" : '') +
                        ". Based on usage in the last ${SR_DAYS_TXT}:"
            }
            def tile = { String colour, String bgc, int n, String label, String sub ->
                div(style: "flex:1 1 200px;border:2px solid ${colour};border-top-width:8px;background:${bgc};border-radius:6px;padding:10px 14px") {
                    div(style: 'font-size:32px;font-weight:bold;line-height:1.1') { mkp.yield String.format('%,d', n) }
                    div(style: 'font-size:15px;font-weight:bold;margin:2px 0 4px') { mkp.yield label }
                    div(style: 'font-size:12px;color:#42526E;line-height:1.4') { mkp.yield sub }
                }
            }
            div(style: 'display:flex;flex-wrap:wrap;gap:10px;margin:0 0 10px') {
                tile('#00875A', '#E3FCEF', nKeep, 'Keep', "Ran in the last ${SR_DAYS_TXT}. Keep and convert with ScriptRunner Migration Suite.".toString())
                tile('#FF8B00', '#FFFAE6', nArchive, 'Archive or delete', "No run in the last ${SR_DAYS_TXT}. If the owner agrees, archive (disable) or delete on Data Center before export.".toString())
                tile('#6B778C', '#F4F5F7', nCheck, 'Check first', 'Usage unknown. Do not archive or delete until checked.')
                if (nLeft) tile('#4C9AFF', '#F4F8FF', nLeft, 'No action: left over', "Usage files of scripts that no longer exist, with no run in the last ${SR_DAYS_TXT}. Nothing to do in ScriptRunner.".toString())
            }
            // Bar: same groups, same colours, same order as the lists below
            if (srNotReadKeys || srUnreadableKeys || srRrdUnavailable || srNodesMissing || srClusterCheckFailed || srListCut || srFailed) {
                div(style: BOX_WARN) {
                    b('Usage data is incomplete. ')
                    mkp.yield 'Some scripts are under "Check first" instead of Keep or Archive. The fix is in Problems in this run, at the top.'
                }
            }
            String barAlt = 'ScriptRunner scripts by usage: ' + SR_GROUPS.findAll { srRowsByGroup[it.key as String] }
                    .collect { "${srRowsByGroup[it.key as String].size()} ${(it.title as String).toLowerCase()}" }.join(', ')
            div(role: 'img', 'aria-label': barAlt, title: barAlt,
                    style: 'display:flex;height:22px;border-radius:4px;overflow:hidden;max-width:760px;margin:8px 0 2px;border:1px solid #DFE1E6') {
                SR_GROUPS.findAll { srRowsByGroup[it.key as String] }.each { Map g ->
                    int c = srRowsByGroup[g.key as String].size()
                    double pct = c * 100d / srRows.size()
                    div(style: "width:${String.format('%.2f', pct)}%;background:${g.colour};color:${g.text};font-size:11px;line-height:22px;text-align:center;white-space:nowrap;overflow:hidden") {
                        mkp.yield pct >= 7 ? "${c}" : ''
                    }
                }
            }
            p(class: 'mri-hint', style: 'margin:0 0 8px') { mkp.yield 'The bar is a picture only.' }
            div(style: 'border-top:2px solid #172B4D;margin:14px 0 4px;padding-top:8px') {
                div(style: 'font-size:16px;font-weight:bold') { mkp.yield 'Your scripts, by decision' }
                p(style: 'font-size:13px;margin:2px 0 0') {
                    mkp.yield "Each coloured bar below is one group (${SR_GROUPS.count { srRowsByGroup[it.key as String] }} in total). Click a bar to show or hide its scripts: on how many days each ran in the last ${SR_DAYS_TXT}, and when it last ran. Archive or delete is already open.".toString()
                }
            }

            // Drill-down: one collapsible group per colour
            SR_GROUPS.findAll { srRowsByGroup[it.key as String] }.each { Map g ->
                // Most useful order per group: longest unused first; most used first.
                List<Map> rs = srRowsByGroup[g.key as String].sort { x, y ->
                    if (g.key == 'RETIRE') {
                        long dx = x.daysAgo == null ? Long.MAX_VALUE : (x.daysAgo as long)
                        long dy = y.daysAgo == null ? Long.MAX_VALUE : (y.daysAgo as long)
                        return (dy <=> dx) ?: (x.name as String) <=> (y.name as String)
                    }
                    if (g.key in ['INUSE', 'IDENTIFY', 'LEFTCHECK', 'LEFTOVER']) {
                        long ax = x.daysAgo == null ? Long.MAX_VALUE : (x.daysAgo as long)
                        long ay = y.daysAgo == null ? Long.MAX_VALUE : (y.daysAgo as long)
                        return ((y.sortDays as int) <=> (x.sortDays as int)) ?: (ax <=> ay) ?: (x.name as String) <=> (y.name as String)
                    }
                    return (x.type as String) <=> (y.type as String) ?: (x.name as String) <=> (y.name as String)
                }
                String sortNote = g.key == 'RETIRE' ? 'Longest unused first.' : (g.key in ['INUSE', 'IDENTIFY', 'LEFTCHECK', 'LEFTOVER'] ? 'Ran on the most days first, then most recent.' : '')
                String edge = g.colour == '#DFE1E6' ? '#6B778C' : (g.colour as String)
                mkp.yieldUnescaped("<details class='mri-sub mri-group' style='border-left-color:${edge}'" + (g.key == 'RETIRE' ? ' open' : '') +
                        "><summary data-open='Show list (${rs.size()})' data-close='Hide list'>" + groupPill(g) + '&nbsp; ')
                mkp.yield "${g.title} (${rs.size()})"
                span(class: 'mri-hint') { mkp.yield ' — ' + (g.explain as String) }
                mkp.yieldUnescaped("</summary><div>")
                if (g.key == 'RETIRE' && rs) {
                    div(style: 'background:#FFFAE6;border-left:4px solid #FF8B00;padding:8px 12px;margin:4px 0 8px;font-size:12px') {
                        b('How to archive or delete safely')
                        ol(style: 'margin:4px 0 0;padding-left:18px;line-height:1.5') {
                            li { mkp.yield "Agree with the script's owner first. Usage is not value: a script that runs once a year can be critical. Not sure? Re-run with option 2 set to 365." }
                            li { mkp.yield 'Keep a copy of the script code before changing anything.' }
                            li { mkp.yield 'Archive = disable it where ScriptRunner offers that. It keeps a copy, and "Export active scripts only" leaves it out of the Script Registry export.' }
                            li { mkp.yield 'Workflow scripts are removed by editing the workflow, which changes live transitions. Your Jira administrator should test this first.' }
                            li { mkp.yield 'Script fields may hold values on issues. Check with your Jira administrator before removing one.' }
                            li { mkp.yield 'Delete only once the owner agrees and a copy is kept.' }
                        }
                    }
                }
                if (!rs) {
                    p(class: 'mri-hint') { mkp.yield 'None.' }
                } else {
                    table(class: 'aui', style: 'width:100%') {
                        thead {
                            tr {
                                th(scope: 'col', 'Type'); th(scope: 'col', 'Name'); th(scope: 'col', 'Projects / scope')
                                th(scope: 'col', style: 'text-align:right', "Days it ran, last ${SR_DAYS_TXT}".toString()); th(scope: 'col', 'Last run')
                                th(scope: 'col', 'Manage in ScriptRunner')
                            }
                        }
                        tbody {
                            rs.take(MAX_ROWS).each { Map r ->
                                tr {
                                    td(r.type as String); td(r.name as String); td(r.where as String)
                                    td(style: 'text-align:right', (r.dX in ['n/a', '—'] ? r.dX : "${r.dX} of ${SR_DORMANT_DAYS}") as String)
                                    td {
                                        if (r.daysAgo == null) { mkp.yield r.lastRun as String }
                                        else {
                                            long da = r.daysAgo as long
                                            b(da == 0L ? 'today' : (da == 1L ? '1 day ago' : "${String.format('%,d', da)} days ago".toString()))
                                            span(class: 'mri-hint') { mkp.yield " (${r.lastRun})" }
                                        }
                                    }
                                    td {
                                        String path = SR_ADMIN_PATHS[r.type as String]
                                        if (path) { extLink("${srBase}${path}".toString(), 'Open admin page') } else { span(class: 'mri-hint') { mkp.yield '—' } }
                                    }
                                }
                            }
                        }
                    }
                    if (sortNote) p(class: 'mri-hint', style: 'margin:4px 0 0') { mkp.yield sortNote }
                    moreNote(rs.size(), 'Use "Copy the full list" below to get every script.')
                    if (g.key == 'RETIRE') {
                        div(style: 'margin:10px 0 2px;padding:6px 10px;background:#DEEBFF;border-radius:4px;font-size:13px;font-weight:bold') {
                            mkp.yield ("End of the Archive or delete list. More groups below: Keep (${nKeep}), Check first (${nCheck})" + (nLeft ? ", No action: left over (${nLeft})" : '') + '. Click a bar to open it. ↓').toString()
                        }
                    }
                }
                mkp.yieldUnescaped("</div></details>")
            }

            // Full list for working through with owners (no row cap)
            subGroup('Copy the full list into a spreadsheet', srRows.size()) {
                p(class: 'mri-hint', style: 'margin:0 0 6px') {
                    mkp.yield 'Click in the box, select all (Ctrl+A or Cmd+A), copy, and paste into Excel or Google Sheets. ' +
                            'It pastes straight into columns, with the suggested decision first and empty Owner and Final decision columns to fill in.'
                }
                def clean = { v -> (v == null ? '' : v.toString()).replaceAll(/[\t\r\n]+/, ' ') }
                Map<String, String> groupTitle = SR_GROUPS.collectEntries { [(it.key): it.title] }
                // The chosen-window column is left out when it would repeat the fixed 30- or 90-day column.
                boolean dupWindow = (SR_DORMANT_DAYS as int) in [30, 90]
                List<String> hdr = ['Suggested decision', 'Usage group', 'Type', 'Name', 'Projects / scope', "Days it ran, last ${SR_DAYS_TXT}".toString(), 'Last run (UTC)', 'Days since last run', 'Days it ran, last 30 days', 'Days it ran, last 90 days']
                hdr += ['Owner', 'Final decision']
                if (dupWindow) hdr.remove(5)
                List<String> lines = [hdr.join('\t')]
                SR_GROUPS.each { Map g ->
                    srRowsByGroup[g.key as String].each { Map r ->
                        List vals = [g.action, groupTitle[g.key as String], r.type, r.name, r.where, r.dX, r.lastRun,
                                     r.daysAgo != null ? r.daysAgo : '', r.d30, r.d90, '', '']
                        if (dupWindow) vals.remove(5)
                        lines << vals.collect { clean(it) }.join('\t')
                    }
                }
                textarea(readonly: 'readonly', rows: '10', 'aria-label': 'Full ScriptRunner script list, tab-separated',
                        style: 'width:100%;font-family:monospace;font-size:11px;white-space:pre;overflow:auto') { mkp.yield lines.join('\n') }
            }

            // Secondary and reference views
            subGroup('By feature type', srByType.size()) {
                table(class: 'aui', style: 'width:100%') {
                    thead { tr { th(scope: 'col', 'Feature type'); th(scope: 'col', style: 'text-align:right', 'Scripts'); th(scope: 'col', style: 'text-align:right', 'In use'); th(scope: 'col', style: 'text-align:right', 'Archive or delete') } }
                    tbody {
                        srByType.each { Map t ->
                            tr { td(t.type as String); td(style: 'text-align:right', t.total as String); td(style: 'text-align:right', t.active as String); td(style: 'text-align:right', t.retire as String) }
                        }
                    }
                }
                p(class: 'mri-hint') {
                    mkp.yield 'To see how each script moves to Cloud, run the Script Registry export through ScriptRunner Migration Suite Analyse and Assess. '
                    extLink('https://docs.adaptavist.com/sms/latest/scriptrunner-migration-suite-web-app/scriptrunner-migration-analyse-and-assess-tool', 'ScriptRunner Migration Suite Analyse and Assess')
                }
            }
            subGroup('How this data was collected, and its limits', 1) {
                p(class: 'mri-hint') {
                    mkp.yield "Read from ScriptRunner's own execution files (the same data behind the SR Performance tab). " +
                            "Nodes read: ${srNodeNames.join(', ') ?: 'none'}. Usage files found: ${srRrdFilesFound}, read: ${srRrdFiles}, usage IDs judged from file date: ${srAgeJudged}" +
                            (srMatchedNotRead ? ", scripts not read (time limit): ${srMatchedNotRead}" : '') +
                            (srUnmatchedNotRead ? ", unmatched IDs not read (time limit): ${srUnmatchedNotRead}" : '') + '. ' +
                            'Usage files of scripts are read first, newest first; IDs that match no current script are read only if time remains. ' +
                            (srRrdMeta.dsType
                                    ? "Days it ran come from ScriptRunner's ${srRrdMeta.fineStep}s and ${srRrdMeta.dailyStep}s rows: any row with a run counts that day once. " +
                                      "Run counts are not shown, because the way ScriptRunner stores them cannot be turned back into an exact number of runs. " +
                                      "File settings: value type ${srRrdMeta.dsType}, step ${srRrdMeta.dbStep}s." +
                                      (srRrdMeta.dailyOk ? '' : ' Daily rows were not found, so days are counted from the last 48 hours only.')
                                    : 'No usage file could be read.')
                }
                if (srWarnings) {
                    div(style: BOX_WARN) { b('Some data could not be loaded:'); ul { srWarnings.each { String w -> li { mkp.yield w } } } }
                }
                ol(style: 'font-size:12px') {
                    li { mkp.yield 'The report shows on how many days a script ran, not how many times. ScriptRunner\'s stored data cannot give an exact run count, so none is shown. A failed run still counts as a run.' }
                    li { mkp.yield 'Runs from the last few minutes may not show yet; the SR Performance tab is real-time.' }
                    li { mkp.yield 'Days and dates are UTC calendar days. A run within about 5 minutes of midnight UTC may count on the next day. Last run is a date only (no time of day), and looks back as far as ScriptRunner keeps data — up to 2 years in this report. "No run recorded" means no run was found in that period.' }
                    li { mkp.yield (srMap != null
                            ? 'Scripts are matched to usage files using ScriptRunner\'s own list (the list behind its execution history), so listeners are named automatically, including ones that never ran. ScriptRunner keeps a script\'s usage file after the script is deleted or its ID changes (for example when a workflow is copied), so files matching no current script are shown as "Left over".'
                            : 'ScriptRunner\'s own script list could not be read, so listeners appear under "Not matched" unless named (form option 3). Listeners that never ran do not appear. "Not matched" can also include other ScriptRunner features and deleted scripts, so it is not a list of listeners.') as String }
                    li { mkp.yield 'REST endpoints and JQL functions appear only once they have been called.' }
                    li { mkp.yield "A usage file is only written when its script runs. A file not changed for over ${Math.max(SR_DORMANT_DAYS as int, 90)} days cannot hold a run in that time, so when there is no time to open it, it is judged from its file date (shown as \"file last changed\"). Left-over files of that age are not opened. Files are read newest first.".toString() }
                    li { mkp.yield 'Behaviours: usage comes from the scripts inside each behaviour (initialiser and field scripts). A behaviour counts as run when any of its scripts ran. A behaviour with no recorded script run shows under "No usage data", not as unused.' }
                    li { mkp.yield 'Not included: Script Fragments, Mail Handlers, Resources and built-in scripts (no usage data is collected for them). Workflow conditions show usage only where ScriptRunner has recorded it.' }
                    li { mkp.yield '"Projects / scope" is where a script is configured to apply, not which projects triggered each run.' }
                    li { mkp.yield 'Usage says nothing about how a script moves to Cloud. ScriptRunner Migration Suite Analyse and Assess shows that.' }
                }
            }
        }

        // ═════ 2. NEXT STEPS ═══════════════════════════════════════════════
        def yesNo = { Boolean avail -> avail == null ? 'version not recognised — check manually' : (avail ? 'available on this instance' : 'needs the latest release in your major version') }
        div(style: 'background:#F4F5F7;border-radius:4px;padding:10px 14px;margin:16px 0;font-size:13px') {
            b('Next steps: how this report fits your ScriptRunner migration')
            p(style: 'margin:4px 0 0') { mkp.yield 'Best used before the Script Registry export, so retired scripts are never analysed or converted. Already ran ScriptRunner Migration Suite Analyse and Assess? Use both together, then export and analyse again after retiring scripts.' }
            ol(style: 'margin:6px 0 0;padding-left:20px') {
                li(style: 'margin-bottom:4px') { b('Archive or delete unused scripts on Data Center — '); mkp.yield 'review the "Archive or delete" list with owners, and disable or delete anything no longer needed. This report narrows the list; it does not assess migratability.' }
                li(style: 'margin-bottom:4px') {
                    b('Script Registry export — '); mkp.yield "choose \"Export active scripts only\", so disabled (retired) scripts are left out. Script roots, and saved JQL filters that use ScriptRunner JQL functions, are always included (${yesNo(exportAvailable)}). "
                    extLink('https://docs.adaptavist.com/sr4js/latest/features/script-registry', 'Script Registry docs')
                }
                li(style: 'margin-bottom:4px') { b('ScriptRunner Migration Suite Analyse and Assess (always do this) — '); mkp.yield 'upload the export. This is the up-to-date check of how each script moves to Cloud. Do this before deciding how any script moves. If a script type you use does not appear in its results, review that type manually — the supported-features page lists what the tool covers. '; extLink('https://docs.adaptavist.com/sms/latest/scriptrunner-migration-suite-web-app/scriptrunner-migration-analyse-and-assess-tool', 'Analyse and Assess docs') }
                li(style: 'margin-bottom:4px') { b('Migration Agent (ScriptRunner Migration Suite) — '); mkp.yield 'convert only what is left — the scripts that are in use and still needed. '; extLink('https://docs.adaptavist.com/sms/latest/scriptrunner-migration-suite-web-app/scriptrunner-migration-agent', 'Migration Agent docs') }
                li { b('Dev and Deployment Tool — '); mkp.yield 'deploy the converted scripts. '; extLink('https://docs.adaptavist.com/sms/latest/scriptrunner-dev-and-deployment-tool', 'Dev and Deployment Tool docs') }
            }
            p(style: 'font-size:12px;color:#42526E;margin:8px 0 0') {
                mkp.yield "For full Jira-wide counts, ScriptRunner's Instance Audit built-in script is ${yesNo(instanceAuditAvailable)}. "
                extLink('https://docs.adaptavist.com/sr4js/latest/best-practices/audit-your-jira-instance-using-scriptrunner', 'Instance Audit docs')
            }
        }


        // ═════ 3. ABOUT THIS RUN (reference) ═══════════════════════════════
        h2(style: 'color:#172B4D;font-size:16px;margin:22px 0 4px') { mkp.yield 'About this run' }
        subGroup('Settings used, and what each one changed', 4) {
            table(class: 'aui', style: 'width:100%') {
                thead { tr { th(scope: 'col', 'Setting'); th(scope: 'col', 'Value'); th(scope: 'col', 'What it meant for this report') } }
                tbody {
                    def row = { String k, String v, String m -> tr { td { b(k) }; td(v); td(m) } }
                    row('Maximum run time', "${TIME_BUDGET_SEC}s".toString(), "The run took ${String.format('%.1f', (RUN_END - RUN_START) / 1000d)}s. " +
                            ((srBudgetHit || srListCut) ? 'The time limit was reached: see Problems in this run.' : 'The time limit was not reached.'))
                    row('Time per step', phasesText() ?: '—', 'How long each step took. Building and saving the report is in the Jira log (RUN COMPLETE line).')
                    row('Unused after', "${SR_DORMANT_DAYS} days".toString(), "Scripts with no recorded run in ${SR_DORMANT_DAYS} days are listed under \"Archive or delete\".")
                    row('Listener names', SR_LISTENER_NAMES ? "${SR_LISTENER_NAMES.size()} entered".toString() : 'none',
                            srMap != null ? 'Listeners are named from ScriptRunner\'s own list' + (SR_LISTENER_NAMES ? '; names entered here are used first.' : '.')
                                    : (SR_LISTENER_NAMES ? 'Matching listeners are shown by name.' : 'Listeners (if any) are shown as "Not matched".'))
                }
            }
        }

        div(style: 'color:#5E6C84;font-size:11px;margin-top:20px;border-top:1px solid #DFE1E6;padding-top:8px') {
            b('About this report. ')
            mkp.yield 'Best-effort proof of concept: provided as-is, not maintained, not supported, and not an official Adaptavist or Atlassian tool. ' +
                    "Read-only for Jira: no Jira data or configuration was changed. One file is written: this report, saved to ${SAVED_REPORT} and overwritten on each run. Automated checks only; results are indicative, so verify before acting. " +
                    'It does not replace Atlassian\'s Jira pre-migration checklist or the Jira Cloud Migration Assistant (JCMA). '
            extLink('https://support.atlassian.com/migration/docs/jira-pre-migration-checklist/', 'Atlassian pre-migration checklist')
        }
    }
}
} catch (Throwable e) {
    // The report itself could not be drawn: show a plain fallback with the decision lists.
    log.error("Report could not be drawn: ${e}", e)
    reportHtml = OutputFormatter.markupBuilder {
        div(style: 'font-family:Arial,sans-serif;max-width:900px') {
            div(style: 'border:2px solid #DE350B;background:#FFEBE6;border-radius:6px;padding:12px 16px;margin-bottom:12px') {
                div(style: 'font-weight:bold;margin-bottom:4px') { mkp.yield 'The full report could not be drawn, so this simple version is shown.' }
                p(style: 'margin:4px 0') { mkp.yield "Error: ${e.class.simpleName}: ${e.message ?: 'no message'}".toString() }
                p(style: 'margin:4px 0') { mkp.yield 'Nothing in Jira was changed. Send this error text to your Adaptavist contact. The lists below are still valid.' }
            }
            SR_GROUPS.each { Map g ->
                List<Map> rs = srRowsByGroup[g.key as String] ?: []
                if (!rs) return
                h3 { mkp.yield "${g.title} (${rs.size()})".toString() }
                ul { rs.each { Map r -> li { mkp.yield "${r.type}: ${r.name} (last run: ${r.lastRun})".toString() } } }
            }
        }
    }
} finally {
    // ── Save the report (v2.1.0): written under a temporary name, then renamed, so a
    // half-written file is never shown. A failed save never affects the report on screen.
    lap('Building the report')
    if (reportHtml) {
        File tmp = null
        try {
            String nodeId = 'single node'
            try {
                def cm = ComponentAccessor.getComponent(com.atlassian.jira.cluster.ClusterManager)
                if (cm?.isClustered()) nodeId = (cm.getNodeId() ?: 'unknown') as String
            } catch (ignored) {}
            String fmtTs = 'yyyy-MM-dd HH:mm:ss z'
            String stamp = "<div style='font-family:Arial,sans-serif;font-size:12px;color:#172B4D;background:#F4F5F7;border-radius:4px;padding:6px 10px;margin:0 auto 8px;max-width:1100px'>" +
                    "Run started ${new Date(RUN_START).format(fmtTs)}, finished ${new Date().format(fmtTs)}, on node ${nodeId.replaceAll(/[^A-Za-z0-9._:-]/, '')}. " +
                    "Time limit ${TIME_BUDGET_SEC}s. Usage files read: ${srRrdFiles} of ${srRrdFilesFound}. Usage IDs judged from file date: ${srAgeJudged}. Time per step: ${phasesText()}.</div>"
            File dir = SAVED_REPORT.parentFile
            dir.mkdirs()
            tmp = new File(dir, "last-report.${RUN_START}.tmp")
            tmp.setText(stamp + reportHtml.toString(), 'UTF-8')
            try {
                Files.move(tmp.toPath(), SAVED_REPORT.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp.toPath(), SAVED_REPORT.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            log.warn "  REPORT SAVED to ${SAVED_REPORT} (${(SAVED_REPORT.length() / 1024L) as long} KB). Option 4 shows it."
        } catch (Throwable se) {
            try { tmp?.delete() } catch (ignored) {}
            log.warn "  Report could not be saved to ${SAVED_REPORT}: ${se.message}. The report on screen is unaffected."
        }
    }
    log.warn "  RUN COMPLETE in ${String.format('%.1f', (System.currentTimeMillis() - RUN_START) / 1000d)}s  |  ${phasesText()}"
    System.clearProperty(RUN_LOCK_KEY)
}
return reportHtml
