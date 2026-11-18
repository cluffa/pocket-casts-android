@file:Suppress("DEPRECATION")

package au.com.shiftyjelly.pocketcasts.views.helper

import android.app.ProgressDialog
import android.content.Context
import au.com.shiftyjelly.pocketcasts.models.db.dao.PlaybackStatsDao
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
 * Fork addition: exports listening data as CSV files and hands them to the share
 * sheet, mirroring how [OpmlExporter] shares the OPML file. Two exports:
 * the full episode history (no 1000-row cap) and the per-play session events.
 */
class ListeningHistoryExporter(
    private val episodeManager: EpisodeManager,
    private val podcastManager: PodcastManager,
    private val playbackStatsDao: PlaybackStatsDao,
    private val context: Context,
    private val applicationScope: CoroutineScope,
) {

    private var exportJob: Job? = null
    private var progressDialog: ProgressDialog? = null

    fun saveFile() {
        shareWhenReady { exportEpisodesCsv() }
    }

    fun saveSessionsFile() {
        shareWhenReady { exportSessionsCsv() }
    }

    private fun shareWhenReady(buildCsv: suspend () -> File) {
        showProgressDialog()
        exportJob?.cancel()
        exportJob = applicationScope.launch {
            val file = runCatching { buildCsv() }
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

    private suspend fun exportEpisodesCsv(): File {
        val episodes = episodeManager.findAllPlaybackHistoryEpisodes()
        val podcastTitles = episodes.map { it.podcastUuid }.distinct().associateWith { uuid ->
            podcastManager.findPodcastByUuid(uuid)?.title.orEmpty()
        }

        val file = File(exportFolder(), "listening_history.csv")
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

    private suspend fun exportSessionsCsv(): File {
        val events = playbackStatsDao.selectAll().sortedBy { it.startedAtMs }
        val episodeTitles = episodeManager.findByUuids(events.map { it.episodeUuid }.distinct())
            .associateBy({ it.uuid }, { it.title })
        val podcastTitles = events.map { it.podcastUuid }.distinct().associateWith { uuid ->
            podcastManager.findPodcastByUuid(uuid)?.title.orEmpty()
        }

        val file = File(exportFolder(), "listening_sessions.csv")
        file.bufferedWriter().use { writer ->
            writer.appendLine("started_at,duration_listened_seconds,podcast_title,episode_title,episode_uuid,podcast_uuid")
            for (event in events) {
                val row = listOf(
                    csvEscape(Instant.ofEpochMilli(event.startedAtMs).toString()),
                    String.format(Locale.US, "%.1f", event.durationMs / 1000.0),
                    csvEscape(podcastTitles[event.podcastUuid].orEmpty()),
                    csvEscape(episodeTitles[event.episodeUuid].orEmpty()),
                    event.episodeUuid,
                    event.podcastUuid,
                )
                writer.appendLine(row.joinToString(","))
            }
        }
        return file
    }

    private fun exportFolder(): File = File(context.filesDir, "email").apply { mkdirs() }

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
