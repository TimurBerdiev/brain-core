package dev.braincore.boss

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

// The task system of Brain Core (MANUAL.md, "Tasks"): one status label per open issue,
// a `Waiting on:` line on input-needed / on-hold, decision cards as comments, answers as comments.
// Tasks live in the task repos: the home repo (Settings) plus every repos.yaml entry with `tasks: true` or
// `tasks: assigned`. The GitHub assignee is whose task it is: an input-needed card assigned to someone else waits on them.

/** Open statuses, in display order. An open issue without one counts as ready. */
val OPEN = listOf("input-needed", "ready", "on-hold", "backlog")

/** Old label names still read as their status: `in-review` was renamed to `input-needed` (task.py's LEGACY). */
val LEGACY = mapOf("in-review" to "input-needed")

/** Statuses the app can move an issue to. Use task.sh or GitHub to set input-needed. */
val MOVE_TO = listOf("ready", "on-hold", "backlog", "done", "dropped")

const val CARD_MARK = "<!-- card -->"
const val ANSWER_MARK = "<!-- answer -->"
const val QUESTION_MARK = "<!-- question -->"

data class Issue(
    val number: Int,
    val title: String,
    val body: String,
    val labels: List<String>,
    val state: String,
    val updatedAt: String,
    val url: String,
    /** Who holds the claim (`claim/<n>` branch, fresh); empty when nobody is working on it. */
    val claim: String = "",
    val stateReason: String = "",
    /** owner/name of the task repo the issue is in; empty for the home repo. */
    val repo: String = "",
    /** The repo's name in repos.yaml. Old caches only have [repo]. */
    val repoAlias: String = "",
    /** GitHub logins the issue is assigned to: whose task it is. Empty = the owner's (or nobody's yet in a shared repo). */
    val assignees: List<String> = emptyList(),
) {
    /** Identifies the task across task repos: "81" in the home repo, "owner/site#38" in another. */
    val key: String get() = taskKey(repo, number)
    val taskRepoName: String get() = repoAlias.ifEmpty { repo.substringAfter('/') }
    /** How people write it: "#81", "site#38" (task.sh's refs). */
    val ref: String get() = if (repo.isEmpty()) "#$number" else "$taskRepoName#$number"
    val inProgress: Boolean get() = state == "open" && claim.isNotEmpty()
    val status: String get() = if (state == "closed") {
        if (stateReason == "not_planned") "dropped" else "done"
    } else labels.map { LEGACY[it] ?: it }.let { names -> OPEN.firstOrNull { it in names } } ?: "ready"
    val waitingOn: String get() = field(body, "Waiting on")
    val checkAfter: String get() = field(body, "Check after")
    val priority: Int get() = (1..3).firstOrNull { "p$it" in labels } ?: 2
    val tags: List<String> get() = labels.filter { it !in OPEN && it !in LEGACY && !Regex("p[123]").matches(it) }

    /** Is this issue the owner's (login [me], from workspace.yaml)? Unassigned counts as theirs, as in task.py. */
    fun belongsTo(me: String): Boolean = assignees.isEmpty() || me.isEmpty() || assignees.any { it.equals(me, ignoreCase = true) }

    /** Someone else's: assigned, and not to [me]. Shown with their @login; their card doesn't wait on the owner. */
    fun othersOnly(me: String): List<String> = if (belongsTo(me)) emptyList() else assignees
}

data class Comment(val id: Long, val author: String, val body: String, val createdAt: String, val url: String) {
    val kind: String
        get() = body.trimStart().let {
            when {
                it.startsWith(CARD_MARK) -> "card"
                it.startsWith(ANSWER_MARK) -> "answer"
                it.startsWith(QUESTION_MARK) -> "question"
                else -> "log"
            }
        }

    /** The body without its marker. */
    val text: String
        get() = body.trimStart().removePrefix(CARD_MARK).removePrefix(ANSWER_MARK).removePrefix(QUESTION_MARK).trim()
}

/** A decision card: the question, the answers to pick from (empty = Yes / No), a recommendation, and the rest as markdown. */
data class Card(val question: String, val options: List<String>, val recommend: String, val rest: String) {
    val choices: List<String> get() = options.ifEmpty { listOf("Yes", "No") }
}

fun field(body: String, name: String): String =
    Regex("(?m)^${Regex.escape(name)}:\\s*(.+?)\\s*$").find(body)?.groupValues?.get(1) ?: ""

fun parseCard(text: String): Card {
    var question = ""
    var options = emptyList<String>()
    var recommend = ""
    val rest = mutableListOf<String>()
    for (line in text.removePrefix(CARD_MARK).trim().lines()) {
        val t = line.trim()
        when {
            question.isEmpty() && t.startsWith("### ") -> question = t.removePrefix("### ").trim()
            options.isEmpty() && t.startsWith("Options:") ->
                options = t.removePrefix("Options:").split("|").map { it.trim() }.filter { it.isNotEmpty() }
            recommend.isEmpty() && t.startsWith("Recommend:") -> recommend = t.removePrefix("Recommend:").trim()
            else -> rest += line
        }
    }
    return Card(question, options, recommend, rest.joinToString("\n").trim())
}

/**
 * The card the owner should answer now: the latest card comment, unless they already answered or asked after it.
 * A question without a decision card uses the `Waiting on:` line or issue title.
 */
fun currentCard(issue: Issue, comments: List<Comment>): Card? {
    if (issue.status != "input-needed") return null
    val card = comments.indexOfLast { it.kind == "card" }
    val reply = comments.indexOfLast { it.kind == "answer" || it.kind == "question" }
    if (card >= 0 && card > reply) return parseCard(comments[card].text)
    return Card(issue.waitingOn.ifBlank { issue.title }, emptyList(), "", "")
}

fun stripStatusLines(body: String): String =
    body.replace(Regex("(?m)^(Waiting on|Check after):.*\\n?"), "").trimStart('\n')

/** The issue body after a status change, as `scripts/task.py set_status` writes it. */
fun bodyFor(body: String, waitingOn: String?, checkAfter: String?): String {
    val stripped = stripStatusLines(body)
    if (waitingOn.isNullOrBlank()) return stripped
    val ca = if (checkAfter.isNullOrBlank()) "" else "Check after: ${checkAfter.trim()}\n"
    return "Waiting on: ${waitingOn.trim()}\n$ca\n$stripped"
}

/** Labels after a status change: the old status label swapped for the new one (none when closing). */
fun labelsFor(labels: List<String>, status: String): List<String> {
    val kept = labels.filter { it !in OPEN && it !in LEGACY }
    return (if (status in OPEN) kept + status else kept).sorted()
}

/** What a status change writes: the new body and labels, plus state / state_reason when it closes or reopens the issue. */
data class StatusChange(val body: String, val labels: List<String>, val state: String? = null, val stateReason: String? = null)

/** The change `scripts/task.py set_status` makes: done / dropped close as completed / not_planned, an open status reopens. */
fun statusChange(issue: Issue, status: String, waitingOn: String? = null, checkAfter: String? = null): StatusChange {
    val body = bodyFor(issue.body, waitingOn, checkAfter)
    val labels = labelsFor(issue.labels, status)
    return when {
        status == "done" || status == "dropped" -> if (issue.state == "open")
            StatusChange(body, labels, "closed", if (status == "done") "completed" else "not_planned")
        else StatusChange(body, labels)
        issue.state == "closed" -> StatusChange(body, labels, "open")
        else -> StatusChange(body, labels)
    }
}

private fun quote(question: String) = question.lines().joinToString("\n") { "> $it" }

fun answerComment(question: String, choice: String?, note: String): String = buildString {
    append(ANSWER_MARK).append('\n').append(quote(question)).append("\n\n")
    if (!choice.isNullOrBlank()) append("**").append(choice).append("**\n\n")
    if (note.isNotBlank()) append(note.trim()).append('\n')
}.trim()

fun questionComment(question: String, text: String): String =
    "$QUESTION_MARK\n${quote(question)}\n\n${text.trim()}"

fun taskKey(repo: String, number: Int): String = if (repo.isEmpty()) "$number" else "$repo#$number"

/** (repo, number) of a task key; repo is empty for the home repo. */
fun splitKey(key: String): Pair<String, Int> = key.substringBeforeLast('#', "") to key.substringAfterLast('#').toInt()

fun refOf(key: String): String = splitKey(key).let { (r, n) -> if (r.isEmpty()) "#$n" else "${r.substringAfter('/')}#$n" }

/**
 * The task repos besides home, mapping each repos.yaml name to its GitHub owner/name as task.py does.
 */
fun taskReposOf(yaml: String): Map<String, String> {
    val root = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(yaml) as? Map<*, *>
    val repos = root?.get("repos") as? Map<*, *> ?: return emptyMap()
    val githubRemote = Regex("github\\.com[:/]([^/]+/[^/]+?)(?:\\.git)?/?$")
    return repos.mapNotNull { (name, value) ->
        val entry = value as? Map<*, *>
        val remote = entry?.get("remote") as? String
        val tasks = entry?.get("tasks")
        if (name !is String || (tasks != true && tasks != "assigned") || remote == null) return@mapNotNull null
        githubRemote.find(remote)?.let { name to it.groupValues[1] }
    }.toMap().toSortedMap()
}

/** The owner's GitHub login from workspace.yaml (`github:`), "" without one. task.py reads the same field. */
fun ownerOf(yaml: String): String =
    ((Yaml(SafeConstructor(LoaderOptions())).load<Any?>(yaml) as? Map<*, *>)?.get("github") as? String).orEmpty().trim()

/** Workstreams from `ws:<name>` labels, plus the one a task repo other than home stands for (its name). */
val Issue.workstreams: List<String> get() {
    val ws = labels.filter { it.startsWith("ws:") }.map { it.removePrefix("ws:") }
    val own = taskRepoName.lowercase()
    return if (repo.isEmpty() || own in ws) ws else ws + own
}

/*
 * A workstream filter is a set of terms: `name` shows only these workstreams, `-name` hides these (issues without a
 * `ws:` label stay), empty shows everything. The same format as `filter:` in everyday's workers.yaml, which decides
 * what Autopilot and the orchestrator pick (`matches` in everyday's core/scripts/task.py). Change both together.
 */
fun Issue.matches(filter: Set<String>): Boolean {
    val only = filter.filterNot { it.startsWith("-") }
    val hidden = filter.filter { it.startsWith("-") }.map { it.drop(1) }
    return (only.isEmpty() || workstreams.any { it in only }) && workstreams.none { it in hidden }
}

/** Workstreams to offer in the filter: those on the issues, plus chosen ones that have no open issue right now. */
fun workstreamsOf(issues: List<Issue>, filter: Set<String>) =
    (issues.flatMap { it.workstreams } + filter.map { it.removePrefix("-") }).distinct().sorted()

enum class Pick { OFF, ONLY, EXCEPT }

fun pickOf(filter: Set<String>, ws: String) = when {
    ws in filter -> Pick.ONLY
    "-$ws" in filter -> Pick.EXCEPT
    else -> Pick.OFF
}

/** A tap on a workstream chip: off -> only -> except -> off. */
fun cycle(filter: Set<String>, ws: String): Set<String> = when (pickOf(filter, ws)) {
    Pick.OFF -> filter + ws
    Pick.ONLY -> filter - ws + "-$ws"
    Pick.EXCEPT -> filter - "-$ws"
}

/** "All", "app, home", "All except site", "app, not home". */
fun describeFilter(filter: Set<String>): String {
    val only = filter.filterNot { it.startsWith("-") }.sorted()
    val hidden = filter.filter { it.startsWith("-") }.map { it.drop(1) }.sorted()
    return when {
        only.isEmpty() && hidden.isEmpty() -> "All"
        only.isEmpty() -> "All except " + hidden.joinToString(", ")
        hidden.isEmpty() -> only.joinToString(", ")
        else -> only.joinToString(", ") + ", not " + hidden.joinToString(", ")
    }
}

private val FILTER_LINE = Regex("(?m)^filter:[ \\t]*\\[([^\\]\\n]*)\\][ \\t]*(#.*)?$")
private val FILTER_TERM = Regex("-?[a-z0-9][a-z0-9-]{0,49}")

/** The dispatcher's filter from workers.yaml (`filter: [app, "-site"]`), or null without that line. */
fun dispatcherFilter(yaml: String): Set<String>? = FILTER_LINE.find(yaml)?.groupValues?.get(1)
    ?.split(',')?.map { it.trim().trim('"', '\'') }?.filter { it.isNotEmpty() }?.toSet()

/** workers.yaml with its `filter:` line replaced; null if it has none, or a term the dispatcher would reject. */
fun withDispatcherFilter(yaml: String, filter: Set<String>): String? {
    if (FILTER_LINE.find(yaml) == null || filter.any { !FILTER_TERM.matches(it) }) return null
    val terms = filter.sortedWith(compareBy({ it.startsWith("-") }, { it.removePrefix("-") })).joinToString(", ") { "\"$it\"" }
    return FILTER_LINE.replaceFirst(yaml, Regex.escapeReplacement("filter: [$terms]"))
}

/** Queue visibility shared by open tasks, completed history and the widget. Search stays global. */
fun visibleIssues(issues: List<Issue>, filter: Set<String>, me: String, showEveryone: Boolean = false): List<Issue> =
    issues.filter { it.matches(filter) && (showEveryone || it.belongsTo(me)) }

/**
 * Issues grouped by status, with the most recently updated issues first in each section. "input-needed" holds only the cards
 * that wait on the owner ([me]); one assigned to someone else waits on that person, so it is listed under on-hold.
 */
fun grouped(issues: List<Issue>, me: String = ""): List<Pair<String, List<Issue>>> {
    fun section(i: Issue) = if (i.status == "input-needed" && !i.belongsTo(me)) "on-hold" else i.status
    val order = compareByDescending<Issue> { it.updatedAt }
        .thenBy { !it.inProgress }
        .thenBy { it.priority }
        .thenBy { it.number }
    return OPEN.map { st -> st to issues.filter { section(it) == st }.sortedWith(order) }
}

/** Search every status and assignee, independently of the queue's visibility and collapsed sections. */
fun searchIssues(issues: List<Issue>, query: String): List<Issue> {
    val text = query.trim()
    val qualified = Regex("([A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)?)#(\\d+)").matchEntire(text)
    val qualifier = qualified?.groupValues?.get(1)
    val qualifiedNumber = qualified?.groupValues?.get(2)?.toIntOrNull()
    val digits = text.removePrefix("#").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    val numberOnly = text.startsWith("#") && digits != null
    fun rank(issue: Issue): Int = when {
        text.isEmpty() -> 0
        qualifier != null -> if (issue.number == qualifiedNumber && issue.repo.isNotEmpty() &&
            (issue.taskRepoName.equals(qualifier, ignoreCase = true) || issue.repo.equals(qualifier, ignoreCase = true))) 0 else 4
        digits != null && issue.number.toString() == digits -> 0
        digits != null && issue.number.toString().contains(digits) -> 1
        !numberOnly && issue.title.contains(text, ignoreCase = true) -> 2
        !numberOnly && issue.body.contains(text, ignoreCase = true) -> 3
        else -> 4
    }
    return issues.distinctBy { it.key }.filter { rank(it) < 4 }
        .sortedWith(compareBy<Issue> { rank(it) }.thenByDescending { it.updatedAt }.thenByDescending { it.number })
}

const val STALE_HOURS = 2

/** The holder named by a claim branch's tip commit ("claim #N by <id>"), or "" if released or stale (as in task.py). */
fun claimHolder(message: String, ageHours: Double): String {
    val m = Regex("^claim #\\d+ by (\\S+)").find(message) ?: return ""
    return if (ageHours > STALE_HOURS) "" else m.groupValues[1]
}

/** github.com/<o>/<r>/raw/<ref>/<path> (what `task.sh upload` prints) -> the contents API, which takes a token. */
fun apiImageUrl(url: String): String? {
    val m = Regex("^https://github\\.com/([^/]+)/([^/]+)/raw/([^/]+)/(.+)$").find(url)
        ?: Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$").find(url)
        ?: return null
    val (o, r, ref, path) = m.destructured
    return "https://api.github.com/repos/$o/$r/contents/$path?ref=$ref"
}
