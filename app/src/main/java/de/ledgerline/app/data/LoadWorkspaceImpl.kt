package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.Workspace
import de.ledgerline.app.domain.usecase.LoadWorkspace
import javax.inject.Inject

class LoadWorkspaceImpl @Inject constructor(
    private val repository: WorkspaceRepository,
    private val cache: WorkspaceCache,
) : LoadWorkspace {
    override suspend fun invoke(): Outcome<Workspace> {
        val result = repository.load()
        if (result is Outcome.Ok) cache.set(result.value)
        return result
    }
}
