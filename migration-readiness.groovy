// ═══════════════════════════════════════════════════════════════════════════
// ScriptRunner Migration Readiness — usage report for Jira Data Center
// https://github.com/SR-Saunders-CS/scriptrunner-jira-dc-to-cloud-readiness
// Run from: ScriptRunner Script Console (Jira Data Center)  |  Output: HTML
//
// ⚠ BEST-EFFORT PROOF OF CONCEPT — NOT A SUPPORTED PRODUCT
//   • Provided as-is, on a best-effort basis. Not maintained, not supported,
//     and not an official Adaptavist or Atlassian tool. No warranty.
//   • Read-only: makes no changes to Jira data or configuration.
//   • Advice only: results are indicative, not authoritative. Verify before acting.
//   • Does NOT replace the ScriptRunner Migration Suite. Its Analyse and Assess
//     tool shows what can be brought to Cloud and how; you still analyse,
//     convert and deploy there.
//   • Network: only "Full" depth (or Custom with CUSTOM_MARKETPLACE_LOOKUP = true)
//     sends plugin keys to marketplace.atlassian.com. Quick and Standard make
//     no network calls.
//
// WHAT IT DOES
//   Shows how much each ScriptRunner script is used on Data Center — on how
//   many days it ran in the last N days, and when it last ran — so customers can consider retiring
//   unused scripts BEFORE converting anything. Then lists wider Jira areas,
//   ordered by relevance to ScriptRunner. Usage only: it never judges what can
//   or cannot move to Cloud.
//
// HOW TO RUN
//   1. Paste this whole file into the Script Console and click Run. The options
//      form appears above the script.
//   2. Choose options, or leave them blank for the defaults (Quick depth, 50s
//      time limit). Click Run again.
//   3. Read the HTML report below the console. Start with "Read this first".
// ═══════════════════════════════════════════════════════════════════════════

import com.atlassian.applinks.api.ApplicationLink
import com.atlassian.applinks.api.ApplicationLinkService
import com.atlassian.crowd.embedded.api.Directory
import com.atlassian.crowd.manager.directory.DirectoryManager
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.util.JiraHome
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.fields.FieldManager
import com.atlassian.jira.issue.fields.OrderableField
import com.atlassian.jira.issue.fields.config.manager.IssueTypeSchemeManager
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager
import com.atlassian.jira.issue.fields.screen.FieldScreenManager
import com.atlassian.jira.issue.security.IssueSecuritySchemeManager
import com.atlassian.jira.project.Project
import com.atlassian.jira.security.groups.GroupManager
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.util.BuildUtilsInfo
import com.atlassian.jira.workflow.JiraWorkflow
import com.atlassian.jira.workflow.WorkflowManager
import com.atlassian.jira.workflow.WorkflowSchemeManager
import com.atlassian.plugin.Plugin
import com.onresolve.jira.behaviours.BehaviourManager
import com.onresolve.scriptrunner.canned.util.OutputFormatter
import com.onresolve.scriptrunner.parameters.annotation.Checkbox
import com.onresolve.scriptrunner.parameters.annotation.NumberInput
import com.onresolve.scriptrunner.parameters.annotation.Select
import com.onresolve.scriptrunner.parameters.annotation.ShortTextInput
import com.onresolve.scriptrunner.parameters.annotation.meta.Option
import com.onresolve.scriptrunner.scheduled.ScheduledScriptJobManager
import com.onresolve.scriptrunner.scheduled.model.AbstractScheduledJobCommand
import com.opensymphony.workflow.loader.ActionDescriptor
import com.opensymphony.workflow.loader.ConditionalResultDescriptor
import com.opensymphony.workflow.loader.ConditionsDescriptor
import org.rrd4j.ConsolFun
import org.rrd4j.core.FetchData
import org.rrd4j.core.RrdDb

// ╔══════════════════════════════════════════════════════════════════════╗
// ║  FORM — appears as fields above the Script Console when you paste    ║
// ║  this script (ScriptRunner Dynamic Forms). Every field is optional:  ║
// ║  leave it blank and the default shown in its description is used.   ║
// ╚══════════════════════════════════════════════════════════════════════╝
@Select(
        label = '1. How thorough should the report be?',
        description = 'Why we ask: deeper checks give a fuller picture but take longer. Every option includes ScriptRunner usage. ' +
                'Quick = ScriptRunner usage plus Jira counts; fastest, no internet needed. ' +
                'Standard = Quick plus checking every user\'s email address; slower on very large instances. ' +
                'Full = Standard plus looking up each app on the Atlassian Marketplace; needs internet access from the Jira server. ' +
                'Custom = uses the settings inside the script. Blank = Quick.',
        options = [
                @Option(label = 'Quick — ScriptRunner usage + Jira counts (recommended first run)', value = 'QUICK'),
                @Option(label = 'Standard — adds user email checks', value = 'STANDARD'),
                @Option(label = 'Full — adds Marketplace app lookups (needs internet)', value = 'FULL'),
                @Option(label = 'Custom — uses the settings inside the script', value = 'CUSTOM'),
        ]
)
String FORM_DEPTH

@NumberInput(label = '2. Maximum run time (seconds)',
        description = 'Why we ask: on large instances the report can take a while, and browsers or proxies often stop waiting ' +
                '(commonly after about 60 seconds), losing the result. After this many seconds the report stops starting new checks, ' +
                'shows what it has, and lists anything it skipped so you can re-run just those. ' +
                'Effect: lower = safer but may skip checks; higher = more complete but riskier. Blank = 50. ' +
                'Large instance? First run at Quick with options 5, 6 and 7 ticked (ScriptRunner usage only).')
Integer FORM_TIME_BUDGET

@NumberInput(label = '3. Treat a ScriptRunner script as unused after (days)',
        description = 'Why we ask: scripts that have not run recently are worth considering for retirement ' +
                'instead of migrate, which can shrink the migration. Scripts with no recorded run in this many days are marked DORMANT. ' +
                'Effect: shorter = more scripts flagged as unused; longer = fewer. Blank = 90.')
Integer FORM_DORMANT_DAYS

@ShortTextInput(label = '4. Script Listener names (optional)',
        description = 'Why we ask: ScriptRunner cannot list listener names automatically, so listeners ' +
                'appear as "Not matched" in the report. Effect: entering names labels them properly. ' +
                'Format: uuid=Name; uuid=Name. Find a UUID in SR admin → Listeners → Edit → the last part of the browser URL. Blank = skip.')
String FORM_LISTENER_NAMES

// "Leave out" boxes: by default the report shows everything, ScriptRunner first.
// Tick a box to leave that group out — it is neither checked nor shown, so the run is faster.
@Checkbox(label = '5. Leave out: Jira areas that host ScriptRunner scripts',
        description = 'Workflows (where post-functions live), custom fields (where script fields live) and projects (where scripts apply). ' +
                'Why you might keep it: it explains where the scripts in the ScriptRunner section are used. Unticked = included.')
Boolean FORM_HIDE_SR_CONTEXT

@Checkbox(label = '6. Leave out: wider migration readiness',
        description = 'Apps, application links, users, user directories, issue volume, issue security and server timezone. ' +
                'Why you might keep it: these affect the overall Jira migration plan, beyond ScriptRunner. Unticked = included.')
Boolean FORM_HIDE_READINESS

@Checkbox(label = '7. Leave out: instance inventory',
        description = 'System information, groups, issue types and statuses, schemes, field configurations and screens — counts for information. ' +
                'Why you might keep it: a complete record of the instance. Unticked = included.')
Boolean FORM_HIDE_INVENTORY

// ╔══════════════════════════════════════════════════════════════════════╗
// ║  PRESETS — what each Report depth runs                               ║
// ╚══════════════════════════════════════════════════════════════════════╝
// Cheap checks: API counts, safe on any size of instance.
def CHEAP_CHECKS = [
        'CHECK_01_SYSTEM_INFO', 'CHECK_02_USERS', 'CHECK_03_DIRECTORIES', 'CHECK_04_GROUPS',
        'CHECK_05_PROJECTS', 'CHECK_06_ISSUES', 'CHECK_07_CONSTANTS', 'CHECK_08_WORKFLOWS',
        'CHECK_09_WORKFLOW_SCHEMES', 'CHECK_10_PERMISSION_SCHEMES', 'CHECK_11_ISSUETYPE_SCHEMES',
        'CHECK_12_FIELD_CONFIGS', 'CHECK_13_SCREENS', 'CHECK_14_CUSTOM_FIELDS', 'CHECK_15_APPS',
        'CHECK_16_APP_LINKS', 'CHECK_17_1_TIMEZONE', 'CHECK_17_2_ISSUE_SECURITY',
]
def PRESETS = [
        //            checks to run                                          user email scan  Marketplace lookup
        QUICK   : [checks: CHEAP_CHECKS + ['CHECK_17_3_SCRIPTRUNNER_USAGE'], userScan: false, marketplace: false],
        STANDARD: [checks: CHEAP_CHECKS + ['CHECK_17_3_SCRIPTRUNNER_USAGE'], userScan: true,  marketplace: false],
        FULL    : [checks: CHEAP_CHECKS + ['CHECK_17_3_SCRIPTRUNNER_USAGE'], userScan: true,  marketplace: true],
]

// ── CUSTOM — used only when Report depth = Custom ────────────────────────────
def CUSTOM_CHECKS = [
        CHECK_01_SYSTEM_INFO          : true,  // Jira version, build number, base URL
        CHECK_02_USERS                : true,  // User counts (+ email checks if CUSTOM_USER_EMAIL_SCAN)
        CHECK_03_DIRECTORIES          : true,  // Internal vs external (LDAP/AD) directories
        CHECK_04_GROUPS               : true,  // Total group count
        CHECK_05_PROJECTS             : true,  // Project counts, types, categories, missing leads
        CHECK_06_ISSUES               : true,  // Total issue count
        CHECK_07_CONSTANTS            : true,  // Issue types, statuses, priorities, resolutions
        CHECK_08_WORKFLOWS            : true,  // Workflow counts and unpublished drafts
        CHECK_09_WORKFLOW_SCHEMES     : true,  // Workflow scheme counts and unused schemes
        CHECK_10_PERMISSION_SCHEMES   : true,  // Permission scheme counts and unused schemes
        CHECK_11_ISSUETYPE_SCHEMES    : true,  // Issue type scheme count
        CHECK_12_FIELD_CONFIGS        : true,  // Field configuration scheme and layout counts
        CHECK_13_SCREENS              : true,  // Screen count
        CHECK_14_CUSTOM_FIELDS        : true,  // Custom field counts and breakdown by type
        CHECK_15_APPS                 : true,  // Installed apps, grouped
        CHECK_16_APP_LINKS            : true,  // Configured application links
        CHECK_17_1_TIMEZONE           : true,  // JVM default timezone
        CHECK_17_2_ISSUE_SECURITY     : true,  // Issue security schemes
        CHECK_17_3_SCRIPTRUNNER_USAGE : true,  // ScriptRunner usage from RRD files
]
def CUSTOM_USER_EMAIL_SCAN   = true   // loads every user into memory — slow on very large instances
def CUSTOM_MARKETPLACE_LOOKUP = false  // outbound calls to marketplace.atlassian.com

// ── Resolve the form + preset into the settings the script uses ──────────────
String DEPTH = (FORM_DEPTH ?: 'QUICK').toUpperCase()
def config
boolean USER_EMAIL_SCAN
boolean MARKETPLACE_LOOKUP
if (DEPTH == 'CUSTOM') {
    config             = new LinkedHashMap(CUSTOM_CHECKS)
    USER_EMAIL_SCAN    = CUSTOM_USER_EMAIL_SCAN
    MARKETPLACE_LOOKUP = CUSTOM_MARKETPLACE_LOOKUP
} else {
    def preset = PRESETS[DEPTH] ?: PRESETS.QUICK
    config             = CUSTOM_CHECKS.collectEntries { k, v -> [k, (preset.checks as List).contains(k)] }
    USER_EMAIL_SCAN    = preset.userScan as boolean
    MARKETPLACE_LOOKUP = preset.marketplace as boolean
}
// ── Report groups, ordered by relevance to a ScriptRunner migration ─────────
def REPORT_GROUPS = [
        SR       : [title: 'ScriptRunner',
                    why  : 'The scripts themselves: what exists, what is used, and what could be retired before migrating.',
                    checks: ['CHECK_17_3_SCRIPTRUNNER_USAGE']],
        SR_CTX   : [title: 'Jira areas that host ScriptRunner scripts',
                    why  : 'Where the scripts above live and apply: workflows, custom fields and projects.',
                    checks: ['CHECK_08_WORKFLOWS', 'CHECK_14_CUSTOM_FIELDS', 'CHECK_05_PROJECTS']],
        READINESS: [title: 'Wider migration readiness',
                    why  : 'Things that affect the overall Jira migration plan, beyond ScriptRunner.',
                    checks: ['CHECK_15_APPS', 'CHECK_16_APP_LINKS', 'CHECK_02_USERS', 'CHECK_03_DIRECTORIES',
                             'CHECK_06_ISSUES', 'CHECK_17_2_ISSUE_SECURITY', 'CHECK_17_1_TIMEZONE']],
        INVENTORY: [title: 'Instance inventory',
                    why  : 'Counts for information, giving a complete record of the instance.',
                    checks: ['CHECK_01_SYSTEM_INFO', 'CHECK_04_GROUPS', 'CHECK_07_CONSTANTS', 'CHECK_09_WORKFLOW_SCHEMES',
                             'CHECK_10_PERMISSION_SCHEMES', 'CHECK_11_ISSUETYPE_SCHEMES', 'CHECK_12_FIELD_CONFIGS', 'CHECK_13_SCREENS']],
]
Set<String> hiddenGroups = [] as Set<String>
if (FORM_HIDE_SR_CONTEXT) hiddenGroups << 'SR_CTX'
if (FORM_HIDE_READINESS)  hiddenGroups << 'READINESS'
if (FORM_HIDE_INVENTORY)  hiddenGroups << 'INVENTORY'
hiddenGroups.each { String g -> (REPORT_GROUPS[g].checks as List).each { config[it] = false } }

int  TIME_BUDGET_SEC = (FORM_TIME_BUDGET && FORM_TIME_BUDGET > 0) ? FORM_TIME_BUDGET : 50
def  SR_DORMANT_DAYS = (FORM_DORMANT_DAYS && FORM_DORMANT_DAYS > 0) ? FORM_DORMANT_DAYS : 90
Map<String, String> SR_LISTENER_NAMES = [:]
(FORM_LISTENER_NAMES ?: '').split(/[;\n]/).each { String pair ->
    def parts = pair.split('=', 2)
    if (parts.size() == 2 && parts[0].trim()) SR_LISTENER_NAMES[parts[0].trim()] = parts[1].trim()
}

// ╔══════════════════════════════════════════════════════════════════════╗
// ║  ADVANCED SETTINGS — most users never need to change these           ║
// ╚══════════════════════════════════════════════════════════════════════╝
def MARKETPLACE_CHECK_MAX_APPS = 50   // cap on Marketplace lookups per run (each can take ~10s on timeout)

// A plugin is treated as bundled if its key starts with one of these prefixes
// OR its vendor name contains "Atlassian". Plugins with no vendor information
// are NOT assumed to be bundled — they are flagged, as they may be in-house.
def CORE_APP_KEY_PREFIXES = [
        'com.atlassian.',
        'com.pyxis.greenhopper',   // Jira Software
]

def LANGUAGE_PACK_PLUGIN_KEYS = [
        'cs', 'da', 'de', 'es', 'et', 'fi', 'fr', 'hu', 'is', 'it', 'ja', 'ko',
        'nb', 'nl', 'pl', 'pt_BR', 'ro', 'ru', 'sk', 'sv', 'tr', 'zh_TW', 'zh_CN',
].collect { "com.atlassian.jira.jira-languages-${it}".toString() }

// Long tables show this many rows, then "…and N more", so the page stays fast and scannable.
def MAX_ROWS = 50

// Heuristic thresholds (rules of thumb, not Atlassian limits)
def THRESHOLD_LARGE_USERS         = 50_000
def THRESHOLD_LARGE_ISSUES        = 500_000L
def THRESHOLD_LARGE_CUSTOM_FIELDS = 500

// ── Time budget helpers ─────────────────────────────────────────────────────
// The budget is checked BETWEEN checks (and inside the Marketplace and RRD
// loops). A single slow API call cannot be interrupted once it has started.
final long RUN_START = System.currentTimeMillis()
def elapsedSec  = { -> ((System.currentTimeMillis() - RUN_START) / 1000L) as long }
def overBudget  = { -> elapsedSec() >= TIME_BUDGET_SEC }
Set<String>  ran          = [] as Set<String>
List<String> timeSkipped  = []
List<List>   checkTimings = []   // [checkKey, startMillis]
def runCheck = { String key ->
    if (!config[key]) return false
    if (overBudget()) {
        timeSkipped << key
        log.warn "    ⏱ ${key} not started — time budget (${TIME_BUDGET_SEC}s) reached"
        return false
    }
    ran << key
    checkTimings << [key, System.currentTimeMillis()]
    return true
}
// ══════════════════════════════════════════════════════════════════════════

// ── Service lookups ──────────────────────────────────────────────────────────
def appProps               = ComponentAccessor.getApplicationProperties()
def buildInfo              = ComponentAccessor.getComponent(BuildUtilsInfo)
def userManager            = ComponentAccessor.getUserManager()
def groupManager           = ComponentAccessor.getComponent(GroupManager)
def projectManager         = ComponentAccessor.getProjectManager()
def issueManager           = ComponentAccessor.getComponent(IssueManager)
def constantsManager       = ComponentAccessor.getConstantsManager()
def workflowManager        = ComponentAccessor.getComponent(WorkflowManager)
def wfSchemeManager        = ComponentAccessor.getComponent(WorkflowSchemeManager)
def permSchemeManager      = ComponentAccessor.getPermissionSchemeManager()
def issueTypeSchemeManager = ComponentAccessor.getComponent(IssueTypeSchemeManager)
def fieldLayoutManager     = ComponentAccessor.getComponent(FieldLayoutManager)
def fieldScreenManager     = ComponentAccessor.getComponent(FieldScreenManager)
def fieldManager           = ComponentAccessor.getComponent(FieldManager)
def pluginAccessor         = ComponentAccessor.getPluginAccessor()
def directoryManager       = ComponentAccessor.getComponent(DirectoryManager)
def appLinkService         = ComponentAccessor.getOSGiComponentInstanceOfType(ApplicationLinkService)
def issueSecMgr            = ComponentAccessor.getComponent(IssueSecuritySchemeManager)

// ── Progress logging helpers ─────────────────────────────────────────────────
def section  = { String num, String title ->
    log.info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log.info "[${num}] ${title}"
    log.info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
}
def skipped  = { String num, String title -> log.info "[${num}] ${title} — SKIPPED" }
def progress = { String msg -> log.info "    ↳ ${msg}" }
def warn     = { String msg -> log.warn "    ⚠ ${msg}" }
def done     = { String msg -> log.info "    ✔ ${msg}" }

log.info "============================================================"
log.info "  MIGRATION READINESS + SR USAGE — BEST-EFFORT POC"
log.info "  ${new Date().format('yyyy-MM-dd HH:mm:ss z')}"
log.info "============================================================"

// ═══════════════════════════════════════════════════════════════════════════
// SCRIPTRUNNER FIRST — runs before anything else so the time budget is spent
// on the most important part of the report.
// ═══════════════════════════════════════════════════════════════════════════
// 17.3 ScriptRunner usage (RRD)
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
def srWarnings  = [] as List<String>
def srNodeNames = [] as List<String>
int srRrdFiles  = 0
Map srRrdMeta = [:]                              // detected value type and step sizes of the usage files
Set<String> srMissingDs = [] as Set<String>      // usage files without a 'count' data source
Set<String> srNotReadKeys = [] as Set<String>   // RRD files skipped because the time budget ran out
boolean srRrdUnavailable = false                 // RRD folder missing or unreadable
boolean srBudgetHit = false
List<String> srNodesMissing = []                 // cluster nodes Jira knows about with no usage folder
if (runCheck('CHECK_17_3_SCRIPTRUNNER_USAGE')) {
    section('17.3', 'ScriptRunner Usage (RRD)')

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
            if (!srRrdMeta) srRrdMeta = [dsType: dsType, dbStep: dbStep, fineStep: fine.getStep(), dailyStep: daily.getStep(),
                                         dailyOk: dailyOk]
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

    try {
        File rrdRoot = new File(ComponentAccessor.getComponent(JiraHome).home, 'scriptrunner/rrd')
        if (!rrdRoot.exists()) {
            srRrdUnavailable = true
            srWarnings << "RRD directory not found (${rrdRoot}). Usage unavailable, so no script is marked as unused.".toString()
        } else {
            List<File> dirs = (rrdRoot.listFiles()?.findAll { it.isDirectory() }?.sort { it.name } ?: []) as List<File>
            if (rrdRoot.listFiles()?.any { it.isFile() && it.name.endsWith('.rrd4j') }) dirs.add(0, rrdRoot)
            dirs.each { File dir ->
                srNodeNames << (dir == rrdRoot ? '(flat layout)' : dir.name)
                dir.listFiles()?.findAll { it.isFile() && it.name.endsWith('.rrd4j') }?.each { File f ->
                    String key = f.name.substring(0, f.name.length() - '.rrd4j'.length())
                    if (overBudget()) { srBudgetHit = true; srNotReadKeys << key; return }
                    srRrdFiles++
                    Map r = readRrd(f) as Map
                    if (r == null) return
                    Map ex = rrd[key]
                    // Nodes: days are combined (a day counts once, whichever node ran it).
                    rrd[key] = ex == null ? r : [
                            days  : ((ex.days as Set<Long>) + (r.days as Set<Long>)) as Set<Long>,
                            last  : Math.max(ex.last as long, r.last as long),
                    ]
                }
            }
            // Cluster check: every node Jira knows about should have a usage folder. If any is missing,
            // usage on that node is unknown, so no script is judged unused (see addRow).
            try {
                def cm = ComponentAccessor.getComponent(com.atlassian.jira.cluster.ClusterManager)
                if (cm?.isClustered()) {
                    Set<String> folders = srNodeNames as Set<String>
                    srNodesMissing = (cm.getAllNodes()?.collect { it.getNodeId() as String }?.findAll { it && !folders.contains(it) }?.sort() ?: []) as List<String>
                    if (srNodesMissing) srWarnings << "No usage folder found for cluster node(s): ${srNodesMissing.join(', ')}. Usage on those nodes is unknown, so no script is marked as unused. A node that has never run a script also has no folder.".toString()
                }
            } catch (Exception e) {
                srWarnings << "Could not check cluster nodes: ${e.message}. Compare \"Nodes read\" with Jira admin → System → Clustering.".toString()
            }
            if (srBudgetHit) srWarnings << "Time limit reached while reading usage files: ${srNotReadKeys.size()} file(s) were not read. Those scripts are shown as \"Not read\", never as unused. Re-run with a larger time limit.".toString()
            if (srMissingDs) srWarnings << "${srMissingDs.size()} usage file(s) had no 'count' data source and were skipped (e.g. ${srMissingDs.take(3).join(', ')}). The file format may differ on this ScriptRunner version.".toString()
            done("Nodes: ${srNodeNames.join(', ') ?: 'none'}  |  RRD files read: ${srRrdFiles}  |  Value type: ${srRrdMeta.dsType ?: 'n/a'}")
        }
    } catch (Exception e) {
        srRrdUnavailable = true
        srWarnings << "RRD load failed: ${e.message}. No script is marked as unused.".toString()
    }

    Set<String> attributed = [] as Set<String>
    def addRow = { String type, String name, String where, String key, boolean tracked ->
        Map d = key ? rrd[key] : null
        if (key) attributed << key
        String status
        if (!tracked)               status = 'NOT TRACKED'
        // Any unread file for this script (e.g. one node read, another cut off by the time limit)
        // means usage is incomplete, so it is never judged unused.
        else if (srRrdUnavailable || (key && srNotReadKeys.contains(key))) status = 'NOT READ'
        // No usage file and a file with no recorded run mean the same thing: no run found.
        else if (d == null || (d.last as long) == 0L) status = 'NEVER RUN'
        else if (Math.floorDiv((d.last as long) - 1L, DAY) > todayKey - (SR_DORMANT_DAYS as long)) status = 'ACTIVE'
        else                        status = 'DORMANT'
        // A cluster node's usage folder is missing: "no run found" can't be trusted, so not judged unused.
        if (srNodesMissing && status in ['DORMANT', 'NEVER RUN']) status = 'NOT READ'
        Set<Long> days  = d ? (d.days as Set<Long>) : ([] as Set<Long>)
        Long lastDay    = d && (d.last as long) > 0L ? Math.floorDiv((d.last as long) - 1L, DAY) : null
        int dXn         = daysIn(days, SR_DORMANT_DAYS as long)
        srRows << [
                type   : type, name: (name ?: '(unnamed)').trim(), where: where ?: '—', key: key ?: '—',
                d30    : d ? "${daysIn(days, 30L)}".toString() : (tracked ? '0' : '—'),
                d90    : d ? "${daysIn(days, 90L)}".toString() : (tracked ? '0' : '—'),
                dX     : d ? "${dXn}".toString() : (tracked ? '0' : '—'),
                lastRun: lastDay != null ? fmtDay(lastDay) : (tracked ? 'No run recorded' : 'Not tracked'),
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
            wfSchemeManager.getWorkflowMap(proj).values().toUnique().each { String wfName ->
                wfToProjects.get(wfName, []) << proj.key
            }
        }
        Set<String> seen = [] as Set<String>
        workflowManager.getWorkflows().each { JiraWorkflow wf ->
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
                boolean tracked = fid != null && (!mustHaveFile || rrd.containsKey(fid))
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
                    cf.getConfigurationSchemes().each { scheme ->
                        String where = scheme.isGlobal() ? 'All projects (global)'
                                : (scheme.getAssociatedProjectObjects()*.key.sort().join(', ') ?: 'No projects')
                        addRow('Script Field', cf.name, where, scheme.id?.toString(), true)
                    }
                }
    } catch (Exception e) {
        srWarnings << "Script fields could not be listed: ${e.message}".toString()
    }

    // Behaviours (inventory only — SR does not record executions)
    try {
        def bm = ComponentAccessor.getOSGiComponentInstanceOfType(BehaviourManager)
        (bm?.getAllConfigs() as Map)?.each { id, state ->
            addRow('Behaviour', (state?.getAt('name') ?: id) as String,
                    state?.getAt('disabled') ? 'Disabled in SR' : 'Not tracked by SR', null, false)
        }
    } catch (Throwable e) {
        srWarnings << "Behaviours could not be listed: ${e.message}".toString()
    }

    // Everything left on disk: REST endpoints, JQL functions, listeners, deleted scripts
    String UUID_RE = /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/
    String REST_RE = /(?i)(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)-.+/
    (rrd.keySet() - attributed).sort().each { String k ->
        if (k ==~ REST_RE) {
            addRow('REST Endpoint', k, 'Not project-scoped', k, true)
        } else if (k ==~ UUID_RE) {
            if (SR_LISTENER_NAMES.containsKey(k)) addRow('Script Listener', SR_LISTENER_NAMES[k], 'Scope not listed for listeners', k, true)
            else addRow('Not matched to a configured script', k, 'Could be a listener, another ScriptRunner feature, or a deleted script', k, true)
        } else if (k ==~ /\d+/) {
            addRow('Not matched to a configured script', k, 'Numeric ID: could be a deleted script field', k, true)
        } else {
            addRow('JQL Function', k, 'Not project-scoped', k, true)
        }
    }

    def byStatus = srRows.countBy { it.status }
    done("Scripts: ${srRows.size()}  |  " + ['ACTIVE', 'DORMANT', 'NEVER RUN', 'NO DATA', 'NOT TRACKED']
            .collect { "${it}: ${byStatus[it] ?: 0}" }.join('  |  '))
    srWarnings.each { warn(it) }
    srRows.each { Map r -> log.info "    [SR] ${r.status} | ${r.type} | ${r.name} | days ran ${SR_DORMANT_DAYS}d ${r.dX} | 30d ${r.d30} | 90d ${r.d90} | last ${r.lastRun}${r.daysAgo != null ? " (${r.daysAgo} days ago)" : ''}" }
} else { skipped('17.3', 'ScriptRunner Usage (RRD)') }

// ═══════════════════════════════════════════════════════════════════════════
// PART 1 — INSTANCE INVENTORY (checks 1–16)
// ═══════════════════════════════════════════════════════════════════════════

// 1. System info
def jiraVersion = '(skipped)'
def buildNumber = '(skipped)'
def baseUrl     = '(skipped)'
if (runCheck('CHECK_01_SYSTEM_INFO')) {
    section('01', 'System Information')
    jiraVersion = buildInfo.version
    buildNumber = buildInfo.currentBuildNumber
    baseUrl     = appProps.getString('jira.baseurl') ?: appProps.jiraBaseUrl
    done("Version: ${jiraVersion}  |  Build: ${buildNumber}  |  URL: ${baseUrl}")
} else { skipped('01', 'System Information') }

// 2. Users
def totalUsers       = 0
def activeUsers      = 0
def inactiveUsers    = 0
def usersWithNoEmail = [] as List<ApplicationUser>
def duplicateEmails  = [:] as Map
boolean userScanRan = false
if (runCheck('CHECK_02_USERS')) {
    section('02', 'Users')
    totalUsers = userManager.getTotalUserCount()
    if (USER_EMAIL_SCAN && !overBudget()) {
        progress("Total users: ${totalUsers} — loading full user list for email checks (slow on very large instances)...")
        @SuppressWarnings('deprecation')
        def allUsers  = userManager.getAllApplicationUsers() as List<ApplicationUser>
        activeUsers   = allUsers.count { it.active }
        inactiveUsers = allUsers.count { !it.active }
        usersWithNoEmail = allUsers.findAll { !it.emailAddress?.trim() }
        duplicateEmails  = allUsers
                .findAll { it.emailAddress?.trim() }
                .groupBy { it.emailAddress.toLowerCase().trim() }
                .findAll { _, users -> users.size() > 1 }
        userScanRan = true
        done("Active: ${activeUsers}  |  Inactive: ${inactiveUsers}  |  No email: ${usersWithNoEmail.size()}  |  Duplicate email groups: ${duplicateEmails.size()}")
    } else {
        done("Total users: ${totalUsers} (email checks not run at this report depth or time budget reached)")
    }
} else { skipped('02', 'Users') }

// 3. User directories
def allDirectories = [] as List<Directory>
def externalDirs   = [] as List<Directory>
if (runCheck('CHECK_03_DIRECTORIES')) {
    section('03', 'User Directories')
    allDirectories = directoryManager.findAllDirectories()
    externalDirs   = allDirectories.findAll { it.type?.toString() != 'INTERNAL' }
    done("Total: ${allDirectories.size()}  |  External: ${externalDirs.size()}")
    externalDirs.each { Directory d -> warn("External directory: [${d.type}] ${d.name}") }
} else { skipped('03', 'User Directories') }

// 4. Groups
def allGroupNames = [] as Collection<String>
if (runCheck('CHECK_04_GROUPS')) {
    section('04', 'Groups')
    allGroupNames = groupManager.getAllGroupNames()
    done("Total groups: ${allGroupNames.size()}")
} else { skipped('04', 'Groups') }

// 5. Projects
def allProjects        = [] as List<Project>
def archivedProjects   = [] as List<Project>
def projectsByType     = [:] as Map
def projectsNoLead     = [] as List<Project>
def projectCategories  = [] as Collection
def projectsNoCategory = [] as Collection<Project>
if (runCheck('CHECK_05_PROJECTS')) {
    section('05', 'Projects')
    allProjects        = projectManager.getProjectObjects()
    archivedProjects   = projectManager.getArchivedProjects()
    projectsByType     = allProjects.groupBy { it.projectTypeKey?.key ?: 'unknown' }
    projectsNoLead     = allProjects.findAll { !it.projectLead }
    projectCategories  = projectManager.getAllProjectCategories()
    projectsNoCategory = projectManager.getProjectObjectsWithNoCategory()
    done("Active: ${allProjects.size()}  |  Archived: ${archivedProjects.size()}  |  No lead: ${projectsNoLead.size()}")
} else { skipped('05', 'Projects') }

// 6. Issues
def totalIssues = 0L
if (runCheck('CHECK_06_ISSUES')) {
    section('06', 'Issues')
    totalIssues = issueManager.getIssueCount()
    done("Total issues: ${totalIssues}")
} else { skipped('06', 'Issues') }

// 7. Issue types, statuses, priorities, resolutions
def issueTypes  = [] as Collection
def subTasks    = [] as Collection
def statuses    = [] as Collection
def priorities  = [] as Collection
def resolutions = [] as Collection
if (runCheck('CHECK_07_CONSTANTS')) {
    section('07', 'Issue Types, Statuses, Priorities & Resolutions')
    issueTypes  = constantsManager.getAllIssueTypeObjects()
    subTasks    = constantsManager.getSubTaskIssueTypeObjects()
    statuses    = constantsManager.getStatuses()
    priorities  = constantsManager.getPriorities()
    resolutions = constantsManager.getResolutions()
    done("Issue types: ${issueTypes.size()}  |  Statuses: ${statuses.size()}  |  Priorities: ${priorities.size()}  |  Resolutions: ${resolutions.size()}")
} else { skipped('07', 'Issue Types, Statuses, Priorities & Resolutions') }

// 8. Workflows
def allWorkflows       = [] as List<JiraWorkflow>
def activeWorkflows    = [] as List<JiraWorkflow>
def workflowsWithDraft = [] as List<JiraWorkflow>
if (runCheck('CHECK_08_WORKFLOWS')) {
    section('08', 'Workflows')
    allWorkflows       = workflowManager.getWorkflows() as List<JiraWorkflow>
    activeWorkflows    = workflowManager.getActiveWorkflows() as List<JiraWorkflow>
    workflowsWithDraft = allWorkflows.findAll { it.hasDraftWorkflow() } as List<JiraWorkflow>
    done("Total: ${allWorkflows.size()}  |  Active: ${activeWorkflows.size()}  |  With drafts: ${workflowsWithDraft.size()}")
} else { skipped('08', 'Workflows') }

// 9. Workflow schemes
def allWfSchemes        = [] as List
def unassignedWfSchemes = [] as List
if (runCheck('CHECK_09_WORKFLOW_SCHEMES')) {
    section('09', 'Workflow Schemes')
    allWfSchemes        = wfSchemeManager.getAssignableSchemes().toList()
    unassignedWfSchemes = allWfSchemes.findAll { wfSchemeManager.getProjectsUsing(it).isEmpty() }
    done("Total: ${allWfSchemes.size()}  |  Unassigned: ${unassignedWfSchemes.size()}")
} else { skipped('09', 'Workflow Schemes') }

// 10. Permission schemes
def allPermSchemes        = [] as List
def unassignedPermSchemes = [] as List
if (runCheck('CHECK_10_PERMISSION_SCHEMES')) {
    section('10', 'Permission Schemes')
    allPermSchemes        = permSchemeManager.getSchemeObjects().toList()
    unassignedPermSchemes = permSchemeManager.getUnassociatedSchemes().toList()
    done("Total: ${allPermSchemes.size()}  |  Unassigned: ${unassignedPermSchemes.size()}")
} else { skipped('10', 'Permission Schemes') }

// 11. Issue type schemes
def allIssueTypeSchemes = [] as List
if (runCheck('CHECK_11_ISSUETYPE_SCHEMES')) {
    section('11', 'Issue Type Schemes')
    allIssueTypeSchemes = issueTypeSchemeManager.getAllSchemes().toList()
    done("Total: ${allIssueTypeSchemes.size()}")
} else { skipped('11', 'Issue Type Schemes') }

// 12. Field configurations
def allFieldLayoutSchemes = [] as List
def allFieldLayouts       = [] as List
if (runCheck('CHECK_12_FIELD_CONFIGS')) {
    section('12', 'Field Configurations')
    allFieldLayoutSchemes = fieldLayoutManager.getFieldLayoutSchemes().toList()
    allFieldLayouts       = fieldLayoutManager.getEditableFieldLayouts().toList()
    done("Schemes: ${allFieldLayoutSchemes.size()}  |  Configurations: ${allFieldLayouts.size()}")
} else { skipped('12', 'Field Configurations') }

// 13. Screens
def allScreens = [] as Collection
if (runCheck('CHECK_13_SCREENS')) {
    section('13', 'Screens')
    allScreens = fieldScreenManager.getFieldScreens()
    done("Total: ${allScreens.size()}")
} else { skipped('13', 'Screens') }

// 14. Custom fields
def allCustomFields = [] as List<CustomField>
def cfByType        = [:] as Map
if (runCheck('CHECK_14_CUSTOM_FIELDS')) {
    section('14', 'Custom Fields')
    allCustomFields = fieldManager.getOrderableFields()
            .findAll { fieldManager.isCustomField(it) }
            .collect { OrderableField f -> fieldManager.getCustomField(f.id) }
            .findAll { it != null } as List<CustomField>
    cfByType = allCustomFields.groupBy { it.customFieldType?.key ?: 'unknown' }
    done("Total: ${allCustomFields.size()}")
} else { skipped('14', 'Custom Fields') }

// 15. Installed apps
//   1. Language packs   2. Bundled Atlassian apps
//   3. Third-party, confirmed on Marketplace
//   4. Third-party, not confirmed (not listed / private / in-house / lookup failed or off)
def languagePackPlugins = [] as List<Plugin>
def corePlugins         = [] as List<Plugin>
def marketplacePlugins  = []  // [plugin, latestVersion, versionMatches]
def unidentifiedPlugins = []  // looked up, NOT found on the Marketplace: [plugin, checkResult, noVendorInfo]
def notCheckedPlugins   = []  // not looked up (lookup off, limit, time budget) or lookup failed: [plugin, checkResult, noVendorInfo]
if (runCheck('CHECK_15_APPS')) {
    section('15', 'Installed Apps')
    def nonSystem = pluginAccessor.getEnabledPlugins()
            .findAll { Plugin p -> !pluginAccessor.isSystemPlugin(p.key) } as List<Plugin>
    def thirdParty = [] as List<Plugin>
    nonSystem.each { Plugin p ->
        def key        = p.key ?: ''
        def name       = (p.name ?: '').toLowerCase(Locale.ENGLISH)
        def vendorName = (p.pluginInformation?.vendorName ?: '').toLowerCase(Locale.ENGLISH)
        if (LANGUAGE_PACK_PLUGIN_KEYS.contains(key) || name.contains('language pack')) {
            languagePackPlugins << p
        } else if (CORE_APP_KEY_PREFIXES.any { String pre -> key.startsWith(pre) } || vendorName.contains('atlassian')) {
            corePlugins << p
        } else {
            thirdParty << p
        }
    }
    done("Language packs: ${languagePackPlugins.size()}  |  Bundled: ${corePlugins.size()}  |  Third-party: ${thirdParty.size()}")

    int checkLimit = MARKETPLACE_CHECK_MAX_APPS as int
    if (MARKETPLACE_LOOKUP && thirdParty) {
        progress("Checking ${Math.min(thirdParty.size(), checkLimit)} of ${thirdParty.size()} third-party app(s) against the Marketplace...")
    }
    thirdParty.eachWithIndex { Plugin p, int idx ->
        boolean noVendor = !p.pluginInformation?.vendorUrl && !p.pluginInformation?.vendorName
        if (!MARKETPLACE_LOOKUP) {
            notCheckedPlugins << [plugin: p, checkResult: 'NOT CHECKED (lookup off)', noVendorInfo: noVendor]
            return
        }
        if (idx >= checkLimit) {
            notCheckedPlugins << [plugin: p, checkResult: 'NOT CHECKED (limit reached)', noVendorInfo: noVendor]
            return
        }
        if (overBudget()) {
            notCheckedPlugins << [plugin: p, checkResult: 'NOT CHECKED (time budget)', noVendorInfo: noVendor]
            return
        }
        def apiUrl = "https://marketplace.atlassian.com/rest/2/addons/${URLEncoder.encode(p.key ?: '', 'UTF-8')}/versions/latest"
        try {
            def conn = new URL(apiUrl).openConnection() as HttpURLConnection
            conn.setRequestMethod('GET')
            conn.setConnectTimeout(5000)
            conn.setReadTimeout(5000)
            int code = conn.responseCode
            if (code == 200) {
                def json   = new groovy.json.JsonSlurper().parse(conn.inputStream) as Map
                def latest = ((json?.get('name') ?: json?.get('version')) ?: 'n/a') as String
                def installed = (p.pluginInformation?.version ?: 'n/a') as String
                marketplacePlugins << [plugin: p, latestVersion: latest, versionMatches: latest == installed]
            } else if (code == 404) {
                unidentifiedPlugins << [plugin: p, checkResult: 'NOT ON MARKETPLACE', noVendorInfo: noVendor]
            } else {
                notCheckedPlugins << [plugin: p, checkResult: "LOOKUP FAILED (HTTP ${code})", noVendorInfo: noVendor]
            }
        } catch (Exception e) {
            notCheckedPlugins << [plugin: p, checkResult: 'LOOKUP FAILED', noVendorInfo: noVendor]
        }
    }
    done("Confirmed on Marketplace: ${marketplacePlugins.size()}  |  Not found: ${unidentifiedPlugins.size()}  |  Not checked: ${notCheckedPlugins.size()}")
} else { skipped('15', 'Installed Apps') }

// 16. Application links
def appLinks = [] as List<ApplicationLink>
if (runCheck('CHECK_16_APP_LINKS')) {
    section('16', 'Application Links')
    appLinks = (appLinkService?.getApplicationLinks()?.toList() ?: []) as List<ApplicationLink>
    done("Total: ${appLinks.size()}")
} else { skipped('16', 'Application Links') }

// ═══════════════════════════════════════════════════════════════════════════
// PART 2 — ADAPTAVIST CHECKS (check 17)
// ═══════════════════════════════════════════════════════════════════════════

// Tempo Timesheets detection (used only to add a review note to 17.1 / 17.2)
def tempoKeys = ['is.origo.jira.plugin.tempo-plugin', 'com.tempoplugin.tempo-timesheets']
boolean tempoInstalled = (pluginAccessor.getEnabledPlugins() as List<Plugin>).any { Plugin p ->
    def n = (p.name ?: '').toLowerCase(Locale.ENGLISH)
    tempoKeys.contains(p.key) || (n.contains('tempo') && n.contains('timesheet'))
}

// 17.1 System timezone
def systemTimezoneId      = '(skipped)'
def systemTimezoneDisplay = '(skipped)'
if (runCheck('CHECK_17_1_TIMEZONE')) {
    section('17.1', 'System Timezone')
    def tz = TimeZone.getDefault()
    systemTimezoneId      = tz.ID
    systemTimezoneDisplay = tz.getDisplayName(Locale.ENGLISH)
    done("Timezone: ${systemTimezoneId} (${systemTimezoneDisplay})${tempoInstalled ? '  |  Tempo Timesheets detected' : ''}")
} else { skipped('17.1', 'System Timezone') }

// 17.2 Issue security schemes
def securitySchemeNames = [] as List<String>
if (runCheck('CHECK_17_2_ISSUE_SECURITY')) {
    section('17.2', 'Issue Security Schemes')
    securitySchemeNames = (issueSecMgr.getSchemeObjects()?.toList() ?: [])
            .collect { s -> (s?.name ?: '(unnamed)') as String } as List<String>
    done("Total: ${securitySchemeNames.size()}")
} else { skipped('17.2', 'Issue Security Schemes') }


// ═══════════════════════════════════════════════════════════════════════════
// PART 3 — ITEMS TO REVIEW (built once, used in the log and the report)
// ═══════════════════════════════════════════════════════════════════════════
def srCount = { String s -> srRows.count { it.status == s } as int }
def srIsUnmatched = { Map r -> (r.type as String).startsWith('Not matched') }
int srUnattributed = srRows.count { srIsUnmatched(it) } as int
int srScripts      = srRows.size() - srUnattributed
def srCountScripts = { List<String> st -> srRows.count { !srIsUnmatched(it) && it.status in st } as int }

// ═══════════════════════════════════════════════════════════════════════════
// SECTION GUIDE — what each section shows, and why it is in the report.
// Shown at the top (traffic lights) and inside each section.
// ═══════════════════════════════════════════════════════════════════════════
def SECTION_INFO = [
        'sec-1'   : [title: 'System information', check: 'CHECK_01_SYSTEM_INFO',
                     what : 'The Jira version, build number and base URL.',
                     why  : 'Identifies exactly which instance and version this report describes, so it can be matched to the right migration plan.'],
        'sec-2'   : [title: 'Users', check: 'CHECK_02_USERS',
                     what : 'How many users there are. At Standard or Full: active and inactive users, and users with a missing or shared email address.',
                     why  : 'Atlassian Cloud identifies people by email address, so missing or shared emails need sorting out before users are moved.'],
        'sec-3'   : [title: 'User directories', check: 'CHECK_03_DIRECTORIES',
                     what : 'Where users come from: Jira\'s own internal directory, or external ones such as LDAP or Active Directory.',
                     why  : 'External directories change how users are brought across, so the migration plan needs to cover them.'],
        'sec-4'   : [title: 'Groups', check: 'CHECK_04_GROUPS',
                     what : 'How many user groups exist.',
                     why  : 'Groups control access. More groups means more permissions to check after the move.'],
        'sec-5'   : [title: 'Projects', check: 'CHECK_05_PROJECTS',
                     what : 'Active and archived projects, project types, categories, and projects with no project lead.',
                     why  : 'ScriptRunner scripts are often set up for specific projects, and migrations are usually planned project by project. Projects with no lead are worth tidying up first.'],
        'sec-6'   : [title: 'Issues', check: 'CHECK_06_ISSUES',
                     what : 'The total number of issues.',
                     why  : 'The main factor in how long moving the data takes. Very large counts need a longer migration window.'],
        'sec-7'   : [title: 'Issue types, statuses, priorities and resolutions', check: 'CHECK_07_CONSTANTS',
                     what : 'How many of each of these building blocks exist.',
                     why  : 'Each one has to exist in Cloud too. Higher counts mean more to match up and check.'],
        'sec-8'   : [title: 'Workflows', check: 'CHECK_08_WORKFLOWS',
                     what : 'All workflows, how many are in use, and any with unpublished draft changes.',
                     why  : 'ScriptRunner post-functions run on workflow transitions, so this shows where those scripts live. A draft is an unfinished change: decide whether to keep or drop it before migrating.'],
        'sec-9'   : [title: 'Workflow schemes', check: 'CHECK_09_WORKFLOW_SCHEMES',
                     what : 'All workflow schemes, and any not used by a project.',
                     why  : 'Unused schemes are clutter that would otherwise be moved and checked. Removing them first keeps the migration simpler.'],
        'sec-10'  : [title: 'Permission schemes', check: 'CHECK_10_PERMISSION_SCHEMES',
                     what : 'All permission schemes, and any not used by a project.',
                     why  : 'Permission schemes control who can do what. Unused ones are clutter worth removing first.'],
        'sec-11'  : [title: 'Issue type schemes', check: 'CHECK_11_ISSUETYPE_SCHEMES',
                     what : 'How many issue type schemes exist.',
                     why  : 'A rough measure of configuration complexity. More schemes means more to check after the move.'],
        'sec-12'  : [title: 'Field configurations', check: 'CHECK_12_FIELD_CONFIGS',
                     what : 'How many field configurations and field configuration schemes exist.',
                     why  : 'A rough measure of configuration complexity. More means more to check after the move.'],
        'sec-13'  : [title: 'Screens', check: 'CHECK_13_SCREENS',
                     what : 'How many screens exist.',
                     why  : 'A rough measure of configuration complexity. More means more to check after the move.'],
        'sec-14'  : [title: 'Custom fields', check: 'CHECK_14_CUSTOM_FIELDS',
                     what : 'How many custom fields exist, broken down by field type.',
                     why  : 'ScriptRunner script fields are custom fields, so this gives their wider context. Custom fields are also a common source of migration effort: unused ones are worth removing first, and field types from apps depend on that app being available in Cloud.'],
        'sec-15'  : [title: 'Installed apps', check: 'CHECK_15_APPS',
                     what : 'Installed apps, grouped into third-party apps (confirmed on the Atlassian Marketplace or not), bundled Atlassian apps, and language packs.',
                     why  : 'Every third-party app needs a Cloud equivalent or a plan. Apps not found on the Marketplace may be built in-house and need extra attention. This report does not check Cloud availability itself.'],
        'sec-16'  : [title: 'Application links', check: 'CHECK_16_APP_LINKS',
                     what : 'Links from Jira to other systems, such as Confluence or Bitbucket.',
                     why  : 'Integrations need reviewing and re-establishing after the move.'],
        'sec-17-1': [title: 'Server timezone', check: 'CHECK_17_1_TIMEZONE',
                     what : 'The Jira server\'s default timezone, and whether Tempo Timesheets is installed.',
                     why  : 'Time-tracking data depends on timezones. If Tempo is installed, check Tempo\'s own migration guidance.'],
        'sec-17-2': [title: 'Issue security', check: 'CHECK_17_2_ISSUE_SECURITY',
                     what : 'Issue security schemes, which hide certain issues from certain people.',
                     why  : 'Restricted visibility needs to be accounted for in the migration plan.'],
        'sec-17-3': [title: 'ScriptRunner usage', check: 'CHECK_17_3_SCRIPTRUNNER_USAGE',
                     what : 'Every ScriptRunner script found, on how many days it ran in the last 30 and 90 days, when it last ran, and whether it is active, dormant or never run.',
                     why  : 'Migrating a script takes work. Scripts nobody uses can be retired instead, so knowing real usage can shrink the migration.'],
]

// Traffic lights: RED = review before migrating · AMBER = plan for it or tidy up ·
// GREEN = nothing flagged · INFO = for information · GREY = not run
def LIGHTS = [
        // Text colours chosen to meet WCAG 2.2 AA contrast (4.5:1) on each background.
        RED  : [colour: '#DE350B', text: '#FFFFFF', word: 'REVIEW',  label: 'Review before migrating'],
        AMBER: [colour: '#FF8B00', text: '#172B4D', word: 'PLAN',    label: 'Plan for it / tidy up'],
        GREEN: [colour: '#00875A', text: '#FFFFFF', word: 'OK',      label: 'Nothing flagged'],
        INFO : [colour: '#5E6C84', text: '#FFFFFF', word: 'INFO',    label: 'For information'],
        GREY : [colour: '#DFE1E6', text: '#172B4D', word: 'NOT RUN', label: 'Not run'],
]
// A light is always a colour AND a word, so it reads correctly without colour (accessibility).
def pill = { String light ->
    Map l = LIGHTS[light]
    "<span style='display:inline-block;min-width:58px;text-align:center;background:${l.colour};color:${l.text};border-radius:10px;" +
            "padding:1px 8px;font-size:11px;font-weight:bold;white-space:nowrap'>${l.word}</span>"
}
Map<String, Map> summaries = [:]   // id → [light, finding]
def setLight = { String id, String light, String finding -> summaries[id] = [light: light, finding: finding] }
def fmtN = { v -> String.format('%,d', (v ?: 0) as long) }

if (ran.contains('CHECK_01_SYSTEM_INFO'))
    setLight('sec-1', buildInfo.isFinal() ? 'INFO' : 'AMBER',
            "Jira ${jiraVersion} (build ${buildNumber})${buildInfo.isFinal() ? '.' : ' — not a final release.'}".toString())
if (ran.contains('CHECK_02_USERS')) {
    if (!userScanRan) setLight('sec-2', 'INFO', "${fmtN(totalUsers)} users. Email checks not run at this depth (use Standard or Full).".toString())
    else if (usersWithNoEmail || duplicateEmails)
        setLight('sec-2', 'RED', "${fmtN(totalUsers)} users: ${usersWithNoEmail.size()} with no email, ${duplicateEmails.size()} email address(es) shared by more than one user.".toString())
    else setLight('sec-2', totalUsers > THRESHOLD_LARGE_USERS ? 'AMBER' : 'GREEN',
            "${fmtN(totalUsers)} users (${fmtN(activeUsers)} active). Every user has a unique email address.".toString())
}
if (ran.contains('CHECK_03_DIRECTORIES'))
    setLight('sec-3', externalDirs ? 'RED' : 'GREEN', externalDirs
            ? "${externalDirs.size()} external director(y/ies), e.g. LDAP/AD: ${externalDirs*.name.join(', ')}.".toString()
            : 'Internal directory only.')
if (ran.contains('CHECK_04_GROUPS'))
    setLight('sec-4', 'INFO', "${fmtN(allGroupNames.size())} groups.".toString())
if (ran.contains('CHECK_05_PROJECTS'))
    setLight('sec-5', projectsNoLead ? 'AMBER' : 'GREEN',
            "${fmtN(allProjects.size())} active, ${fmtN(archivedProjects.size())} archived" +
                    (projectsNoLead ? ", ${projectsNoLead.size()} with no project lead." : '. All have a project lead.'))
if (ran.contains('CHECK_06_ISSUES'))
    setLight('sec-6', totalIssues > THRESHOLD_LARGE_ISSUES ? 'AMBER' : 'INFO',
            "${fmtN(totalIssues)} issues" + (totalIssues > THRESHOLD_LARGE_ISSUES ? " — above the ${fmtN(THRESHOLD_LARGE_ISSUES)} rule of thumb; allow a longer migration window." : '.'))
if (ran.contains('CHECK_07_CONSTANTS'))
    setLight('sec-7', 'INFO', "${issueTypes.size()} issue types, ${statuses.size()} statuses, ${priorities.size()} priorities, ${resolutions.size()} resolutions.".toString())
if (ran.contains('CHECK_08_WORKFLOWS'))
    setLight('sec-8', workflowsWithDraft ? 'AMBER' : 'GREEN',
            "${allWorkflows.size()} workflows (${activeWorkflows.size()} in use)" +
                    (workflowsWithDraft ? ", ${workflowsWithDraft.size()} with unpublished drafts." : '. No unpublished drafts.'))
if (ran.contains('CHECK_09_WORKFLOW_SCHEMES'))
    setLight('sec-9', unassignedWfSchemes ? 'AMBER' : 'GREEN',
            "${allWfSchemes.size()} schemes" + (unassignedWfSchemes ? ", ${unassignedWfSchemes.size()} not used by any project." : '. All in use.'))
if (ran.contains('CHECK_10_PERMISSION_SCHEMES'))
    setLight('sec-10', unassignedPermSchemes ? 'AMBER' : 'GREEN',
            "${allPermSchemes.size()} schemes" + (unassignedPermSchemes ? ", ${unassignedPermSchemes.size()} not used by any project." : '. All in use.'))
if (ran.contains('CHECK_11_ISSUETYPE_SCHEMES'))
    setLight('sec-11', 'INFO', "${allIssueTypeSchemes.size()} issue type schemes.".toString())
if (ran.contains('CHECK_12_FIELD_CONFIGS'))
    setLight('sec-12', 'INFO', "${allFieldLayouts.size()} field configurations in ${allFieldLayoutSchemes.size()} schemes.".toString())
if (ran.contains('CHECK_13_SCREENS'))
    setLight('sec-13', 'INFO', "${allScreens.size()} screens.".toString())
if (ran.contains('CHECK_14_CUSTOM_FIELDS'))
    setLight('sec-14', allCustomFields.size() > THRESHOLD_LARGE_CUSTOM_FIELDS ? 'AMBER' : 'INFO',
            "${fmtN(allCustomFields.size())} custom fields" + (allCustomFields.size() > THRESHOLD_LARGE_CUSTOM_FIELDS
                    ? " — above the ${THRESHOLD_LARGE_CUSTOM_FIELDS} rule of thumb; worth reviewing for unused fields." : '.'))
if (ran.contains('CHECK_15_APPS')) {
    int third = marketplacePlugins.size() + unidentifiedPlugins.size() + notCheckedPlugins.size()
    String appsFinding = !third ? 'No third-party apps.'
            : ("${third} third-party app(s): " +
               (MARKETPLACE_LOOKUP ? "${unidentifiedPlugins.size()} not found on the Marketplace, ${marketplacePlugins.size()} found" +
                       (notCheckedPlugins ? ", ${notCheckedPlugins.size()} not checked" : '') + '. '
                                   : 'not looked up on the Marketplace at this depth (Full adds this). ') +
               'Confirm a Cloud equivalent for each.') as String
    // RED only for apps actually looked up and not found. "Not checked" is never RED.
    setLight('sec-15', unidentifiedPlugins ? 'RED' : (third ? 'AMBER' : 'GREEN'), appsFinding)
}
if (ran.contains('CHECK_16_APP_LINKS'))
    setLight('sec-16', appLinks ? 'AMBER' : 'GREEN', appLinks ? "${appLinks.size()} application link(s) to review after the move.".toString() : 'No application links.')
if (ran.contains('CHECK_17_1_TIMEZONE'))
    setLight('sec-17-1', tempoInstalled ? 'AMBER' : 'INFO',
            "Server timezone ${systemTimezoneId}." + (tempoInstalled ? ' Tempo Timesheets is installed — check Tempo\'s migration guidance on timezones.' : ''))
if (ran.contains('CHECK_17_2_ISSUE_SECURITY'))
    setLight('sec-17-2', securitySchemeNames ? 'AMBER' : 'GREEN',
            securitySchemeNames ? "${securitySchemeNames.size()} issue security scheme(s) to include in the migration plan.".toString() : 'No issue security schemes.')
if (ran.contains('CHECK_17_3_SCRIPTRUNNER_USAGE')) {
    int retire = srCountScripts(['DORMANT', 'NEVER RUN'])
    int inUse  = srCountScripts(['ACTIVE'])
    setLight('sec-17-3', (inUse || retire || srUnattributed) ? 'AMBER' : 'GREEN',
            !srRows ? 'No ScriptRunner scripts found.'
                    : "${inUse} in use, ${retire} not run recently (worth considering for retirement)" +
                    (srUnattributed ? ", ${srUnattributed} not matched to a script." : '.') +
                    (srBudgetHit ? ' Incomplete — time budget reached.' : ''))
}
SECTION_INFO.each { String id, Map info ->
    if (!summaries[id]) setLight(id, 'GREY', timeSkipped.contains(info.check)
            ? "Not run — the ${TIME_BUDGET_SEC}s time limit was reached first.".toString()
            : 'Not run at this report depth.')
}
int redCount   = summaries.count { k, v -> v.light == 'RED' } as int
int amberCount = summaries.count { k, v -> v.light == 'AMBER' } as int
int greyCount  = summaries.count { k, v -> v.light == 'GREY' } as int
int srTracked  = srRows.count { it.status in ['ACTIVE', 'DORMANT', 'NEVER RUN', 'NO DATA'] } as int

// Report order = relevance order (REPORT_GROUPS), only groups not left out.
def sectionIdFor = { String check -> SECTION_INFO.find { k, v -> v.check == check }?.key }
def visibleGroups = REPORT_GROUPS.findAll { k, v -> !hiddenGroups.contains(k) }
List<String> DETAIL_ORDER = visibleGroups.collectMany { k, v -> (v.checks as List).collect { sectionIdFor(it as String) } } as List<String>
int redCountShown   = DETAIL_ORDER.count { summaries[it]?.light == 'RED' } as int
int amberCountShown = DETAIL_ORDER.count { summaries[it]?.light == 'AMBER' } as int

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
Map<String, Long> durationsMs = [:]
checkTimings.eachWithIndex { List t, int i ->
    long next = i + 1 < checkTimings.size() ? (checkTimings[i + 1][1] as long) : RUN_END
    durationsMs[t[0] as String] = next - (t[1] as long)
}
log.info "============================================================"
log.info "  DATA COLLECTION COMPLETE in ${String.format('%.1f', (RUN_END - RUN_START) / 1000d)}s — ${redCount} red, ${amberCount} amber"
summaries.findAll { k, v -> v.light in ['RED', 'AMBER'] }.sort { it.value.light }.each { k, v ->
    log.info "  [${v.light}] ${SECTION_INFO[k].title}: ${v.finding}"
}
log.info "  See the HTML result panel for the full report."
log.info "============================================================"


// ═══════════════════════════════════════════════════════════════════════════
// SCRIPTRUNNER DRILL-DOWN GROUPS — usage only (how the scripts move to Cloud is ScriptRunner Migration Suite's job). Every script is in exactly
// one group, and the bar chart uses the same groups and colours, so what you
// see in the bar is what you can open underneath it.
// ═══════════════════════════════════════════════════════════════════════════
def srGroupOf = { Map r ->
    // Unmatched usage files are not scripts: they always need identifying first.
    if ((r.type as String).startsWith('Not matched')) return 'IDENTIFY'
    if (r.status in ['DORMANT', 'NEVER RUN']) return 'RETIRE'
    if (r.status == 'NOT READ') return 'NOTREAD'
    if (r.status == 'ACTIVE') return 'INUSE'
    return 'NODATA'
}
String SR_DAYS_TXT = (SR_DORMANT_DAYS as int) == 1 ? '1 day' : "${SR_DORMANT_DAYS} days".toString()
List<Map> SR_GROUPS = [
        [key: 'RETIRE', word: 'CONSIDER', colour: '#FF8B00', text: '#172B4D', title: 'Consider retiring before converting',
         explain: "No run recorded in the last ${SR_DAYS_TXT}. Consider whether each one is still needed. If not, consider deleting it, or disabling it to keep a copy, on Data Center before exporting — so only scripts that are wanted get converted.".toString()],
        [key: 'IDENTIFY', word: 'IDENTIFY', colour: '#6554C0', text: '#FFFFFF', title: 'Not matched to a script — identify these',
         explain: 'Usage files that match no configured script. They can be listeners (name them with form option 4), other ScriptRunner features, or scripts that were deleted. Identify them before deciding anything.'],
        [key: 'INUSE', word: 'IN USE', colour: '#00875A', text: '#FFFFFF', title: 'In use',
         explain: 'These scripts appear to be actively used.'],
        [key: 'NOTREAD', word: 'NOT READ', colour: '#DFE1E6', text: '#172B4D', title: 'Not read — usage unknown',
         explain: 'Usage could not be read (the time limit was reached, the usage files were unavailable, or a cluster node had no usage folder). These are not marked as unused. See the note above the groups.'],
        [key: 'NODATA', word: 'NO DATA', colour: '#DFE1E6', text: '#172B4D', title: 'No usage data',
         explain: 'A type ScriptRunner does not record usage for (e.g. Behaviours), or a workflow condition with no recorded run (it is not confirmed that ScriptRunner records conditions). Usage is unknown, not zero. Check with the owner whether it is still needed.'],
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
String srBase = ((appProps.getString('jira.baseurl') ?: appProps.jiraBaseUrl) ?: '').replaceAll('/+$', '')

// ═══════════════════════════════════════════════════════════════════════════
// HTML REPORT
// OutputFormatter escapes values passed as text, so names from the instance
// are rendered safely. Only fixed badge markup is written unescaped.
// ═══════════════════════════════════════════════════════════════════════════
def badge = { String colour, String label, String text = '#FFFFFF' ->
    "<span style='background:${colour};color:${text};padding:2px 8px;border-radius:3px;" +
            "font-size:11px;font-weight:bold;white-space:nowrap'>${label}</span>"
}
def okBadge     = { String label -> badge('#00875A', label) }
// Amber = plan for it / check. The word REVIEW is kept for the red level only.
def reviewBadge = { String label -> badge('#FF8B00', label.replace('REVIEW', 'CHECK'), '#172B4D') }
def greyBadge   = { String label -> badge('#DFE1E6', label, '#172B4D') }
def statusColours = ['ACTIVE': '#00875A', 'DORMANT': '#FF8B00', 'NEVER RUN': '#DE350B',
                     'NO DATA': '#97A0AF', 'NOT TRACKED': '#97A0AF']

String H2  = 'color:#0052CC;margin-top:28px'
String H3  = 'color:#172B4D;margin-top:20px'
String DIM = 'color:#5E6C84;font-size:12px'
String BOX_WARN = 'background:#FFFAE6;border-left:4px solid #FF8B00;padding:8px 12px;margin:12px 0;font-size:12px'

OutputFormatter.markupBuilder { builder ->
    // ── One visual rule for "clickable" ──────────────────────────────────────
    //   blue ▸ / ▾ = the line opens and closes (click anywhere on it)
    //   blue underlined text with ↗ = a real link (opens in a new tab)
    //   everything else = plain text, not clickable
    mkp.yieldUnescaped("""<style>
.mri { font-family: Arial, sans-serif; max-width: 1100px; margin: 0 auto; color: #172B4D; }
.mri a.mri-link { color: #0052CC; text-decoration: underline; }
.mri a.mri-link::after { content: ' \\2197'; font-size: 10px; }
.mri details > summary { cursor: pointer; list-style: none; }
.mri details > summary::-webkit-details-marker { display: none; }
.mri details > summary::before { content: '\\25B8'; display: inline-block; width: 16px; color: #0052CC; font-weight: bold; }
.mri details[open] > summary::before { content: '\\25BE'; }
.mri details > summary:hover { background: #DEEBFF; }
.mri details.mri-row { border: 1px solid #DFE1E6; border-radius: 4px; margin: 4px 0; background: #fff; }
.mri details.mri-row > summary { padding: 8px 10px; font-size: 13px; }
.mri details.mri-sub { margin: 6px 0; border: 1px solid #DFE1E6; border-radius: 4px; }
.mri details.mri-sub > summary { padding: 6px 8px; font-size: 13px; font-weight: bold; }
.mri details.mri-sub > div { padding: 4px 10px 8px 24px; }
.mri .mri-hint { color: #5E6C84; font-size: 12px; font-weight: normal; }
</style>""")

    def skippedNote = { String key ->
        p(style: 'color:#5E6C84;font-style:italic') {
            mkp.yield timeSkipped.contains(key)
                    ? "Not run — the ${TIME_BUDGET_SEC}s time budget was reached first. Re-run with a larger budget, or use Custom to run just this check."
                    : 'Not included at this report depth.'
        }
    }
    def kvTable = { List<List> rows ->
        table(class: 'aui', style: 'width:100%') {
            thead { tr { th(scope: 'col', 'Metric'); th(scope: 'col', style: 'text-align:right', 'Count'); th(scope: 'col', 'Status') } }
            tbody {
                rows.each { List r ->
                    tr {
                        td(r[0] as String)
                        td(style: 'text-align:right', (r[1] instanceof Number) ? String.format('%,d', (r[1] as Number).longValue()) : (r[1] as String))
                        td { mkp.yieldUnescaped((r.size() > 2 ? r[2] : '') as String) }
                    }
                }
            }
        }
    }
    // A collapsible group inside a section ("zoom"): closed until clicked, count in the title.
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

        def depthLbl = [QUICK: 'Quick', STANDARD: 'Standard', FULL: 'Full', CUSTOM: 'Custom'][DEPTH] ?: DEPTH

        def renderers = [:] as Map<String, Closure>
        renderers['sec-1'] = {
            if (!ran.contains('CHECK_01_SYSTEM_INFO')) skippedNote('CHECK_01_SYSTEM_INFO') else {
                table(class: 'aui', style: 'width:100%') {
                    thead { tr { th(scope: 'col', 'Property'); th(scope: 'col', 'Value') } }
                    tbody {
                        tr { td('Jira version'); td(jiraVersion as String) }
                        tr { td('Build number'); td(buildNumber as String) }
                        tr { td('Base URL');     td((baseUrl ?: '(not set)') as String) }
                        tr {
                            td('Release type')
                            td { mkp.yieldUnescaped(buildInfo.isFinal() ? okBadge('FINAL') : reviewBadge(buildInfo.isEap() ? 'EAP' : 'NON-FINAL')) }
                        }
                    }
                }
            }
        }
        renderers['sec-2'] = {
            if (!ran.contains('CHECK_02_USERS')) skippedNote('CHECK_02_USERS') else {
                if (!userScanRan) {
                    kvTable([['Total users', totalUsers, totalUsers <= THRESHOLD_LARGE_USERS ? okBadge('OK') : reviewBadge('LARGE — REVIEW')]])
                    p(style: DIM) { mkp.yield 'Active/inactive counts and email checks were not run (they load every user). Use Standard or Full to include them.' }
                } else kvTable([
                        ['Total users', totalUsers, totalUsers <= THRESHOLD_LARGE_USERS ? okBadge('OK') : reviewBadge('LARGE — REVIEW')],
                        ['Active users', activeUsers],
                        ['Inactive users', inactiveUsers],
                        ['Users with no email address', usersWithNoEmail.size(), usersWithNoEmail ? reviewBadge('REVIEW') : okBadge('OK')],
                        ['Shared email addresses', duplicateEmails.size(), duplicateEmails ? reviewBadge('REVIEW') : okBadge('OK')],
                ])
                if (usersWithNoEmail) subGroup('Users with no email address', usersWithNoEmail.size()) {
                    ul { usersWithNoEmail.take(MAX_ROWS).each { ApplicationUser u -> li { mkp.yield "${u.displayName} (${u.username})" } } }
                    moreNote(usersWithNoEmail.size(), '')
                }
                if (duplicateEmails) subGroup('Email addresses shared by more than one user', duplicateEmails.size()) {
                    table(class: 'aui', style: 'width:100%') {
                        thead { tr { th(scope: 'col', 'Email'); th(scope: 'col', 'Users') } }
                        tbody {
                            duplicateEmails.take(MAX_ROWS).each { email, users ->
                                tr { td(email as String); td((users as List<ApplicationUser>).collect { ApplicationUser u -> "${u.displayName} (${u.username})" }.join(', ')) }
                            }
                        }
                    }
                    moreNote(duplicateEmails.size(), '')
                }
            }
        }
        renderers['sec-3'] = {
            if (!ran.contains('CHECK_03_DIRECTORIES')) skippedNote('CHECK_03_DIRECTORIES') else {
                table(class: 'aui', style: 'width:100%') {
                    thead { tr { th(scope: 'col', 'Name'); th(scope: 'col', 'Type'); th(scope: 'col', 'Active'); th(scope: 'col', 'Note') } }
                    tbody {
                        allDirectories.each { Directory d ->
                            boolean ext = d.type?.toString() != 'INTERNAL'
                            tr {
                                td(d.name); td(d.type?.toString() ?: 'unknown'); td(d.active ? 'Yes' : 'No')
                                td { mkp.yieldUnescaped(ext ? reviewBadge('EXTERNAL — REVIEW') : okBadge('INTERNAL')) }
                            }
                        }
                    }
                }
                if (externalDirs) {
                    p(style: BOX_WARN) { mkp.yield 'External directories (e.g. LDAP/AD) detected. Confirm how users from these directories will be handled in the migration plan.' }
                }
            }
        }
        renderers['sec-4'] = {
            if (!ran.contains('CHECK_04_GROUPS')) skippedNote('CHECK_04_GROUPS') else kvTable([['Total groups', allGroupNames.size()]])
        }
        renderers['sec-5'] = {
            if (!ran.contains('CHECK_05_PROJECTS')) skippedNote('CHECK_05_PROJECTS') else {
                def rows = [
                        ['Active projects', allProjects.size()],
                        ['Archived projects', archivedProjects.size()],
                        ['Project categories', projectCategories.size()],
                        ['Projects with no category', projectsNoCategory.size()],
                        ['Projects with no lead', projectsNoLead.size(), projectsNoLead ? reviewBadge('REVIEW') : okBadge('OK')],
                ]
                (projectsByType as Map<String, List<Project>>).each { String type, List<Project> ps -> (rows as List<List>) << ["  ↳ ${type} projects".toString(), ps.size()] }
                kvTable(rows)
                if (projectsNoLead) subGroup('Projects with no project lead', projectsNoLead.size()) {
                    ul { projectsNoLead.take(MAX_ROWS).each { Project pr -> li { mkp.yield "${pr.name} (${pr.key})" } } }
                    moreNote(projectsNoLead.size(), '')
                }
            }
        }
        renderers['sec-6'] = {
            if (!ran.contains('CHECK_06_ISSUES')) skippedNote('CHECK_06_ISSUES') else {
                kvTable([['Total issues', totalIssues, totalIssues <= THRESHOLD_LARGE_ISSUES ? okBadge('OK') : reviewBadge('LARGE — REVIEW')]])
            }
        }
        renderers['sec-7'] = {
            if (!ran.contains('CHECK_07_CONSTANTS')) skippedNote('CHECK_07_CONSTANTS') else {
                kvTable([
                        ['Issue types (total)', issueTypes.size()],
                        ['  ↳ Standard', issueTypes.size() - subTasks.size()],
                        ['  ↳ Sub-tasks', subTasks.size()],
                        ['Statuses', statuses.size()],
                        ['Priorities', priorities.size()],
                        ['Resolutions', resolutions.size()],
                ])
            }
        }
        renderers['sec-8'] = {
            if (!ran.contains('CHECK_08_WORKFLOWS')) skippedNote('CHECK_08_WORKFLOWS') else {
                kvTable([
                        ['Total workflows', allWorkflows.size()],
                        ['Active workflows', activeWorkflows.size()],
                        ['Inactive workflows', allWorkflows.size() - activeWorkflows.size()],
                        ['Workflows with unpublished drafts', workflowsWithDraft.size(), workflowsWithDraft ? reviewBadge('REVIEW') : okBadge('OK')],
                ])
                if (workflowsWithDraft) subGroup('Workflows with unpublished drafts', workflowsWithDraft.size()) {
                    ul { workflowsWithDraft.take(MAX_ROWS).each { wf -> li { mkp.yield wf.name } } }
                    moreNote(workflowsWithDraft.size(), '')
                }
            }
        }
        renderers['sec-9'] = {
            if (!ran.contains('CHECK_09_WORKFLOW_SCHEMES')) skippedNote('CHECK_09_WORKFLOW_SCHEMES') else {
                kvTable([
                        ['Total workflow schemes', allWfSchemes.size()],
                        ['Not used by any project', unassignedWfSchemes.size(), unassignedWfSchemes ? reviewBadge('CLEANUP CANDIDATE') : okBadge('OK')],
                ])
            }
        }
        renderers['sec-10'] = {
            if (!ran.contains('CHECK_10_PERMISSION_SCHEMES')) skippedNote('CHECK_10_PERMISSION_SCHEMES') else {
                kvTable([
                        ['Total permission schemes', allPermSchemes.size()],
                        ['Not used by any project', unassignedPermSchemes.size(), unassignedPermSchemes ? reviewBadge('CLEANUP CANDIDATE') : okBadge('OK')],
                ])
            }

            // ── 11–13. Schemes, configurations, screens ───────────────────────────
        }
        renderers['sec-11'] = {
            if (!ran.contains('CHECK_11_ISSUETYPE_SCHEMES')) skippedNote('CHECK_11_ISSUETYPE_SCHEMES') else kvTable([['Total issue type schemes', allIssueTypeSchemes.size()]])
        }
        renderers['sec-12'] = {
            if (!ran.contains('CHECK_12_FIELD_CONFIGS')) skippedNote('CHECK_12_FIELD_CONFIGS') else {
                kvTable([['Field configuration schemes', allFieldLayoutSchemes.size()], ['Field configurations', allFieldLayouts.size()]])
            }
        }
        renderers['sec-13'] = {
            if (!ran.contains('CHECK_13_SCREENS')) skippedNote('CHECK_13_SCREENS') else kvTable([['Total screens', allScreens.size()]])
        }
        renderers['sec-14'] = {
            if (!ran.contains('CHECK_14_CUSTOM_FIELDS')) skippedNote('CHECK_14_CUSTOM_FIELDS') else {
                kvTable([['Total custom fields', allCustomFields.size(),
                          allCustomFields.size() <= THRESHOLD_LARGE_CUSTOM_FIELDS ? okBadge('OK') : reviewBadge('LARGE — REVIEW')]])
                subGroup('Custom fields by type (most common first)', cfByType.size()) {
                    table(class: 'aui', style: 'width:100%') {
                        thead { tr { th(scope: 'col', 'Field type key'); th(scope: 'col', style: 'text-align:right', 'Count') } }
                        tbody {
                            cfByType.sort { a, b -> b.value.size() <=> a.value.size() }.take(MAX_ROWS).each { type, fields ->
                                tr { td(type as String); td(style: 'text-align:right', String.format('%,d', fields.size())) }
                            }
                        }
                    }
                    moreNote(cfByType.size(), '')
                }
            }
        }
        renderers['sec-15'] = {
            if (!ran.contains('CHECK_15_APPS')) skippedNote('CHECK_15_APPS') else {
                p(style: BOX_WARN) {
                    mkp.yield 'This check does not confirm Cloud availability or migration paths. ' +
                            '"Latest on Marketplace" is the latest listed version for any hosting type. ' +
                            'Confirm a Cloud equivalent for every third-party app on the Marketplace.'
                }
                kvTable([
                        ['Third-party: confirmed on Marketplace', marketplacePlugins.size(), marketplacePlugins ? reviewBadge('CONFIRM CLOUD') : okBadge('NONE')],
                        ['Third-party: not found on Marketplace', unidentifiedPlugins.size(), unidentifiedPlugins ? reviewBadge('REVIEW') : okBadge('NONE')],
                        ['Third-party: not checked', notCheckedPlugins.size(), notCheckedPlugins ? greyBadge('NOT CHECKED') : okBadge('NONE')],
                        ['Bundled Atlassian apps', corePlugins.size(), greyBadge('BUNDLED')],
                        ['Language packs', languagePackPlugins.size(), greyBadge('BUNDLED')],
                ])

                p(style: DIM) { mkp.yield 'Click a group to open it. Start with "not found" — those need the most attention. Disabled apps are not listed.' }

                subGroup('Third-party apps looked up and not found on the Marketplace — review first', unidentifiedPlugins.size()) {
                    p(style: DIM) {
                        mkp.yield 'Possible reasons: not publicly listed, or private or built in-house. "No vendor info" often means an in-house app.'
                    }
                    table(class: 'aui', style: 'width:100%') {
                        thead { tr { th(scope: 'col', 'App'); th(scope: 'col', 'Plugin key'); th(scope: 'col', 'Installed'); th(scope: 'col', 'Result'); th(scope: 'col', 'Vendor info') } }
                        tbody {
                            unidentifiedPlugins.sort { ((it as Map).plugin as Plugin).name }.take(MAX_ROWS).each { e ->
                                Plugin pl = (e as Map).plugin as Plugin
                                tr {
                                    td(pl.name ?: '(unnamed)'); td(pl.key); td(pl.pluginInformation?.version ?: 'n/a')
                                    td { mkp.yieldUnescaped(reviewBadge((e as Map).checkResult as String)) }
                                    td((e as Map).noVendorInfo ? 'No vendor info' : (pl.pluginInformation?.vendorName ?: '—'))
                                }
                            }
                        }
                    }
                    moreNote(unidentifiedPlugins.size(), '')
                }

                subGroup('Third-party apps not checked on the Marketplace', notCheckedPlugins.size()) {
                    p(style: DIM) {
                        mkp.yield "Not looked up: the lookup is only on at Full depth, the per-run limit of ${MARKETPLACE_CHECK_MAX_APPS} was reached, " +
                                'the time limit was reached, or the lookup failed. This is not the same as "not found".'
                    }
                    table(class: 'aui', style: 'width:100%') {
                        thead { tr { th(scope: 'col', 'App'); th(scope: 'col', 'Plugin key'); th(scope: 'col', 'Installed'); th(scope: 'col', 'Reason'); th(scope: 'col', 'Vendor info') } }
                        tbody {
                            notCheckedPlugins.sort { ((it as Map).plugin as Plugin).name }.take(MAX_ROWS).each { e ->
                                Plugin pl = (e as Map).plugin as Plugin
                                tr {
                                    td(pl.name ?: '(unnamed)'); td(pl.key); td(pl.pluginInformation?.version ?: 'n/a')
                                    td { mkp.yieldUnescaped(greyBadge((e as Map).checkResult as String)) }
                                    td((e as Map).noVendorInfo ? 'No vendor info' : (pl.pluginInformation?.vendorName ?: '—'))
                                }
                            }
                        }
                    }
                    moreNote(notCheckedPlugins.size(), '')
                }

                subGroup('Third-party apps confirmed on the Marketplace — confirm Cloud availability', marketplacePlugins.size()) {
                    p(style: DIM) { mkp.yield '"Latest on Marketplace" is the newest listed version for any hosting type — it does not mean a Cloud version exists.' }
                    table(class: 'aui', style: 'width:100%') {
                        thead { tr { th(scope: 'col', 'App'); th(scope: 'col', 'Plugin key'); th(scope: 'col', 'Installed'); th(scope: 'col', 'Latest on Marketplace'); th(scope: 'col', 'Version') } }
                        tbody {
                            marketplacePlugins.sort { ((it as Map).plugin as Plugin).name }.take(MAX_ROWS).each { e ->
                                Plugin pl = (e as Map).plugin as Plugin
                                tr {
                                    td(pl.name ?: '(unnamed)'); td(pl.key); td(pl.pluginInformation?.version ?: 'n/a')
                                    td((e as Map).latestVersion as String)
                                    td { mkp.yieldUnescaped((e as Map).versionMatches ? okBadge('MATCHES') : greyBadge('DIFFERS')) }
                                }
                            }
                        }
                    }
                    moreNote(marketplacePlugins.size(), '')
                }

                subGroup('Bundled Atlassian apps — part of Jira, for information', corePlugins.size()) {
                    table(class: 'aui', style: 'width:100%') {
                        thead { tr { th(scope: 'col', 'App'); th(scope: 'col', 'Plugin key'); th(scope: 'col', 'Installed') } }
                        tbody { corePlugins.sort { it.name }.take(MAX_ROWS).each { Plugin cp -> tr { td(cp.name ?: '(unnamed)'); td(cp.key); td(cp.pluginInformation?.version ?: 'n/a') } } }
                    }
                    moreNote(corePlugins.size(), '')
                }

                subGroup('Language packs — for information', languagePackPlugins.size()) {
                    table(class: 'aui', style: 'width:100%') {
                        thead { tr { th(scope: 'col', 'Language pack'); th(scope: 'col', 'Plugin key'); th(scope: 'col', 'Installed') } }
                        tbody { languagePackPlugins.sort { it.name }.take(MAX_ROWS).each { Plugin lp -> tr { td(lp.name ?: '(unnamed)'); td(lp.key); td(lp.pluginInformation?.version ?: 'n/a') } } }
                    }
                    moreNote(languagePackPlugins.size(), '')
                }
            }
        }
        renderers['sec-16'] = {
            if (!ran.contains('CHECK_16_APP_LINKS')) skippedNote('CHECK_16_APP_LINKS') else {
                kvTable([['Application links', appLinks.size(), appLinks ? reviewBadge('REVIEW') : okBadge('NONE')]])
                if (appLinks) subGroup('Application links', appLinks.size()) {
                    table(class: 'aui', style: 'width:100%') {
                        thead { tr { th(scope: 'col', 'Name'); th(scope: 'col', 'Type'); th(scope: 'col', 'URL') } }
                        tbody { appLinks.take(MAX_ROWS).each { link -> tr { td(link.name); td(link.type?.class?.simpleName ?: 'unknown'); td(link.displayUrl?.toString() ?: 'n/a') } } }
                    }
                    moreNote(appLinks.size(), '')
                }
            }
        }
        renderers['sec-17-1'] = {
            if (!ran.contains('CHECK_17_1_TIMEZONE')) skippedNote('CHECK_17_1_TIMEZONE') else {
                table(class: 'aui', style: 'width:100%') {
                    thead { tr { th(scope: 'col', 'Property'); th(scope: 'col', 'Value') } }
                    tbody {
                        tr { td('JVM default timezone (ID)');   td(systemTimezoneId as String) }
                        tr { td('JVM default timezone (name)'); td(systemTimezoneDisplay as String) }
                        tr { td('Tempo Timesheets detected');   td(tempoInstalled ? 'Yes' : 'No') }
                    }
                }
                if (tempoInstalled) {
                    p(style: BOX_WARN) { mkp.yield "Tempo Timesheets is installed and the server timezone is ${systemTimezoneId}. Check Tempo's migration guidance on timezone handling before migrating." }
                }
            }
        }
        renderers['sec-17-2'] = {
            if (!ran.contains('CHECK_17_2_ISSUE_SECURITY')) skippedNote('CHECK_17_2_ISSUE_SECURITY') else {
                kvTable([['Issue security schemes', securitySchemeNames.size(), securitySchemeNames ? reviewBadge('REVIEW') : okBadge('NONE')]])
                if (securitySchemeNames) subGroup('Issue security schemes', securitySchemeNames.size()) {
                    ul { securitySchemeNames.take(MAX_ROWS).each { String sn -> li { mkp.yield sn } } }
                    moreNote(securitySchemeNames.size(), '')
                }
                if (securitySchemeNames) {
                    p(style: BOX_WARN) {
                        mkp.yield 'Issue security restricts who can see issues. Include these schemes in migration planning' +
                                (tempoInstalled ? ', and check Tempo\'s migration guidance as Tempo is installed.' : '.')
                    }
                }
            }
        }

        // ── Header ────────────────────────────────────────────────────────────
        h1(style: 'color:#172B4D;margin:0 0 4px') { mkp.yield 'ScriptRunner Migration Readiness' }
        p(style: DIM + ';margin:0 0 8px') {
            mkp.yield([ran.contains('CHECK_01_SYSTEM_INFO') ? "${baseUrl ?: ''} · Jira ${jiraVersion}" : null,
                       "ScriptRunner ${srVersion}", "Generated ${new Date().format('yyyy-MM-dd HH:mm z')}", "Depth: ${depthLbl}"].findAll().join('  ·  '))
        }
        div(style: 'border:2px solid #0052CC;border-radius:6px;padding:12px 16px;margin:6px 0 14px;background:#F4F8FF') {
            div(style: 'font-size:15px;font-weight:bold;margin-bottom:6px') { mkp.yield 'Read this first: what this report is' }
            ol(style: 'margin:0 0 6px;padding-left:20px;font-size:13px;line-height:1.5') {
                li { b('It shows how much your ScriptRunner scripts are used on Data Center '); mkp.yield '— on how many days each one ran, and when it last ran.' }
                li { b('Use it to decide which scripts you still need, before converting any. '); mkp.yield 'Scripts nobody uses are worth considering for retirement first.' }
                li {
                    b('It is advice only and does not replace the ScriptRunner Migration Suite. ')
                    mkp.yield 'ScriptRunner Migration Suite Analyse and Assess shows what can be brought to Cloud and how. You still analyse, convert and deploy in ScriptRunner Migration Suite. '
                    extLink('https://docs.adaptavist.com/sms/latest/scriptrunner-migration-suite-web-app/scriptrunner-migration-analyse-and-assess-tool', 'ScriptRunner Migration Suite Analyse and Assess')
                }
            }
            p(style: 'font-size:11px;color:#5E6C84;margin:0') {
                mkp.yield 'Best-effort proof of concept: not supported or maintained, read-only (nothing was changed), results indicative. '
                mkp.yieldUnescaped("<span style='color:#0052CC;font-weight:bold'>&#9656;</span>")
                mkp.yield ' = opens when clicked. '
                span(style: 'color:#0052CC;text-decoration:underline') { mkp.yield 'Blue underlined ↗' }
                mkp.yield ' = link. Nothing else is clickable.'
            }
        }
        if (timeSkipped) {
            div(style: BOX_WARN) {
                b("⏱ ${timeSkipped.size()} area(s) were not checked because the ${TIME_BUDGET_SEC}s time limit was reached. ")
                mkp.yield 'Results below are incomplete — raise the limit (form option 2) and re-run.'
            }
        }

        // ═════ 1. SCRIPTRUNNER ═════════════════════════════════════════════
        h2(style: 'color:#172B4D;font-size:18px;margin:0 0 6px') { mkp.yield 'Your ScriptRunner scripts' }
        if (!ran.contains('CHECK_17_3_SCRIPTRUNNER_USAGE')) {
            p(style: 'color:#5E6C84') { mkp.yield summaries['sec-17-3']?.finding as String }
        } else if (!srRows) {
            p { mkp.yield srRrdUnavailable ? 'ScriptRunner usage files could not be read, so no usage can be shown. See the warnings in the Jira log.'
                    : 'No ScriptRunner scripts or script activity were found on this instance.' }
        } else {
            // Headline: one count per group, problems first
            div(style: 'border-left:6px solid #172B4D;background:#FAFBFC;padding:10px 14px;margin-bottom:8px') {
                div(style: 'font-size:20px;font-weight:bold') { mkp.yield "${srScripts} ScriptRunner scripts found" + (srUnattributed ? ", plus ${srUnattributed} usage files not matched to a script" : '') }
                div(style: 'margin-top:6px;font-size:13px;line-height:24px') {
                    SR_GROUPS.findAll { srRowsByGroup[it.key as String] }.each { Map g ->
                        int c = srRowsByGroup[g.key as String].size()
                        span(style: 'margin-right:16px;white-space:nowrap') { mkp.yieldUnescaped(groupPill(g)); mkp.yield " ${c} ${(g.title as String).toLowerCase()}" }
                    }
                }
            }
            // Bar: same groups, same colours, same order as the lists below
            if (srNotReadKeys || srRrdUnavailable || srNodesMissing) {
                div(style: BOX_WARN) {
                    b('Usage data is incomplete. ')
                    mkp.yield srRrdUnavailable ? 'The usage files could not be read, so no script is marked as unused.'
                            : srNodesMissing ? "No usage folder was found for cluster node(s) ${srNodesMissing.join(', ')}, so no script is marked as unused. Scripts without a recorded run are listed as \"Not read\". Check those nodes with your Jira administrator."
                            : "${srNotReadKeys.size()} usage file(s) were not read before the time limit. Those scripts are listed as \"Not read\", not as unused. Raise the time limit (form option 2) and re-run."
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
            p(class: 'mri-hint', style: 'margin:0 0 8px') { mkp.yield "The bar is a picture only. Open a group below to see each script: on how many days it ran in the last ${SR_DAYS_TXT}, and when it last ran." }

            // Drill-down: one collapsible group per colour
            SR_GROUPS.findAll { srRowsByGroup[it.key as String] }.each { Map g ->
                // Most useful order per group: longest unused first; most used first.
                List<Map> rs = srRowsByGroup[g.key as String].sort { x, y ->
                    if (g.key == 'RETIRE') {
                        long dx = x.daysAgo == null ? Long.MAX_VALUE : (x.daysAgo as long)
                        long dy = y.daysAgo == null ? Long.MAX_VALUE : (y.daysAgo as long)
                        return (dy <=> dx) ?: (x.name as String) <=> (y.name as String)
                    }
                    if (g.key in ['INUSE', 'IDENTIFY']) {
                        long ax = x.daysAgo == null ? Long.MAX_VALUE : (x.daysAgo as long)
                        long ay = y.daysAgo == null ? Long.MAX_VALUE : (y.daysAgo as long)
                        return ((y.sortDays as int) <=> (x.sortDays as int)) ?: (ax <=> ay) ?: (x.name as String) <=> (y.name as String)
                    }
                    return (x.type as String) <=> (y.type as String) ?: (x.name as String) <=> (y.name as String)
                }
                String sortNote = g.key == 'RETIRE' ? 'Longest unused first.' : (g.key in ['INUSE', 'IDENTIFY'] ? 'Ran on the most days first, then most recent.' : '')
                mkp.yieldUnescaped("<details class='mri-sub'><summary>" + groupPill(g) + '&nbsp; ')
                mkp.yield "${g.title} (${rs.size()})"
                span(class: 'mri-hint') { mkp.yield ' — ' + (g.explain as String) }
                mkp.yieldUnescaped("</summary><div>")
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
                }
                mkp.yieldUnescaped("</div></details>")
            }

            // Full list for working through with owners (no row cap)
            subGroup('Copy the full list into a spreadsheet', srRows.size()) {
                p(class: 'mri-hint', style: 'margin:0 0 6px') {
                    mkp.yield 'Click in the box, select all (Ctrl+A or Cmd+A), copy, and paste into Excel or Google Sheets. ' +
                            'It pastes straight into columns, so you can add an owner and a decision for each script.'
                }
                def clean = { v -> (v == null ? '' : v.toString()).replaceAll(/[\t\r\n]+/, ' ') }
                Map<String, String> groupTitle = SR_GROUPS.collectEntries { [(it.key): it.title] }
                // The chosen-window column is left out when it would repeat the fixed 30- or 90-day column.
                boolean dupWindow = (SR_DORMANT_DAYS as int) in [30, 90]
                List<String> hdr = ['Usage group', 'Type', 'Name', 'Projects / scope', "Days it ran, last ${SR_DAYS_TXT}".toString(), 'Last run (UTC)', 'Days since last run', 'Days it ran, last 30 days', 'Days it ran, last 90 days']
                if (dupWindow) hdr.remove(4)
                List<String> lines = [hdr.join('\t')]
                SR_GROUPS.each { Map g ->
                    srRowsByGroup[g.key as String].each { Map r ->
                        List vals = [groupTitle[g.key as String], r.type, r.name, r.where, r.dX, r.lastRun,
                                     r.daysAgo != null ? r.daysAgo : '', r.d30, r.d90]
                        if (dupWindow) vals.remove(4)
                        lines << vals.collect { clean(it) }.join('\t')
                    }
                }
                textarea(readonly: 'readonly', rows: '10', 'aria-label': 'Full ScriptRunner script list, tab-separated',
                        style: 'width:100%;font-family:monospace;font-size:11px;white-space:pre;overflow:auto') { mkp.yield lines.join('\n') }
            }

            // Secondary and reference views
            subGroup('By feature type', srByType.size()) {
                table(class: 'aui', style: 'width:100%') {
                    thead { tr { th(scope: 'col', 'Feature type'); th(scope: 'col', style: 'text-align:right', 'Scripts'); th(scope: 'col', style: 'text-align:right', 'In use'); th(scope: 'col', style: 'text-align:right', 'Consider retiring') } }
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
                            "Nodes read: ${srNodeNames.join(', ') ?: 'none'}. Files read: ${srRrdFiles}" +
                            (srNotReadKeys ? ", not read (time limit): ${srNotReadKeys.size()}" : '') + '. ' +
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
                    li { mkp.yield 'Listeners cannot be listed by API, so they appear under "Not matched" unless named (form option 4). Listeners that never ran do not appear. "Not matched" can also include other ScriptRunner features and deleted scripts, so it is not a list of listeners.' }
                    li { mkp.yield 'REST endpoints and JQL functions appear only once they have been called.' }
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
                li(style: 'margin-bottom:4px') { b('Consider retiring unused scripts on Data Center — '); mkp.yield 'review the "consider retiring" list with owners, and delete or disable anything no longer needed. This report narrows the list; it does not assess migratability.' }
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

        // ═════ 3. EVERYTHING ELSE, BY RELEVANCE ════════════════════════════
        def otherGroups = visibleGroups.findAll { k, v -> k != 'SR' }
        if (otherGroups) {
            h2(style: 'color:#172B4D;font-size:18px;margin:22px 0 4px') { mkp.yield 'Wider Jira migration readiness' }
            p(style: 'font-size:13px;margin:0 0 6px') {
                mkp.yield((redCountShown ? "${redCountShown} area(s) to review before migrating, ${amberCountShown} to plan for or tidy up. "
                        : (amberCountShown ? "Nothing to review before migrating; ${amberCountShown} area(s) to plan for or tidy up. " : 'Nothing flagged. ')) +
                        'Most relevant to ScriptRunner first. Each line shows the result; open it for why it matters and the full data.')
            }
            div(style: 'font-size:12px;margin-bottom:6px;line-height:22px') {
                LIGHTS.each { String k, Map v -> span(style: 'margin-right:14px;white-space:nowrap') { mkp.yieldUnescaped(pill(k)); mkp.yield " ${v.label}" } }
            }
            otherGroups.each { String gk, Map g ->
                h3(style: 'color:#172B4D;margin:16px 0 2px;font-size:14px') { mkp.yield g.title as String }
                p(class: 'mri-hint', style: 'margin:0 0 4px') { mkp.yield g.why as String }
                (g.checks as List<String>).each { String chk ->
                    String id = sectionIdFor(chk)
                    Map info = SECTION_INFO[id]
                    Map sm = summaries[id]
                    mkp.yieldUnescaped("<details class='mri-row'><summary>" + pill(sm.light as String) + '&nbsp; ')
                    b(info.title as String)
                    span(style: sm.light == 'GREY' ? 'color:#5E6C84;font-style:italic' : 'color:#42526E') { mkp.yield ' — ' + (sm.finding as String) }
                    mkp.yieldUnescaped("</summary><div style='padding:4px 12px 10px 26px'>")
                    p(class: 'mri-hint', style: 'margin:4px 0 8px') { b('Why it matters: '); mkp.yield info.why as String; br(); b('What this shows: '); mkp.yield info.what as String }
                    renderers[id]()
                    mkp.yieldUnescaped("</div></details>")
                }
            }
        }
        if (hiddenGroups) {
            p(class: 'mri-hint', style: 'margin-top:6px') { mkp.yield 'Left out of this run (form options 5–7): ' + hiddenGroups.collect { REPORT_GROUPS[it].title }.join(', ') + '.' }
        }

        // ═════ 4. ABOUT THIS RUN (reference) ═══════════════════════════════
        h2(style: 'color:#172B4D;font-size:16px;margin:22px 0 4px') { mkp.yield 'About this run' }
        subGroup('Settings used, and what each one changed', 5) {
            table(class: 'aui', style: 'width:100%') {
                thead { tr { th(scope: 'col', 'Setting'); th(scope: 'col', 'Value'); th(scope: 'col', 'What it meant for this report') } }
                tbody {
                    def row = { String k, String v, String m -> tr { td { b(k) }; td(v); td(m) } }
                    row('How thorough', depthLbl, "${ran.size()} of ${SECTION_INFO.size()} areas checked. " +
                            (userScanRan ? 'User emails were checked. ' : 'User emails were not checked (Standard or Full adds this). ') +
                            (MARKETPLACE_LOOKUP ? 'Apps were looked up on the Marketplace (internet calls made).' : 'Apps were not looked up on the Marketplace (Full adds this).'))
                    row('Maximum run time', "${TIME_BUDGET_SEC}s".toString(), "The run took ${String.format('%.1f', (RUN_END - RUN_START) / 1000d)}s. ScriptRunner was checked first. " +
                            (timeSkipped ? "${timeSkipped.size()} area(s) were skipped for time." : 'Nothing was skipped for time.'))
                    row('Unused after', "${SR_DORMANT_DAYS} days".toString(), "Scripts with no recorded run in ${SR_DORMANT_DAYS} days are listed under \"Consider retiring\".")
                    row('Listener names', SR_LISTENER_NAMES ? "${SR_LISTENER_NAMES.size()} entered".toString() : 'none',
                            SR_LISTENER_NAMES ? 'Matching listeners are shown by name.' : 'Listeners (if any) are shown as "Not matched".')
                    row('Left out', hiddenGroups ? hiddenGroups.collect { REPORT_GROUPS[it].title }.join(', ') : 'nothing',
                            hiddenGroups ? 'Not checked or shown. Untick in the form to include.' : 'Every group was included.')
                }
            }
            if (durationsMs) {
                p(class: 'mri-hint') {
                    mkp.yield 'Slowest areas: ' + durationsMs.sort { -it.value }.take(5)
                            .collect { k, v -> "${SECTION_INFO.find { sid, i -> i.check == k }?.value?.title ?: k} ${String.format('%.1f', (v as long) / 1000d)}s" }.join('  ·  ')
                }
            }
        }

        div(style: 'color:#5E6C84;font-size:11px;margin-top:20px;border-top:1px solid #DFE1E6;padding-top:8px') {
            b('About this report. ')
            mkp.yield 'Best-effort proof of concept: provided as-is, not maintained, not supported, and not an official Adaptavist or Atlassian tool. ' +
                    'Read-only — no data or configuration was changed. Automated checks only; results are indicative, so verify before acting. ' +
                    'It does not replace Atlassian\'s Jira pre-migration checklist or the Jira Cloud Migration Assistant (JCMA). '
            extLink('https://support.atlassian.com/migration/docs/jira-pre-migration-checklist/', 'Atlassian pre-migration checklist')
        }
    }
}
