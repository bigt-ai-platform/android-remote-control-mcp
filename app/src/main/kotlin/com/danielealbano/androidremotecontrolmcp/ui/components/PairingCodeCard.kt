@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danielealbano.androidremotecontrolmcp.R
import java.text.DateFormat
import java.util.Date

/**
 * Shows the short single-use pairing code and its expiry, with Copy / New-code actions.
 * Purely presentational; the caller supplies the code and handles both actions.
 */
@Composable
fun PairingCodeCard(
    code: String,
    expiresAtMs: Long,
    onCopy: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.pairing_code_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.pairing_code_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = code,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.pairing_code_expires, timeFormat.format(Date(expiresAtMs))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.align(Alignment.End),
            ) {
                TextButton(onClick = { onCopy(code) }) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null)
                    Text(
                        text = stringResource(R.string.pairing_code_copy),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                TextButton(onClick = onRefresh) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                    Text(
                        text = stringResource(R.string.pairing_code_refresh),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}
