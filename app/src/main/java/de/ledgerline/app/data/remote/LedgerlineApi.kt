package de.ledgerline.app.data.remote

import de.ledgerline.app.data.remote.dto.PairClaimRequest
import de.ledgerline.app.data.remote.dto.PairClaimResponse
import de.ledgerline.app.data.remote.dto.PairPollResponse
import de.ledgerline.app.data.remote.dto.StoreResponse
import de.ledgerline.app.data.remote.dto.VaultResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface LedgerlineApi {
    @POST("api/v1/auth/pair")
    suspend fun claimPair(@Body body: PairClaimRequest): Response<PairClaimResponse>

    @GET("api/v1/auth/pair")
    suspend fun pollPair(@Query("code") code: String): Response<PairPollResponse>

    @GET("api/v1/vault")
    suspend fun vault(): Response<VaultResponse>

    @GET("api/v1/store")
    suspend fun store(): Response<StoreResponse>
}
