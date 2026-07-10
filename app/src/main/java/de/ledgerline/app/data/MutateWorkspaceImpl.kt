package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.Workspace
import de.ledgerline.app.domain.model.WorkspaceManifest
import de.ledgerline.app.domain.usecase.MutateWorkspace
import javax.inject.Inject

class MutateWorkspaceImpl @Inject constructor(
    private val repo: WorkspaceRepository,
) : MutateWorkspace {
    override suspend fun invoke(mutate: (WorkspaceManifest) -> WorkspaceManifest): Outcome<Workspace> =
        repo.save(mutate)
}
