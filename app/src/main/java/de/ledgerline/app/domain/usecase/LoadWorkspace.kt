package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.Workspace

/** Abstraction the tab ViewModels depend on, so they can be unit-tested with a fake. */
interface LoadWorkspace {
    suspend fun invoke(): Outcome<Workspace>
}
