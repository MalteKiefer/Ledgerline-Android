package de.ledgerline.app.core.finance

import de.ledgerline.app.domain.model.Project
import de.ledgerline.app.domain.model.Receipt
import de.ledgerline.app.domain.model.Transaction
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Cost projects (client-side, ZK) — a port of the web `shared/finance-projects.js`. A project bundles
 * receipts + manual "hand" expenses and nests via `parentId`; totals roll up through the tree. Pure.
 */
object FinanceProjects {

    /** A receipt paired with the transaction it's attached to (for amount fallback). */
    data class ReceiptRef(val receipt: Receipt, val tx: Transaction)

    data class TreeRow(val project: Project, val depth: Int)

    private fun round2(n: Double) = (n * 100.0).roundToLong() / 100.0

    /** The amount a bundled receipt contributes: its recognised gross, else the booking amount. */
    fun receiptAmount(r: Receipt, tx: Transaction?): Double {
        val t = r.total
        if (t != null && t > 0) return t
        return abs(tx?.amount ?: 0.0)
    }

    /** Direct children of a project id (null = root level). */
    fun projectChildren(projects: List<Project>, parentId: String?): List<Project> =
        projects.filter { (it.parentId ?: "") == (parentId ?: "") }

    /** All descendant ids of a project (excludes itself), cycle-safe. */
    fun descendantIds(projects: List<Project>, id: String): List<String> {
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        fun walk(pid: String) {
            for (c in projectChildren(projects, pid)) {
                if (!seen.add(c.id)) continue
                out.add(c.id); walk(c.id)
            }
        }
        walk(id)
        return out
    }

    /** Sum of a project's own manual expenses. */
    fun expensesSum(project: Project): Double = project.expenses.sumOf { it.amount }

    /** The receipts assigned directly to a project id. */
    fun projectReceipts(receipts: List<ReceiptRef>, id: String): List<ReceiptRef> =
        receipts.filter { it.receipt.projectId == id }

    /** A project's own total (manual expenses + directly-assigned receipts, no descendants). */
    fun ownTotal(project: Project, receipts: List<ReceiptRef>): Double {
        val rs = projectReceipts(receipts, project.id).sumOf { receiptAmount(it.receipt, it.tx) }
        return expensesSum(project) + rs
    }

    /** A project's rolled-up total, including every descendant. Rounded to the cent. */
    fun rolledTotal(projects: List<Project>, id: String, receipts: List<ReceiptRef>): Double {
        val self = projects.firstOrNull { it.id == id } ?: return 0.0
        var t = ownTotal(self, receipts)
        for (did in descendantIds(projects, id)) {
            projects.firstOrNull { it.id == did }?.let { t += ownTotal(it, receipts) }
        }
        return round2(t)
    }

    /**
     * Flattened tree for rendering: depth-first, parents before children, alphabetical within a
     * level. Cycle-safe; a project whose parent is missing surfaces at the root so it's never hidden.
     */
    fun projectTree(projects: List<Project>): List<TreeRow> {
        val out = mutableListOf<TreeRow>()
        val seen = HashSet<String>()
        fun walk(parentId: String?, depth: Int) {
            for (p in projectChildren(projects, parentId).sortedBy { it.name.lowercase() }) {
                if (!seen.add(p.id)) continue
                out.add(TreeRow(p, depth)); walk(p.id, depth + 1)
            }
        }
        walk(null, 0)
        for (p in projects) if (seen.add(p.id)) out.add(TreeRow(p, 0))
        return out
    }
}
