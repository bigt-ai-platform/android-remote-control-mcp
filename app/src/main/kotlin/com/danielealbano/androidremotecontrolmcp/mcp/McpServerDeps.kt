package com.danielealbano.androidremotecontrolmcp.mcp

import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthServerDeps
import com.danielealbano.androidremotecontrolmcp.mcp.pairing.PairingServerDeps

/** Groups the `oauth` and `pairing` collaborator bundles passed into [McpServer] (the other four
 *  collaborators stay as direct parameters) so its constructor stays within detekt's LongParameterList. */
class McpServerDeps(
    val oauth: OAuthServerDeps,
    val pairing: PairingServerDeps,
)
