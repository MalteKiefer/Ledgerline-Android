package de.ledgerline.app.core.finance

import de.ledgerline.app.domain.model.Project
import de.ledgerline.app.domain.model.ProjectExpense
import de.ledgerline.app.domain.model.Receipt
import de.ledgerline.app.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test

class FinanceProjectsTest {

    private fun proj(id: String, name: String, parent: String? = null, exp: List<ProjectExpense> = emptyList()) =
        Project(id = id, name = name, parentId = parent, expenses = exp)

    private val projects = listOf(
        proj("root", "Bau", exp = listOf(ProjectExpense("e1", 100.0))),
        proj("a", "Alpha", parent = "root", exp = listOf(ProjectExpense("e2", 50.0))),
        proj("b", "Beta", parent = "root"),
        proj("a1", "Alpha-1", parent = "a", exp = listOf(ProjectExpense("e3", 25.0))),
    )

    // A receipt attached to a transaction, assigned to project "a".
    private val receipts = listOf(
        FinanceProjects.ReceiptRef(
            Receipt(id = "r1", total = 30.0, projectId = "a"),
            Transaction(id = "t1", amount = -30.0),
        ),
        FinanceProjects.ReceiptRef(
            Receipt(id = "r2", total = null, projectId = "b"),      // no total → falls back to |tx.amount|
            Transaction(id = "t2", amount = -12.5),
        ),
    )

    @Test fun children_and_descendants() {
        assertEquals(setOf("a", "b"), FinanceProjects.projectChildren(projects, "root").map { it.id }.toSet())
        assertEquals(setOf("a", "b", "a1"), FinanceProjects.descendantIds(projects, "root").toSet())
        assertEquals(listOf("a1"), FinanceProjects.descendantIds(projects, "a"))
    }

    @Test fun own_and_rolled_totals() {
        // own(a) = expense 50 + receipt r1 30 = 80
        assertEquals(80.0, FinanceProjects.ownTotal(projects.first { it.id == "a" }, receipts), 0.001)
        // own(a1) = 25 ; own(b) = 0 + receipt r2 fallback 12.5
        assertEquals(12.5, FinanceProjects.ownTotal(projects.first { it.id == "b" }, receipts), 0.001)
        // rolled(root) = own(root 100) + own(a 80) + own(b 12.5) + own(a1 25) = 217.5
        assertEquals(217.5, FinanceProjects.rolledTotal(projects, "root", receipts), 0.001)
        // rolled(a) = own(a 80) + own(a1 25) = 105
        assertEquals(105.0, FinanceProjects.rolledTotal(projects, "a", receipts), 0.001)
    }

    @Test fun tree_is_depth_first_alphabetical() {
        val rows = FinanceProjects.projectTree(projects)
        assertEquals(listOf("Bau", "Alpha", "Alpha-1", "Beta"), rows.map { it.project.name })
        assertEquals(listOf(0, 1, 2, 1), rows.map { it.depth })
    }

    @Test fun effective_kind_derives_from_root() {
        val ps = listOf(
            Project(id = "root", name = "R", kind = "private"),
            Project(id = "a", name = "A", parentId = "root", kind = "business"),  // stale/legacy kind
            Project(id = "a1", name = "A1", parentId = "a", kind = "business"),
            Project(id = "biz", name = "B", kind = "business"),
        )
        assertEquals("private", FinanceProjects.effectiveKind(ps, "root"))
        assertEquals("private", FinanceProjects.effectiveKind(ps, "a"))    // inherits root, not its own
        assertEquals("private", FinanceProjects.effectiveKind(ps, "a1"))
        assertEquals("business", FinanceProjects.effectiveKind(ps, "biz"))
        // normalizeKinds rewrites the stale sub-project kinds to the root's.
        val fixed = FinanceProjects.normalizeKinds(ps).associate { it.id to it.kind }
        assertEquals("private", fixed["a"])
        assertEquals("private", fixed["a1"])
        assertEquals("business", fixed["biz"])
    }

    @Test fun orphan_surfaces_at_root() {
        val orphan = projects + proj("x", "Orphan", parent = "missing")
        val rows = FinanceProjects.projectTree(orphan)
        assertEquals(1, rows.count { it.project.id == "x" && it.depth == 0 })
    }
}
