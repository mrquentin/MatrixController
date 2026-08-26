package com.mrquentinet.matrixcontroller.ui.boards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mrquentinet.matrixcontroller.R
import com.mrquentinet.matrixcontroller.ui.common.message
import com.mrquentinet.matrixcontroller.ui.common.text

/** Stable handles for UI tests; the field labels are localised and not safe to match on. */
const val ADD_BOARD_NAME_TAG = "addBoard.name"
const val ADD_BOARD_ADDRESS_TAG = "addBoard.address"

/**
 * The whole add-board flow lives in this one dialog: the board has to answer `GET /` and then
 * complete pairing before it is persisted, so the list can never contain a board that does not
 * exist or that still needs to be set up.
 */
@Composable
fun AddBoardDialog(
    state: AddBoardUiState,
    onNameChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onCheck: () -> Unit,
    onPair: () -> Unit,
    onRecheck: () -> Unit,
    onDismiss: () -> Unit,
) {
    val step = state.step
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_board_title)) },
        text = {
            when (step) {
                AddBoardStep.Form -> AddBoardForm(state, onNameChange, onAddressChange)
                is AddBoardStep.Pairing -> AddBoardPairing(state, step, onRecheck)
            }
        },
        confirmButton = {
            when (step) {
                AddBoardStep.Form -> TextButton(onClick = onCheck, enabled = !state.checking) {
                    if (state.checking) {
                        CircularWavyProgressIndicator(Modifier.size(20.dp))
                    } else {
                        Text(stringResource(R.string.action_continue))
                    }
                }

                is AddBoardStep.Pairing -> TextButton(
                    onClick = onPair,
                    enabled = !step.pairing && !state.checking,
                ) {
                    if (step.pairing) {
                        CircularWavyProgressIndicator(Modifier.size(20.dp))
                    } else {
                        Text(stringResource(R.string.action_pair))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun AddBoardForm(
    state: AddBoardUiState,
    onNameChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth().testTag(ADD_BOARD_NAME_TAG),
            enabled = !state.checking,
            label = { Text(stringResource(R.string.field_name)) },
            placeholder = { Text(stringResource(R.string.field_name_placeholder)) },
            isError = state.nameError != null,
            supportingText = state.nameError?.let { { Text(stringResource(it)) } },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.address,
            onValueChange = onAddressChange,
            modifier = Modifier.fillMaxWidth().testTag(ADD_BOARD_ADDRESS_TAG),
            enabled = !state.checking,
            label = { Text(stringResource(R.string.field_address)) },
            placeholder = { Text(stringResource(R.string.field_address_placeholder)) },
            isError = state.addressError != null,
            supportingText = {
                Text(state.addressError?.text() ?: stringResource(R.string.field_address_support))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
        )
    }
}

@Composable
private fun AddBoardPairing(
    state: AddBoardUiState,
    step: AddBoardStep.Pairing,
    onRecheck: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Confirms to the user that the address really is a board before they commit to it.
        Text(
            text = stringResource(
                R.string.add_board_found,
                step.info.device,
                step.info.firmwareVersion,
            ),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.pair_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (step.info.pairingOpen) {
            Text(
                text = stringResource(R.string.pair_window_open, step.info.pairingExpiresInSeconds),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        step.error?.let { error ->
            Text(
                text = error.message(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(
            onClick = onRecheck,
            enabled = !state.checking && !step.pairing,
        ) {
            Text(stringResource(R.string.action_check_again))
        }
    }
}
