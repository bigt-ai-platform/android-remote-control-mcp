package com.danielealbano.androidremotecontrolmcp.services.update

import com.danielealbano.androidremotecontrolmcp.data.model.AvailableUpdate
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What triggered an update check.
 * - [PERIODIC]: the recurring background worker; never time-throttled (WorkManager spaces it).
 * - [ON_OPEN]: the app-foreground one-shot; time-throttled so rapid foregrounding cannot spam the update host.
 * - [MANUAL]: the user's "Check now"; bypasses the enabled toggle and the throttle, and never notifies.
 */
enum class UpdateCheckTrigger {
    PERIODIC,
    ON_OPEN,
    MANUAL,
}

/** The result of an update check, surfaced to the UI for manual checks. */
sealed interface UpdateCheckOutcome {
    /** Automatic checks are disabled by the user and this was not a manual check. */
    data object Disabled : UpdateCheckOutcome

    /** An on-open check was skipped because an automatic check ran too recently. */
    data object Skipped : UpdateCheckOutcome

    /** The installed build is a local/dev build (or an unparseable version); checks are skipped. */
    data object DevBuild : UpdateCheckOutcome

    /** The check could not complete (offline, rate-limited, malformed response). */
    data object Failed : UpdateCheckOutcome

    /** The installed build is the latest. */
    data object UpToDate : UpdateCheckOutcome

    /** A newer release is available. */
    data class UpdateAvailable(
        val update: AvailableUpdate,
    ) : UpdateCheckOutcome
}

/**
 * Compares the installed versionCode against the latest published build (the OTA manifest) and, when
 * newer, persists it (so the in-app banner can render) and — for automatic checks only, once per new
 * version — posts a notification. Idempotent and fail-closed: any failure yields
 * [UpdateCheckOutcome.Failed] and leaves persisted state untouched.
 */
@Singleton
class UpdateCheckCoordinator
    @Inject
    constructor(
        private val checker: OtaManifestChecker,
        private val settingsRepository: SettingsRepository,
        private val notifier: UpdateNotifier,
        private val versionProvider: AppVersionProvider,
    ) {
        // Overridable for tests; production reads the wall clock.
        internal var nowMillis: () -> Long = { System.currentTimeMillis() }

        // Serializes the persist/notify read-modify-write so a concurrent periodic + on-open run (both
        // injected with this same @Singleton) cannot both observe "not yet notified" and double-post.
        private val applyMutex = Mutex()

        suspend fun check(trigger: UpdateCheckTrigger): UpdateCheckOutcome {
            val manual = trigger == UpdateCheckTrigger.MANUAL
            val gate = if (manual) null else automaticGate(trigger)
            return gate ?: proceed(manual)
        }

        // Returns a terminal outcome when an automatic check must NOT run, or null when it may proceed.
        private suspend fun automaticGate(trigger: UpdateCheckTrigger): UpdateCheckOutcome? =
            when {
                !settingsRepository.autoUpdateCheckEnabled.first() -> UpdateCheckOutcome.Disabled
                trigger == UpdateCheckTrigger.ON_OPEN && checkedRecently() -> UpdateCheckOutcome.Skipped
                else -> null
            }

        private suspend fun checkedRecently(): Boolean {
            val last = settingsRepository.getLastAutoCheckAtMillis()
            return last != 0L && nowMillis() - last < MIN_ON_OPEN_INTERVAL_MS
        }

        private suspend fun proceed(manual: Boolean): UpdateCheckOutcome {
            // git-describe stamps builds after a tag with a `-dev.<n>` pre-release segment; those are
            // development builds and must never be prompted to "update" to the tagged release.
            if (versionProvider.versionName.contains(DEV_MARKER)) {
                return UpdateCheckOutcome.DevBuild
            }
            // Record the attempt time for BOTH automatic triggers so the on-open throttle also counts the
            // most recent periodic run. Manual checks never touch the throttle window.
            if (!manual) settingsRepository.setLastAutoCheckAtMillis(nowMillis())
            return evaluateAgainstLatest(versionProvider.versionCode, manual)
        }

        private suspend fun evaluateAgainstLatest(
            currentVersionCode: Int,
            manual: Boolean,
        ): UpdateCheckOutcome {
            // A missing manifest means "could not determine the latest" → Failed.
            val manifest = checker.fetchLatestManifest() ?: return UpdateCheckOutcome.Failed
            return if (manifest.versionCode <= currentVersionCode) {
                // Clear any stale persisted update (e.g. the user has since upgraded).
                settingsRepository.setAvailableUpdate(null)
                UpdateCheckOutcome.UpToDate
            } else {
                applyUpdate(AvailableUpdate(manifest.versionName, manifest.downloadUrl), manual)
            }
        }

        private suspend fun applyUpdate(
            update: AvailableUpdate,
            manual: Boolean,
        ): UpdateCheckOutcome.UpdateAvailable =
            applyMutex.withLock {
                settingsRepository.setAvailableUpdate(update)
                // De-dup: surface a given version at most once. A notification is posted only for automatic
                // checks; a manual check shows the result in-app, so it records the version as "seen"
                // WITHOUT posting — suppressing a redundant notification for it on the next automatic run.
                if (settingsRepository.getNotifiedUpdateVersion() != update.versionName) {
                    if (!manual) {
                        notifier.notifyUpdateAvailable(update.versionName, update.releaseUrl)
                    }
                    settingsRepository.setNotifiedUpdateVersion(update.versionName)
                }
                UpdateCheckOutcome.UpdateAvailable(update)
            }

        companion object {
            // git-describe stamps builds after a tag with a `-dev.<n>` pre-release segment; those are
            // ahead of the tagged release and must never be prompted to "update" to that same tag.
            private const val DEV_MARKER = "-dev"

            // Minimum spacing between on-open checks. Keeps rapid foreground transitions from hammering
            // the update host; below the periodic interval so periodic runs are unaffected.
            private const val MIN_ON_OPEN_INTERVAL_MS = 60L * 60L * 1000L
        }
    }
