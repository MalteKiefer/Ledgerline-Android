package de.ledgerline.app.ui.workspace.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.domain.workspace.Tags

/**
 * Chip-based tag editor (web/iOS parity, replacing the old raw comma text field): existing
 * tags render as removable [InputChip]s, typing + comma/Enter turns the draft into a chip,
 * and Backspace on an empty field drops the last chip. The [draft] is hoisted so the host
 * editor can fold any half-typed text into the saved tags via [Tags.mergeDraft] (no silent
 * loss). Storage stays a plain `List<String>`.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TagInput(
    tags: List<String>,
    onTagsChange: (List<String>) -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.tags_hint),
) {
    fun commit(raw: String) {
        val next = Tags.mergeDraft(tags, raw)
        if (next != tags) onTagsChange(next)
        onDraftChange("")
    }
    Column(modifier) {
        if (tags.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = { onTagsChange(tags - tag) },
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(Icons.Outlined.Close, stringResource(R.string.tag_remove), Modifier.padding(start = 2.dp))
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { v ->
                // A comma or newline finalises the current draft into a chip.
                if (v.endsWith(',') || v.endsWith('\n')) commit(v) else onDraftChange(v)
            },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit(draft) }),
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && draft.isEmpty() && tags.isNotEmpty()) {
                        onTagsChange(tags.dropLast(1)); true
                    } else false
                },
        )
    }
}
