package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Keeps list drags inside the content, including overscroll at either end. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScrollableBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.semantics {
            dismiss {
                onDismissRequest()
                true
            }
        },
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.semantics { contentDescription = "Close sheet" },
            ) {
                Text("Close")
            }
        }
        content()
    }
}
