package com.danielealbano.androidremotecontrolmcp.mcp.pairing

import com.danielealbano.androidremotecontrolmcp.data.repository.PairedDeviceRepository

/** Groups the pairing collaborators passed into `McpServer` (kept out of its constructor). */
class PairingServerDeps(
    val codeRegistry: PairingCodeRegistry,
    val deviceRepository: PairedDeviceRepository,
)
