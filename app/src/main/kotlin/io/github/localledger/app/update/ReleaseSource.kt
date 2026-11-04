package io.github.localledger.app.update

import io.github.localledger.app.BuildConfig
import io.github.localledger.core.AppVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal enum class ReleaseHost(val label: String, val webHost: String, val apiBase: String) {
    GITEE("Gitee", "gitee.com", "https://gitee.com/api/v5"),
    GITHUB("GitHub", "github.com", "https://api.github.com")
}

internal data class ReleaseSource(val host: ReleaseHost, val repository: String) {
    init { require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repository)) }
    val endpoint get() = "${host.apiBase}/repos/$repository/releases?per_page=100&page=1"
    fun page(tag: String) = "https://${host.webHost}/$repository/releases/tag/$tag"

    companion object {
        fun configured(): List<ReleaseSource> = buildList {
            if (BuildConfig.UPDATE_GITEE_REPOSITORY.isNotEmpty()) add(ReleaseSource(ReleaseHost.GITEE, BuildConfig.UPDATE_GITEE_REPOSITORY))
            if (BuildConfig.UPDATE_GITHUB_REPOSITORY.isNotEmpty()) add(ReleaseSource(ReleaseHost.GITHUB, BuildConfig.UPDATE_GITHUB_REPOSITORY))
        }
    }
}

internal data class AppRelease(val version: AppVersion, val source: ReleaseSource, val page: String)
internal sealed interface UpdateResult {
    data class Available(val releases: List<AppRelease>) : UpdateResult
    data object Current : UpdateResult
    data object Failed : UpdateResult
}

/** Malformed responses fail this source; valid empty lists count as a successful request. */
internal fun parseReleases(source: ReleaseSource, json: String): List<AppRelease> {
    val array = JSONArray(json)
    require(array.length() <= 100)
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            require(item.opt("tag_name") is String)
            if (item.optBoolean("draft") || item.optBoolean("prerelease")) continue
            val tag = item.getString("tag_name")
            val version = AppVersion.parse(tag) ?: continue
            // Construct the destination from the configured repository, never a remote redirect URL.
            add(AppRelease(version, source, source.page(tag)))
        }
    }
}

internal fun selectUpdate(currentVersion: AppVersion, results: List<Result<List<AppRelease>>>): UpdateResult {
    if (results.none { it.isSuccess }) return UpdateResult.Failed
    val newer = results.flatMap { it.getOrDefault(emptyList()) }.filter { it.version > currentVersion }
    val highest = newer.maxOfOrNull { it.version } ?: return UpdateResult.Current
    return UpdateResult.Available(newer.filter { it.version == highest }.distinctBy { it.source })
}

/** Both sources run independently. A failed source never suppresses another source's result. */
internal suspend fun checkUpdates(sources: List<ReleaseSource>, currentVersion: AppVersion,
                                 fetch: suspend (ReleaseSource) -> String = ::fetchReleaseJson): UpdateResult = supervisorScope {
    val requests = sources.map { source -> async {
        try {
            val releases = withTimeoutOrNull(12_000) { parseReleases(source, fetch(source)) }
                ?: error("Release source timed out")
            Result.success(releases)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.failure<List<AppRelease>>(IllegalStateException("Release source unavailable")) }
    } }
    selectUpdate(currentVersion, requests.map { it.await() })
}

internal suspend fun fetchReleaseJson(source: ReleaseSource): String = fetchReleaseJsonUsing(source) {
    it.openConnection() as HttpURLConnection
}

internal suspend fun fetchReleaseJsonUsing(source: ReleaseSource, open: (URL) -> HttpURLConnection): String = withContext(Dispatchers.IO) {
    val connection = open(URL(source.endpoint))
    try {
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "PureLedger-UpdateCheck")
        require(connection.responseCode == 200)
        require(connection.contentLengthLong <= 1_048_576)
        connection.inputStream.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            val started = System.nanoTime()
            while (true) {
                coroutineContext.ensureActive()
                require(System.nanoTime() - started < 10_000_000_000L)
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 1_048_576)
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    } finally { connection.disconnect() }
}

internal fun isReleasePage(release: AppRelease): Boolean = runCatching {
    val uri = URI(release.page)
    uri.scheme == "https" && uri.host == release.source.host.webHost && uri.port == -1 && uri.userInfo == null &&
        uri.query == null && uri.fragment == null &&
        uri.path.matches(Regex("/${Regex.escape(release.source.repository)}/releases/tag/v?[0-9]+\\.[0-9]+\\.[0-9]+"))
}.getOrDefault(false)
