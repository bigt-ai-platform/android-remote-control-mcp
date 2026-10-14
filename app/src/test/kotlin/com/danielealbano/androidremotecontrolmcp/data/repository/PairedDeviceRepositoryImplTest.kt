package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PairedDeviceRepositoryImplTest {
    @TempDir
    lateinit var tempDir: File

    private fun repository(): PairedDeviceRepositoryImpl {
        val dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "paired_devices.preferences_pb") },
            )
        return PairedDeviceRepositoryImpl(dataStore, mockk(relaxed = true))
    }

    @Test
    @DisplayName("claim persists a hashed record and returns the token once")
    fun claimPersistsHash() =
        runTest {
            val repository = repository()

            val claimed = repository.claim("wallet", nowMs = 1_000L)

            assertEquals(64, claimed.token.length)
            val devices = repository.getDevices()
            assertEquals(1, devices.size)
            assertEquals("wallet", devices.first().name)
            assertNotEquals(claimed.token, devices.first().tokenSha256)
        }

    @Test
    @DisplayName("validate accepts a claimed token and rejects others")
    fun validateAcceptsClaimed() =
        runTest {
            val repository = repository()
            val claimed = repository.claim(null, nowMs = 1_000L)

            assertTrue(repository.validate(claimed.token, nowMs = 1_000L))
            assertFalse(repository.validate("deadbeef", nowMs = 1_000L))
        }

    @Test
    @DisplayName("validate debounces lastUsed updates")
    fun validateDebounces() =
        runTest {
            val repository = repository()
            val claimed = repository.claim(null, nowMs = 1_000L)

            assertTrue(repository.validate(claimed.token, nowMs = 2_000L))
            assertEquals(1_000L, repository.getDevices().first().lastUsedAtMs)

            assertTrue(repository.validate(claimed.token, nowMs = 61_000L))
            assertEquals(61_000L, repository.getDevices().first().lastUsedAtMs)
        }

    @Test
    @DisplayName("revoke invalidates the token")
    fun revokeInvalidates() =
        runTest {
            val repository = repository()
            val claimed = repository.claim(null, nowMs = 1_000L)

            repository.revoke(claimed.device.id)

            assertFalse(repository.validate(claimed.token, nowMs = 2_000L))
            assertTrue(repository.getDevices().isEmpty())
        }

    @Test
    @DisplayName("revokeAll clears every device")
    fun revokeAllClears() =
        runTest {
            val repository = repository()
            repository.claim("a", nowMs = 1_000L)
            repository.claim("b", nowMs = 2_000L)

            repository.revokeAll()

            assertTrue(repository.getDevices().isEmpty())
        }

    @Test
    @DisplayName("claim evicts the least-recently-used beyond MAX_PAIRED_DEVICES")
    fun claimEvictsOldest() =
        runTest {
            val repository = repository()
            repeat(PairedDeviceRepository.MAX_PAIRED_DEVICES + 1) { index ->
                repository.claim("device-$index", nowMs = (index + 1).toLong())
            }

            val devices = repository.getDevices()
            assertEquals(PairedDeviceRepository.MAX_PAIRED_DEVICES, devices.size)
            assertFalse(devices.any { it.name == "device-0" })
        }

    @Test
    @DisplayName("observeDevices emits persisted state")
    fun observeDevicesEmits() =
        runTest {
            val repository = repository()
            repository.claim("wallet", nowMs = 1_000L)

            val first = repository.observeDevices().first()

            assertEquals(1, first.size)
            assertEquals("wallet", first.first().name)
        }
}
