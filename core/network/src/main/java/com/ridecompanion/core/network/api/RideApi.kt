package com.ridecompanion.core.network.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

data class HealthResponse(
    val status: String,
    val sessions: Int
)

data class CreateSessionRequest(
    val name: String,
    val riderName: String
)

data class JoinSessionRequest(
    val sessionId: String,
    val riderName: String
)

data class UpdateDestinationRequest(
    val destinationName: String,
    val latitude: Double,
    val longitude: Double,
    val gpxPathJson: String?
)

data class SessionResponse(
    val sessionId: String,
    val name: String,
    val token: String, // LiveKit connecting token
    val liveKitUrl: String, // LiveKit signaling server URL
    val websocketUrl: String // Backend signaling websocket
)

interface RideApi {

    @POST("api/session/create")
    suspend fun createSession(
        @Body request: CreateSessionRequest
    ): SessionResponse

    @POST("api/session/join")
    suspend fun joinSession(
        @Body request: JoinSessionRequest
    ): SessionResponse

    @POST("api/session/{sessionId}/destination")
    suspend fun updateDestination(
        @Path("sessionId") sessionId: String,
        @Body request: UpdateDestinationRequest
    )

    @GET("/")
    suspend fun checkHealth(): HealthResponse
}
