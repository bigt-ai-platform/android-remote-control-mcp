package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.mcp.pairing.PairingCode
import com.danielealbano.androidremotecontrolmcp.mcp.pairing.PairingCodeRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PairingViewModel
    @Inject
    constructor(
        private val pairingCodeRegistry: PairingCodeRegistry,
    ) : ViewModel() {
        /**
         * The active pairing code, or null. Clears itself when the code expires (the registry only
         * nulls lazily on `current()`/`consume()`, which the UI never calls) so the card hides promptly.
         */
        val pairingCode: StateFlow<PairingCode?> =
            pairingCodeRegistry.code
                .transformLatest { current ->
                    val remaining = current?.expiresAtMs?.minus(System.currentTimeMillis()) ?: 0L
                    if (current == null || remaining <= 0) {
                        emit(null)
                    } else {
                        emit(current)
                        delay(remaining)
                        emit(null)
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), null)

        /** Mints a fresh code (the previous one stops working). */
        fun refresh() {
            pairingCodeRegistry.issue()
        }

        private companion object {
            const val FLOW_TIMEOUT_MS = 5_000L
        }
    }
