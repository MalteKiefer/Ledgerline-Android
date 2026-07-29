package de.ledgerline.app.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.asImageBitmap
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
    var bic by remember(c) { mutableStateOf(c.bic) }
    var bankName by remember(c) { mutableStateOf(c.bankName) }
    var vatRate by remember(c) { mutableStateOf(trimNum(c.defaultVatRate)) }
    var terms by remember(c) { mutableStateOf(c.paymentTermsDays.toString()) }
    var termsText by remember(c) { mutableStateOf(c.paymentTermsText) }
    var methods by remember(c) { mutableStateOf(c.paymentMethods) }
    var numberFormat by remember(c) { mutableStateOf(c.numberFormat) }
    var nextNumber by remember(c) { mutableStateOf(c.nextNumber.toString()) }
    var footer by remember(c) { mutableStateOf(c.footerText) }

    // GoBD: the sequence is locked once this year already has issued invoices (web parity).
    val invoices by vm.invoices.collectAsStateWithLifecycle()
    val thisYear = java.time.LocalDate.now().year.toString()
    val numberingLocked = invoices.any { !it.trashed && it.seq != null && it.issueDate.startsWith(thisYear) }

    // Load the stored logo (if any) for a preview.
    var logo by remember(c) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(c.hasLogo) {
        if (c.hasLogo) vm.loadCompanyLogo { bytes ->
            logo = bytes?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
        }
    }

    AppScaffold(
        topBar = { AppTopBar(title = stringResource(R.string.finance_company), onBack = onBack) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            logo?.let { bmp ->
                androidx.compose.foundation.Image(
                    bitmap = bmp,
                    contentDescription = stringResource(R.string.finance_company_logo),
                    modifier = Modifier.fillMaxWidth().height(88.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.finance_company_identity), style = MaterialTheme.typography.labelMedium, color = Brand.accent)
                Field(name, { name = it }, R.string.finance_customer_name)
                Field(address, { address = it }, R.string.finance_customer_address)
                Field(email, { email = it }, R.string.finance_customer_email)
                Field(phone, { phone = it }, R.string.finance_company_phone)
                Field(vatId, { vatId = it }, R.string.finance_customer_vatid)
                Field(taxNumber, { taxNumber = it }, R.string.finance_company_taxnr)
                Field(iban, { iban = it }, R.string.finance_company_iban)
                Field(bic, { bic = it }, R.string.finance_company_bic)
                Field(bankName, { bankName = it }, R.string.finance_company_bank)
            }
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.finance_company_defaults), style = MaterialTheme.typography.labelMedium, color = Brand.accent)
                Field(numberFormat, { numberFormat = it }, R.string.finance_company_numfmt, enabled = !numberingLocked)
                NumField(nextNumber, { nextNumber = it }, R.string.finance_company_nextnr, enabled = !numberingLocked)
                if (numberingLocked) {
                    Text(
                        stringResource(R.string.finance_company_numbering_locked),
                        style = MaterialTheme.typography.bodySmall, color = Brand.accent,
                    )
                }
                NumField(vatRate, { vatRate = it }, R.string.finance_company_vatrate, decimal = true)
                NumField(terms, { terms = it }, R.string.finance_company_terms)
                Field(termsText, { termsText = it }, R.string.finance_company_terms_text)
                Field(methods, { methods = it }, R.string.finance_company_methods)
                Field(footer, { footer = it }, R.string.finance_company_footer)
            }
            PrimaryGradientButton(stringResource(R.string.action_save), onClick = {
                vm.saveCompany(
                    c.copy(
                        name = name.trim(), address = address.trim(), email = email.trim(), phone = phone.trim(),
                        vatId = vatId.trim(), taxNumber = taxNumber.trim(), iban = iban.trim(),
                        bic = bic.trim(), bankName = bankName.trim(),
                        defaultVatRate = vatRate.replace(',', '.').trim().toDoubleOrNull() ?: 19.0,
                        paymentTermsDays = terms.trim().toIntOrNull() ?: 14,
                        paymentTermsText = termsText.trim(), paymentMethods = methods.trim(),
                        // Server bounds (openapi): invoice_next_number ∈ [1, 100_000_000].
                        numberFormat = numberFormat.trim().ifBlank { "YYYY-NNNN" }, nextNumber = (nextNumber.trim().toIntOrNull() ?: 1).coerceIn(1, 100_000_000),
                        footerText = footer.trim(),
                    ),
                ) { ok -> if (ok) onBack() }
            })
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, labelRes: Int, enabled: Boolean = true) {
    OutlinedTextField(
        value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(labelRes)) },
        singleLine = true, enabled = enabled,
    )
}

@Composable
private fun NumField(value: String, onChange: (String) -> Unit, labelRes: Int, enabled: Boolean = true, decimal: Boolean = false) {
    OutlinedTextField(
        value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(labelRes)) }, singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}

/** Show a whole VAT rate as `19`, a fractional one as `7.5` (no trailing `.0`). */
private fun trimNum(d: Double): String =
    if (d == kotlin.math.floor(d)) d.toLong().toString() else d.toString()
