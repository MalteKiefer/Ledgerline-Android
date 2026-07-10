package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.Workspace
import de.ledgerline.app.domain.model.WorkspaceManifest

/** A workspace write expressed as a pure manifest mutation (409-merge-safe). */
interface MutateWorkspace {
    suspend fun invoke(mutate: (WorkspaceManifest) -> WorkspaceManifest): Outcome<Workspace>
}
