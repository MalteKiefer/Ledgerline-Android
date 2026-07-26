package de.ledgerline.app.core.autofill

import android.app.assist.AssistStructure
import android.content.Intent
import android.content.IntentSender
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.widget.RemoteViews
import de.ledgerline.app.R
import de.ledgerline.app.ui.autofill.AutofillUnlockActivity

/**
 * Zero-knowledge Autofill provider. [onFillRequest] only inspects the *structure* of the foreign
 * screen — it never decrypts the vault. It returns a single authenticated entry; tapping it opens
 * [AutofillUnlockActivity], which unlocks the vault (biometric/passphrase, reusing the app's unlock
 * path), matches credentials to the requesting domain/app, and returns the concrete [Dataset]. No
 * plaintext credential ever crosses into the OS before the user authenticates.
 */
class LedgerlineAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val structure = latestStructure(request)
        if (structure == null) { callback.onSuccess(null); return }

        val parsed = AutofillParsing.parse(structure)
        if (parsed.passwordId == null && parsed.usernameId == null) {
            callback.onSuccess(null)
            return
        }

        val presentation = RemoteViews(packageName, R.layout.autofill_entry).apply {
            setTextViewText(R.id.autofill_entry_text, getString(R.string.autofill_unlock_and_fill))
        }

        val authSender: IntentSender = AutofillUnlockActivity.authIntentSender(
            context = this,
            usernameId = parsed.usernameId,
            passwordId = parsed.passwordId,
            packageName = parsed.packageName,
            webDomain = parsed.webDomain,
        )

        val datasetBuilder = Dataset.Builder(presentation).setAuthentication(authSender)
        // The dataset must declare which fields it targets (values filled after auth).
        parsed.usernameId?.let { datasetBuilder.setValue(it, null, presentation) }
        parsed.passwordId?.let { datasetBuilder.setValue(it, null, presentation) }

        val responseBuilder = FillResponse.Builder().addDataset(datasetBuilder.build())

        // Offer to save newly-entered credentials.
        val saveIds = parsed.autofillIds.toTypedArray()
        if (saveIds.isNotEmpty()) {
            val saveType = if (parsed.usernameId != null && parsed.passwordId != null) {
                SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD
            } else {
                SaveInfo.SAVE_DATA_TYPE_PASSWORD
            }
            responseBuilder.setSaveInfo(SaveInfo.Builder(saveType, saveIds).build())
        }

        callback.onSuccess(responseBuilder.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) { callback.onSuccess(); return }
        val parsed = AutofillParsing.parse(structure)
        // Hand off to the app to persist under the unlocked vault. The activity reads the captured
        // values from the structure it re-parses via the passed ids + a one-shot save payload.
        val intent = AutofillUnlockActivity.saveIntent(
            context = this,
            username = valueOf(structure, parsed.usernameId),
            password = valueOf(structure, parsed.passwordId),
            packageName = parsed.packageName,
            webDomain = parsed.webDomain,
        )
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        callback.onSuccess()
    }

    private fun latestStructure(request: FillRequest): AssistStructure? =
        request.fillContexts.lastOrNull()?.structure

    private fun valueOf(structure: AssistStructure, id: android.view.autofill.AutofillId?): String? {
        if (id == null) return null
        for (i in 0 until structure.windowNodeCount) {
            find(structure.getWindowNodeAt(i).rootViewNode, id)?.let { return it }
        }
        return null
    }

    private fun find(node: AssistStructure.ViewNode, id: android.view.autofill.AutofillId): String? {
        if (node.autofillId == id) {
            node.autofillValue?.let { v -> if (v.isText) return v.textValue.toString() }
            node.text?.let { return it.toString() }
        }
        for (i in 0 until node.childCount) find(node.getChildAt(i), id)?.let { return it }
        return null
    }
}
