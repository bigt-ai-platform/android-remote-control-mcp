package com.danielealbano.androidremotecontrolmcp.services.update

import com.danielealbano.androidremotecontrolmcp.data.model.AvailableUpdate
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("UpdateCheckCoordinator")
class UpdateCheckCoordinatorTest {
    private lateinit var checker: OtaManifestChecker
    private lateinit var settings: SettingsRepository
    private lateinit var notifier: UpdateNotifier
    private lateinit var versionProvider: AppVersionProvider
    private lateinit var coordinator: UpdateCheckCoordinator

    private val downloadUrl = "https://s3.bigt.ai/apps/android-remote-control-mcp/apk/MobileAgent.apk"

    @BeforeEach
    fun setUp() {
        checker = mockk()
        settings = mockk(relaxed = true)
        notifier = mockk(relaxed = true)
        versionProvider = mockk()
        coordinator = UpdateCheckCoordinator(checker, settings, notifier, versionProvider)

        every { versionProvider.versionName } returns "1.10.0"
        every { versionProvider.versionCode } returns INSTALLED_VERSION_CODE
        every { settings.autoUpdateCheckEnabled } returns flowOf(true)
        coEvery { settings.getNotifiedUpdateVersion() } returns ""
    }

    private fun manifest(
        versionCode: Int,
        versionName: String = "1.11.0",
    ) = OtaManifest(versionName, versionCode, downloadUrl)

    @Test
    fun `automatic check is skipped when disabled and never hits the network`() =
        runTest {
            every { settings.autoUpdateCheckEnabled } returns flowOf(false)

            val outcome = coordinator.check(UpdateCheckTrigger.PERIODIC)

            assertEquals(UpdateCheckOutcome.Disabled, outcome)
            coVerify(exactly = 0) { checker.fetchLatestManifest() }
        }

    @Test
    fun `manual check bypasses the disabled toggle`() =
        runTest {
            every { settings.autoUpdateCheckEnabled } returns flowOf(false)
            coEvery { checker.fetchLatestManifest() } returns manifest(NEWER_VERSION_CODE)

            val outcome = coordinator.check(UpdateCheckTrigger.MANUAL)

            assertInstanceOf(UpdateCheckOutcome.UpdateAvailable::class.java, outcome)
        }

    @Test
    fun `dev build is skipped without a network call`() =
        runTest {
            every { versionProvider.versionName } returns "1.10.0-dev.7+abc1234"

            val outcome = coordinator.check(UpdateCheckTrigger.PERIODIC)

            assertEquals(UpdateCheckOutcome.DevBuild, outcome)
            coVerify(exactly = 0) { checker.fetchLatestManifest() }
        }

    @Test
    fun `failed fetch yields Failed and leaves state untouched`() =
        runTest {
            coEvery { checker.fetchLatestManifest() } returns null

            val outcome = coordinator.check(UpdateCheckTrigger.PERIODIC)

            assertEquals(UpdateCheckOutcome.Failed, outcome)
            coVerify(exactly = 0) { settings.setAvailableUpdate(any()) }
        }

    @Test
    fun `up to date clears any stale persisted update`() =
        runTest {
            coEvery { checker.fetchLatestManifest() } returns manifest(INSTALLED_VERSION_CODE)

            val outcome = coordinator.check(UpdateCheckTrigger.PERIODIC)

            assertEquals(UpdateCheckOutcome.UpToDate, outcome)
            coVerify(exactly = 1) { settings.setAvailableUpdate(null) }
            verify(exactly = 0) { notifier.notifyUpdateAvailable(any(), any()) }
        }

    @Test
    fun `an older published build is treated as up to date`() =
        runTest {
            coEvery { checker.fetchLatestManifest() } returns manifest(INSTALLED_VERSION_CODE - 10)

            assertEquals(UpdateCheckOutcome.UpToDate, coordinator.check(UpdateCheckTrigger.PERIODIC))
        }

    @Test
    fun `a newer build persists the update and notifies once`() =
        runTest {
            coEvery { checker.fetchLatestManifest() } returns manifest(NEWER_VERSION_CODE)

            val outcome = coordinator.check(UpdateCheckTrigger.PERIODIC)

            assertEquals(UpdateCheckOutcome.UpdateAvailable(AvailableUpdate("1.11.0", downloadUrl)), outcome)
            coVerify(exactly = 1) { settings.setAvailableUpdate(AvailableUpdate("1.11.0", downloadUrl)) }
            verify(exactly = 1) { notifier.notifyUpdateAvailable("1.11.0", downloadUrl) }
            coVerify(exactly = 1) { settings.setNotifiedUpdateVersion("1.11.0") }
        }

    @Test
    fun `already-notified version updates the banner but does not re-notify`() =
        runTest {
            coEvery { checker.fetchLatestManifest() } returns manifest(NEWER_VERSION_CODE)
            coEvery { settings.getNotifiedUpdateVersion() } returns "1.11.0"

            coordinator.check(UpdateCheckTrigger.PERIODIC)

            coVerify(exactly = 1) { settings.setAvailableUpdate(AvailableUpdate("1.11.0", downloadUrl)) }
            verify(exactly = 0) { notifier.notifyUpdateAvailable(any(), any()) }
        }

    @Test
    fun `manual check records the version as seen but never posts a notification`() =
        runTest {
            coEvery { checker.fetchLatestManifest() } returns manifest(NEWER_VERSION_CODE)

            coordinator.check(UpdateCheckTrigger.MANUAL)

            coVerify(exactly = 1) { settings.setAvailableUpdate(AvailableUpdate("1.11.0", downloadUrl)) }
            verify(exactly = 0) { notifier.notifyUpdateAvailable(any(), any()) }
            // Recorded as seen so the next automatic check does not re-surface it as a notification.
            coVerify(exactly = 1) { settings.setNotifiedUpdateVersion("1.11.0") }
        }

    @Test
    fun `on-open check is skipped when an automatic check ran recently`() =
        runTest {
            val now = 10_000_000L
            coordinator.nowMillis = { now }
            // Last check 10 minutes ago (< the 1h on-open throttle window).
            coEvery { settings.getLastAutoCheckAtMillis() } returns now - 10L * 60L * 1000L

            val outcome = coordinator.check(UpdateCheckTrigger.ON_OPEN)

            assertEquals(UpdateCheckOutcome.Skipped, outcome)
            coVerify(exactly = 0) { checker.fetchLatestManifest() }
        }

    @Test
    fun `on-open check proceeds when the last check is old and records the time`() =
        runTest {
            val now = 10_000_000L
            coordinator.nowMillis = { now }
            // Last check 2 hours ago (> the 1h on-open throttle window).
            coEvery { settings.getLastAutoCheckAtMillis() } returns now - 2L * 60L * 60L * 1000L
            coEvery { checker.fetchLatestManifest() } returns manifest(NEWER_VERSION_CODE)

            val outcome = coordinator.check(UpdateCheckTrigger.ON_OPEN)

            assertInstanceOf(UpdateCheckOutcome.UpdateAvailable::class.java, outcome)
            coVerify(exactly = 1) { settings.setLastAutoCheckAtMillis(now) }
        }

    @Test
    fun `on-open check proceeds when no automatic check has ever run`() =
        runTest {
            coEvery { settings.getLastAutoCheckAtMillis() } returns 0L
            coEvery { checker.fetchLatestManifest() } returns manifest(NEWER_VERSION_CODE)

            val outcome = coordinator.check(UpdateCheckTrigger.ON_OPEN)

            assertInstanceOf(UpdateCheckOutcome.UpdateAvailable::class.java, outcome)
            coVerify(exactly = 1) { checker.fetchLatestManifest() }
        }

    private companion object {
        // Illustrative git-derived codes: installed build older than the published release.
        const val INSTALLED_VERSION_CODE = 1_010_000
        const val NEWER_VERSION_CODE = 1_011_000
    }
}
