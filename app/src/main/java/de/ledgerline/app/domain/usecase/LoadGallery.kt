package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.Gallery

interface LoadGallery { suspend fun invoke(): Outcome<Gallery> }
