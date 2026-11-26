package dev.braincore.boss

import kotlinx.coroutines.CancellationException
import java.io.IOException

internal data class TaskRepo(val name: String, val fullName: String)

internal data class ReadAccess(val problem: String? = null, val denied: Boolean = false)

internal data class RepositoryAccess(
    val repo: TaskRepo,
    val issues: ReadAccess? = null,
    val contents: ReadAccess? = null,
)

internal data class RepositoryAccessReport(
    val repositories: List<RepositoryAccess>,
    val registryProblem: String? = null,
)

internal suspend fun readTaskRepoRegistry(home: GitHub): Map<String, String> =
    taskReposOf(home.file("repos.yaml").text).filterValues { !it.equals(home.repo, ignoreCase = true) }

/** The settings check uses the credentials in the form and does not change the app's queue or configuration. */
internal suspend fun checkRepositoryAccess(
    home: GitHub,
    cachedRepos: List<TaskRepo>,
    usingApp: Boolean = false,
    connect: (String) -> GitHub,
): RepositoryAccessReport {
    var registryProblem: String? = null
    val repos = try {
        readTaskRepoRegistry(home).map { (name, fullName) -> TaskRepo(name, fullName) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        registryProblem = when (e) {
            is GitHubHttpException -> if (e.code == 404) {
                "GitHub can't find repos.yaml or this token can't read it (404)."
            } else {
                "Couldn't read repos.yaml. ${accessProblem(e)}"
            }
            is IOException -> "Couldn't read repos.yaml. ${accessProblem(e)}"
            else -> "Couldn't read the task repository list in repos.yaml. Check the file on GitHub."
        }
        cachedRepos
    }

    return RepositoryAccessReport(
        (listOf(TaskRepo("Workspace", home.repo)) + repos)
            .distinctBy { it.fullName.lowercase() }
            .map { repo ->
                val github = if (repo.fullName == home.repo) home else connect(repo.fullName)
                val appAccess = if (usingApp) probeRead {
                    if (!github.hasAppAccess()) throw GitHubAuthException("The GitHub App is not installed on this repository.")
                } else null
                if (appAccess?.problem != null) RepositoryAccess(repo, appAccess, appAccess)
                else RepositoryAccess(repo, probeRead { github.check() }, probeRead { github.checkContents() })
            },
        registryProblem,
    )
}

private suspend fun probeRead(check: suspend () -> Unit): ReadAccess = try {
    check()
    ReadAccess()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    val denied = e is GitHubAuthException || (e is GitHubHttpException && !e.rateLimited && e.code in listOf(401, 403, 404, 410))
    ReadAccess(accessProblem(e), denied)
}

private fun accessProblem(e: Exception): String = when {
    e is GitHubAuthException -> e.message.orEmpty()
    e is GitHubHttpException && e.rateLimited -> "GitHub rate limit reached. Try again later."
    e is GitHubHttpException -> when (e.code) {
        401 -> "GitHub rejected the token (401). Check whether it expired or was revoked."
        403 -> "GitHub denied access (403). Check the token's repositories and permissions."
        404 -> "GitHub can't find this resource or the token can't access it (404)."
        410 -> "GitHub reports this resource is disabled (410)."
        else -> "GitHub returned ${e.code}. Try again later."
    }
    e is IOException -> "Couldn't reach GitHub. Check your connection and try again."
    else -> "Couldn't check access. Try again."
}
