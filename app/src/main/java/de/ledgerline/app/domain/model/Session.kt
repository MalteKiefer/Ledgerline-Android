package de.ledgerline.app.domain.model

data class Session(val baseUrl: String, val token: String, val spkiPin: String, val userName: String?)
