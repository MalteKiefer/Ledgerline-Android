package de.ledgerline.app.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.CompanyProfile
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.PrimaryGradientButton
import de.ledgerline.app.ui.theme.cardSurface

/** Edit the non-secret company profile (business identity + invoice defaults) served by `/company`. */
@Composable
fun CompanyEditScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val company by vm.company.collectAsStateWithLifecycle()
    val c = company ?: CompanyProfile()

    var name by remember(c) { mutableStateOf(c.name) }
    var address by remember(c) { mutableStateOf(c.address) }
    var email by remember(c) { mutableStateOf(c.email) }
    var phone by remember(c) { mutableStateOf(c.phone) }
    var vatId by remember(c) { mutableStateOf(c.vatId) }
    var taxNumber by remember(c) { mutableStateOf(c.taxNumber) }
    var iban by remember(c) { mutableStateOf(c.iban) }
    var currency by remember(c) { mutableStateOf(c.currency) }
    var terms by remember(c) { mutableStateOf(c.paymentTermsDays.toString()) }
    var numberFormat by remember(c) { mutableStateOf(c.numberFormat) }
    var nextNumber by remember(c) { mutableStateOf(c.nextNumber.toString()) }
    var footer by remember(c) { mutableStateOf(c.footerText) }

    AppScaffold(
        topBar = { AppTopBar(title = stringResource(R.string.finance_company), onBack = onBack) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.finance_company_identity), style = MaterialTheme.typography.labelMedium, color = Brand.accent)
                Field(name, { name = it }, R.string.finance_customer_name)
                Field(address, { address = it }, R.string.finance_customer_address)
                Field(email, { email = it }, R.string.finance_customer_email)
                Field(phone, { phone = it }, R.string.finance_company_phone)
                Field(vatId, { vatId = it }, R.string.finance_customer_vatid)
                Field(taxNumber, { taxNumber = it }, R.string.finance_company_taxnr)
                Field(iban, { iban = it }, R.string.finance_company_iban)
            }
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.finance_company_defaults), style = MaterialTheme.typography.labelMedium, color = Brand.accent)
                Field(currency, { currency = it }, R.string.finance_currency)
                NumField(terms, { terms = it }, R.string.finance_company_terms)
                Field(numberFormat, { numberFormat = it }, R.string.finance_company_numfmt)
                NumField(nextNumber, { nextNumber = it }, R.string.finance_company_nextnr)
                Field(footer, { footer = it }, R.string.finance_company_footer)
            }
            PrimaryGradientButton(stringResource(R.string.action_save), onClick = {
                vm.saveCompany(
                    c.copy(
                        name = name.trim(), address = address.trim(), email = email.trim(), phone = phone.trim(),
                        vatId = vatId.trim(), taxNumber = taxNumber.trim(), iban = iban.trim(),
                        currency = currency.trim().ifBlank { "EUR" }, paymentTermsDays = terms.trim().toIntOrNull() ?: 14,
                        numberFormat = numberFormat.trim().ifBlank { "YYYY-NNNN" }, nextNumber = nextNumber.trim().toIntOrNull() ?: 1,
                        footerText = footer.trim(),
                    ),
                ) { ok -> if (ok) onBack() }
            })
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, labelRes: Int) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(labelRes)) }, singleLine = true)
}

@Composable
private fun NumField(value: String, onChange: (String) -> Unit, labelRes: Int) {
    OutlinedTextField(
        value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(labelRes)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
