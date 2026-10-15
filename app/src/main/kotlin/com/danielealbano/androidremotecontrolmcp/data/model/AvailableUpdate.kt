package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * A newer app build detected via the OTA manifest and available to install.
 *
 * @property versionName The release's `versionName`, shown in the banner and notification.
 * @property releaseUrl The public APK download URL opened when the user accepts the update.
 */
data class AvailableUpdate(
    val versionName: String,
    val releaseUrl: String,
)
