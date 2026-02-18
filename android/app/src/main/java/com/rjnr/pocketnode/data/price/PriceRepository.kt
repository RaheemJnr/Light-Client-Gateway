package com.rjnr.pocketnode.data.price

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the CKB/USD spot price from CoinGecko's free public API (no API key required).
 *
 * Endpoint: GET https://api.coingecko.com/api/v3/simple/price
 * Response: {"nervos-network":{"usd":0.01234}}
 */
@Singleton
class PriceRepository @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json
) {
    suspend fun getCkbUsdPrice(): Result<Double> = runCatching {
        val response = httpClient.get("https://api.coingecko.com/api/v3/simple/price") {
            parameter("ids", "nervos-network")
            parameter("vs_currencies", "usd")
        }
        val body = response.bodyAsText()
        val data = json.decodeFromString<Map<String, Map<String, Double>>>(body)
        data["nervos-network"]?.get("usd")
            ?: throw Exception("CKB price not found in CoinGecko response")
    }
}
