package de.ledgerline.app.core.autofill

import android.app.assist.AssistStructure
import android.view.View
import android.view.autofill.AutofillId

/**
 * Classifies the fillable fields in an [AssistStructure]: the username/identifier node and the
 * password node, plus the app package and (for browsers) the web domain of the screen requesting
 * autofill. Detection prefers the platform [View.getAutofillHints]; where an app supplies none it
 * falls back to heuristics over the node's id-entry / hint text / html attributes. Zero-knowledge:
 * this only inspects the *structure* of the foreign screen — it never touches the vault.
 */
object AutofillParsing {

    /** The classified fields for one fill request. */
    data class Parsed(
        val usernameId: AutofillId?,
        val passwordId: AutofillId?,
        val packageName: String?,
        val webDomain: String?,
    ) {
        val hasPassword: Boolean get() = passwordId != null
        val autofillIds: List<AutofillId> get() = listOfNotNull(usernameId, passwordId)
    }

    private val USERNAME_HINTS = setOf(
        View.AUTOFILL_HINT_USERNAME,
        View.AUTOFILL_HINT_EMAIL_ADDRESS,
        "email", "username", "user", "login", "phone", "tel",
    )
    private val PASSWORD_HINTS = setOf(
        View.AUTOFILL_HINT_PASSWORD,
        "password", "passwort", "pass", "pwd",
    )
    private val USERNAME_NEEDLES = listOf("email", "e-mail", "user", "login", "account", "phone", "mobile")
    private val PASSWORD_NEEDLES = listOf("password", "passwort", "passwd", "pwd", "pass")

    fun parse(structure: AssistStructure): Parsed {
        var username: AutofillId? = null
        var password: AutofillId? = null
        var webDomain: String? = null
        val packageName = structure.activityComponent?.packageName

        for (i in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(i).rootViewNode
            val found = walk(root)
            // First password wins; first username *before* the password is preferred, else any.
            if (username == null) username = found.username
            if (password == null) password = found.password
            if (webDomain == null) webDomain = found.webDomain
        }
        return Parsed(username, password, packageName, webDomain?.let(DomainMatch::normalizeHost))
    }

    private data class Walk(var username: AutofillId?, var password: AutofillId?, var webDomain: String?)

    private fun walk(node: AssistStructure.ViewNode): Walk {
        val acc = Walk(null, null, null)
        visit(node, acc)
        return acc
    }

    private fun visit(node: AssistStructure.ViewNode, acc: Walk) {
        if (acc.webDomain == null) node.webDomain?.takeIf { it.isNotBlank() }?.let { acc.webDomain = it }
        val id = node.autofillId
        if (id != null && node.autofillType != View.AUTOFILL_TYPE_NONE) {
            when (classify(node)) {
                Kind.PASSWORD -> if (acc.password == null) acc.password = id
                Kind.USERNAME -> if (acc.username == null) acc.username = id
                Kind.NONE -> {}
            }
        }
        for (i in 0 until node.childCount) visit(node.getChildAt(i), acc)
    }

    private enum class Kind { USERNAME, PASSWORD, NONE }

    private fun classify(node: AssistStructure.ViewNode): Kind {
        node.autofillHints?.forEach { raw ->
            val h = raw.lowercase()
            if (PASSWORD_HINTS.any { h.contains(it) }) return Kind.PASSWORD
            if (USERNAME_HINTS.any { h.contains(it) }) return Kind.USERNAME
        }
        // Heuristic fallback over id-entry, hint and HTML name/type.
        val haystack = buildString {
            node.idEntry?.let { append(it).append(' ') }
            node.hint?.let { append(it).append(' ') }
            node.htmlInfo?.attributes?.forEach { append(it.second).append(' ') }
        }.lowercase()
        if (haystack.isBlank()) return Kind.NONE
        val htmlType = node.htmlInfo?.attributes?.firstOrNull { it.first == "type" }?.second?.lowercase()
        if (htmlType == "password") return Kind.PASSWORD
        if (PASSWORD_NEEDLES.any { haystack.contains(it) }) return Kind.PASSWORD
        if (USERNAME_NEEDLES.any { haystack.contains(it) }) return Kind.USERNAME
        return Kind.NONE
    }
}
