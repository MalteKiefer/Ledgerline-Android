package de.ledgerline.app.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.ui.theme.cardSurface
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One editable contact person (name/role/email/phone), each backed by observable state. */
internal class ContactRow(name: String, role: String, email: String, phone: String) {
    var name by mutableStateOf(name)
    var role by mutableStateOf(role)
    var email by mutableStateOf(email)
    var phone by mutableStateOf(phone)
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name.trim()); put("role", role.trim()); put("email", email.trim()); put("phone", phone.trim())
    }
    fun isBlank() = name.isBlank() && role.isBlank() && email.isBlank() && phone.isBlank()
}

private fun str(o: JsonObject, k: String): String = runCatching { o[k]?.jsonPrimitive?.content }.getOrNull() ?: ""

/**
 * Reusable contact-persons editor for company + partner (`[{name, role, email, phone}]`). Rebuilds the
 * JSON list and calls [onChange] on every edit; the caller persists it in its save body.
 */
@Composable
internal fun ContactsEditor(initial: List<JsonObject>, onChange: (List<JsonObject>) -> Unit) {
    val rows = remember { mutableStateListOf<ContactRow>().apply { initial.forEach { add(ContactRow(str(it, "name"), str(it, "role"), str(it, "email"), str(it, "phone"))) } } }
    fun emit() = onChange(rows.filterNot { it.isBlank() }.map { it.toJson() })

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEachIndexed { i, c ->
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(c.name, { c.name = it; emit() }, Modifier.weight(1f), label = { Text(stringResource(R.string.contact_name)) }, singleLine = true)
                    IconButton(onClick = { rows.removeAt(i); emit() }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
                }
                OutlinedTextField(c.role, { c.role = it; emit() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.contact_role)) }, singleLine = true)
                OutlinedTextField(c.email, { c.email = it; emit() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.contact_email)) }, singleLine = true)
                OutlinedTextField(c.phone, { c.phone = it; emit() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.contact_phone)) }, singleLine = true)
            }
        }
        TextButton(onClick = { rows.add(ContactRow("", "", "", "")) }) { Text(stringResource(R.string.contact_add)) }
    }
}
