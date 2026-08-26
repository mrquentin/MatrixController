package com.mrquentinet.matrixcontroller.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mrquentinet.matrixcontroller.domain.BoardError

/**
 * A form-field error that is either a fixed string resource (validation) or a failure the board
 * itself reported (reachability). Modelling both in one type keeps the "res id *or* BoardError,
 * never both" invariant out of the ViewModel state.
 */
sealed interface FieldError {
    data class Res(val id: Int) : FieldError
    data class Board(val error: BoardError) : FieldError
}

@Composable
fun FieldError.text(): String = when (this) {
    is FieldError.Res -> stringResource(id)
    is FieldError.Board -> error.message()
}
