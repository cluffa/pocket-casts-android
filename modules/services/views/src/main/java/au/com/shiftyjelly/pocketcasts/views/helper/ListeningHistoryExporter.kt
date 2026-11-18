@file:Suppress("DEPRECATION")

package au.com.shiftyjelly.pocketcasts.views.helper

import android.app.ProgressDialog
import android.content.Context
import au.com.shiftyjelly.pocketcasts.models.type.EpisodePlayingStatus
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import au.com.shiftyjelly.pocketcasts.localization.R as LR

/**
 * Fork addition: exports the listening history (episodes with a recorded playback
 * interaction, most recent first) as a CSV file and hands it to the share sheet,
 * mirroring how [OpmlExporter] shares the OPML file.
 */
class ListeningHistoryExporter(
    private val episodeManager: EpisodeManager,
    private val podcastManager: PodcastManager,
    private val context: Context,
    private val applicationScope: CoroutineScope,
) {

    private var exportJob: Job? = null
    private var progressDialog: ProgressDialog? = null

    fun saveFile() {
        showProgressDialog()
        exportJob?.cancel()
        exportJob = applicationScope.launch {
            val file = runCatching { exportCsv() }
                .onFailure { Timber.e(it) }
                .getOrNull()
                ?.takeIf(File::exists)

            withContext(Dispatchers.Main) {
                UiUtil.hideProgressDialog(progressDialog)
                if (file != null) {
                    IntentUtil.sendIntent(
                        context = context,
                        file = file,
                        intentType = "text/csv",
                        errorMessage = context.getString(LR.string.settings_opml_export_failed),
                        errorTitle = context.getString(LR.string.settings_no_file_browser_title),
                    )
                }
            }
        }
    }

    private suspend fun exportCsv(): File {
        val episodes = episodeManager.findPlaybackHistoryEpisodes()
        val podcastTitles = episodes.map { it.podcastUuid }.distinct().associateWith { uuid ->
            podcastManager.findPodcastByUuid(uuid)?.title.orEmpty()
        }

        val folder = File(context.filesDir, "email").apply { mkdirs() }
        val file = File(folder, "listening_history.csv")
        file.bufferedWriter().use { writer ->
            writer.appendLine(
                "podcast_title,episode_title,published_date,duration_seconds,played_up_to_seconds," +
                    "percent_listened,finished,last_played_at,starred,archived,episode_uuid,podcast_uuid",
            )
            for (episode in episodes) {
                val duration = episode.duration
                val percent = if (duration > 0) episode.playedUpTo / duration * 100 else 0.0
                val lastPlayed = episode.lastPlaybackInteraction
                    ?.takeIf { it > 0 }
                    ?.let { Instant.ofEpochMilli(it).toString() }
                    .orEmpty()
                val row = listOf(
                    csvEscape(podcastTitles[episode.podcastUuid].orEmpty()),
                    csvEscape(episode.title),
                    csvEscape(episode.publishedDate.toInstant().toString()),
                    String.format(Locale.US, "%.0f", duration),
                    String.format(Locale.US, "%.0f", episode.playedUpTo),
                    String.format(Locale.US, "%.1f", percent),
                    (episode.playingStatus == EpisodePlayingStatus.COMPLETED).toString(),
                    csvEscape(lastPlayed),
                    episode.isStarred.toString(),
                    episode.isArchived.toString(),
                    episode.uuid,
                    episode.podcastUuid,
                )
                writer.appendLine(row.joinToString(","))
            }
        }
        return file
    }

    private fun showProgressDialog() {
        UiUtil.hideProgressDialog(progressDialog)
        progressDialog = ProgressDialog.show(context, "", context.getString(LR.string.settings_opml_exporting), true, true) {
            exportJob?.cancel()
        }.apply {
            show()
        }
    }

    private fun csvEscape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
