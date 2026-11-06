package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.settings.AboutBundledNotices
import moe.rukamori.archivetune.shared.ui.settings.AboutContributor
import moe.rukamori.archivetune.shared.ui.settings.AboutContributorsState
import moe.rukamori.archivetune.shared.ui.settings.AboutDialog
import moe.rukamori.archivetune.shared.ui.settings.AboutFailureMessages
import moe.rukamori.archivetune.shared.ui.settings.AboutLicensesState
import moe.rukamori.archivetune.shared.ui.settings.AboutSettings
import moe.rukamori.archivetune.shared.ui.settings.AboutTranslationEntry
import moe.rukamori.archivetune.shared.ui.settings.AboutTranslationsState
import moe.rukamori.archivetune.shared.ui.settings.NatuneAboutLogo
import moe.rukamori.archivetune.shared.ui.settings.SettingsController

/**
 * The baseline About lists on desktop: GitHub contributors and the Weblate translators per `values-*` folder, read from
 * the same repository endpoints as Android. Results stay in memory for the session; Android's DataStore cache has no
 * desktop counterpart. The generated Android dependency catalog is unavailable here, so the licenses dialog
 * lists the bundled data notices instead.
 */
internal class DesktopAboutRepository(private val http: HttpClient) {
    private val lock = Mutex()
    private var contributors: List<AboutContributor>? = null
    private var translations: List<AboutTranslationEntry>? = null
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun contributors(): Result<List<AboutContributor>> = lock.withLock {
        contributors?.let { return@withLock Result.success(it) }
        attempt {
            val response = github("$GITHUB_REPO_API/contributors") { parameter("per_page", CONTRIBUTORS_LIMIT) }
            check(response.status.value in 200..299) { "GitHub contributors request failed" }
            val parsed = (json.parseToJsonElement(response.bodyAsText()) as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { item ->
                    val login = item.text("login").orEmpty()
                    val avatar = item.text("avatar_url").orEmpty()
                    val bot = item.text("type").equals("Bot", ignoreCase = true) || login.lowercase().endsWith("[bot]")
                    if (bot || login.isBlank() || avatar.isBlank()) null
                    else AboutContributor(login, avatar, item.text("html_url").orEmpty())
                }
                .take(CONTRIBUTORS_LIMIT)
            parsed.also { if (it.isNotEmpty()) contributors = it }
        }
    }

    suspend fun translations(): Result<List<AboutTranslationEntry>> = lock.withLock {
        translations?.let { return@withLock Result.success(it) }
        attempt {
            val resources = github("$GITHUB_REPO_API/contents/$TRANSLATION_RESOURCE_ROOT")
            check(resources.status.value in 200..299) { "GitHub resource request failed" }
            val languages = (json.parseToJsonElement(resources.bodyAsText()) as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { resource ->
                    val name = resource.text("name").orEmpty()
                    val qualifier = name.removePrefix(TRANSLATION_RESOURCE_PREFIX)
                    if (resource.text("type") != "dir" || !name.startsWith(TRANSLATION_RESOURCE_PREFIX) || qualifier.isBlank()) {
                        null
                    } else {
                        val path = resource.text("path")?.takeIf(String::isNotBlank) ?: "$TRANSLATION_RESOURCE_ROOT/$name"
                        qualifier.toLanguageDisplayName() to path
                    }
                }
                .sortedBy { it.first.lowercase() }
            val entries = languages.mapNotNull { (language, path) ->
                translators(path).takeIf { it.isNotEmpty() }?.let { AboutTranslationEntry(language, it.joinToString(", ")) }
            }
            if (entries.isEmpty()) throw IllegalStateException("No translation contributors found")
            entries.also { translations = it }
        }
    }

    private suspend fun translators(path: String): List<String> {
        val names = LinkedHashSet<String>()
        var page = 1
        while (names.size < MAX_TRANSLATORS_PER_LANGUAGE && page <= MAX_COMMIT_PAGES_PER_LANGUAGE) {
            val response = github("$GITHUB_REPO_API/commits") {
                parameter("path", path)
                parameter("per_page", COMMITS_PAGE_SIZE)
                parameter("page", page)
            }
            check(response.status.value in 200..299) { "GitHub commits request failed with HTTP ${response.status.value}" }
            val commits = (json.parseToJsonElement(response.bodyAsText()) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            if (commits.isEmpty()) break
            for (commit in commits) {
                if (names.size == MAX_TRANSLATORS_PER_LANGUAGE) break
                val details = commit["commit"] as? JsonObject
                if (details?.text("message")?.startsWith(TRANSLATION_COMMIT_PREFIX, ignoreCase = true) != true) continue
                val author = (commit["author"] as? JsonObject)?.text("login")?.takeIf(String::isNotBlank)
                    ?: (details["author"] as? JsonObject)?.text("name")?.takeIf(String::isNotBlank)
                val name = author?.trim()?.takeIf(String::isNotBlank) ?: continue
                if (IGNORED_TRANSLATORS.any { name.equals(it, ignoreCase = true) }) continue
                names.add(name)
            }
            page++
        }
        return names.toList()
    }

    private suspend fun github(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        http.get(url) {
            header("Accept", "application/vnd.github+json")
            header("User-Agent", "ArchiveTune")
            block()
        }

    private inline fun <T> attempt(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            desktopDiagnostic("about list request failed: ${failure.javaClass.simpleName}")
            Result.failure(failure)
        }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun String.toLanguageDisplayName(): String {
        val tag = toLanguageTag()
        val display = Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH).trim()
        return display.takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) } ?: this
    }

    private fun String.toLanguageTag(): String {
        if (startsWith("b+")) return removePrefix("b+").replace('+', '-')
        val segments = split('-').filter(String::isNotBlank)
        if (segments.isEmpty()) return this
        val language = when (val code = segments.first()) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            else -> code
        }
        val rest = segments.drop(1).map { if (it.startsWith("r") && it.length > 1) it.drop(1) else it }
        return (listOf(language) + rest).joinToString("-")
    }

    private companion object {
        const val GITHUB_REPO_API = "https://api.github.com/repos/rukamori/ArchiveTune"
        const val TRANSLATION_RESOURCE_ROOT = "app/src/main/res"
        const val TRANSLATION_RESOURCE_PREFIX = "values-"
        const val TRANSLATION_COMMIT_PREFIX = "Translated using Weblate"
        const val CONTRIBUTORS_LIMIT = 20
        const val COMMITS_PAGE_SIZE = 100
        const val MAX_COMMIT_PAGES_PER_LANGUAGE = 2
        const val MAX_TRANSLATORS_PER_LANGUAGE = 6
        val IGNORED_TRANSLATORS = setOf("weblate:commit", "Codeberg Translate", "anonymous", "anynymous")
    }
}

/** Settings > About on this host. The desktop app ships no updater, so the update row stays hidden. */
@Composable
internal fun DesktopAboutPage(
    graph: DesktopSessionGraph,
    controller: SettingsController,
    navigation: ShellNavigation,
) {
    val repository = remember(graph) { DesktopAboutRepository(graph.http) }
    val scope = rememberCoroutineScope()
    var contributorsState by remember { mutableStateOf<AboutContributorsState>(AboutContributorsState.Loading) }
    var translationsState by remember { mutableStateOf<AboutTranslationsState>(AboutTranslationsState.Loading) }
    var dialog by remember { mutableStateOf(AboutDialog.NONE) }
    fun loadContributors() {
        contributorsState = AboutContributorsState.Loading
        scope.launch {
            contributorsState = repository.contributors().fold(
                onSuccess = { if (it.isEmpty()) AboutContributorsState.Empty else AboutContributorsState.Loaded(it) },
                onFailure = { AboutContributorsState.Failed(AboutFailureMessages.requestFailed) },
            )
        }
    }
    fun loadTranslations() {
        translationsState = AboutTranslationsState.Loading
        scope.launch {
            translationsState = repository.translations().fold(
                onSuccess = { AboutTranslationsState.Loaded(it) },
                onFailure = { AboutTranslationsState.Failed(AboutFailureMessages.requestFailed) },
            )
        }
    }
    LaunchedEffect(repository) { loadContributors() }
    // The jar manifest carries no Implementation-Version, so a released build reported "Development build" here. The
    // version the build stamps into DesktopBuildIdentity is the one the updater, the feedback report and CI use.
    val version = DesktopBuildIdentity.VERSION_NAME.takeIf(String::isNotBlank)
        ?: DesktopAboutRepository::class.java.`package`?.implementationVersion?.takeIf(String::isNotBlank)
        ?: DesktopStrings["about_version_development"]
    AboutSettings(
        controller = controller,
        isNightlyBuild = DesktopBuildIdentity.VERSION_NAME.startsWith("N"),
        updaterAvailable = false,
        appName = DesktopStrings["app_name"],
        versionLine = version,
        distributionLabel = DesktopStrings["about_distribution"],
        debugOrAbiLabel = System.getProperty("os.arch").orEmpty(),
        appLogo = { NatuneAboutLogo() },
        memberAvatar = { url, _, modifier -> DesktopRemoteArtwork(url, graph.artworkLoader, modifier) },
        contributorsState = contributorsState,
        translationsState = translationsState,
        licensesState = AboutLicensesState.Loaded(AboutBundledNotices.entries, bundledOnly = true),
        activeDialog = dialog,
        navigate = { navigation.open(it) },
        onOpenUri = { openDesktopUri(it, graph::report) },
        onRetryContributors = ::loadContributors,
        onOpenTranslations = {
            dialog = AboutDialog.TRANSLATION_CONTRIBUTORS
            if (translationsState !is AboutTranslationsState.Loaded) loadTranslations()
        },
        onRetryTranslations = ::loadTranslations,
        onOpenLicenses = { dialog = AboutDialog.DEPENDENCY_LICENSES },
        onRetryLicenses = { },
        onDismissDialog = { dialog = AboutDialog.NONE },
    )
}
