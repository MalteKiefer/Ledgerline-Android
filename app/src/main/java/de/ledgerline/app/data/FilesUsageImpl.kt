package de.ledgerline.app.data

import de.ledgerline.app.domain.usecase.FilesUsage
import javax.inject.Inject

/** Delegates to [WorkspaceRepository.filesUsage]. */
class FilesUsageImpl @Inject constructor(private val repo: WorkspaceRepository) : FilesUsage {
    override suspend fun invoke(): Pair<Long, Long>? = repo.filesUsage()
}
