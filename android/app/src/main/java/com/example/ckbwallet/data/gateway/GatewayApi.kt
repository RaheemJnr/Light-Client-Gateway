package com.example.ckbwallet.data.gateway

import android.util.Log
import com.example.ckbwallet.BuildConfig
import com.example.ckbwallet.data.gateway.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GatewayApi"

@Singleton
class GatewayApi @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }

    private val baseUrl: String = BuildConfig.GATEWAY_URL

    suspend fun getStatus(): Result<StatusResponse> = runCatching {
        client.get("$baseUrl/v1/status").body()
    }

    suspend fun registerAccount(request: RegisterAccountRequest): Result<RegisterResponse> = runCatching {
        Log.d(TAG, "Registering account: address=${request.address}")

        val response = client.post("$baseUrl/v1/accounts/register") {
            setBody(request)
        }

        val responseText = response.bodyAsText()
        Log.d(TAG, "Register response status: ${response.status}")
        Log.d(TAG, "Register response body: $responseText")

        if (!response.status.isSuccess()) {
            // Try to parse error response
            try {
                val errorResponse = json.decodeFromString<ApiError>(responseText)
                throw Exception("Registration failed: ${errorResponse.error.code} - ${errorResponse.error.message}")
            } catch (e: Exception) {
                if (e.message?.startsWith("Registration failed:") == true) throw e
                throw Exception("Registration failed: ${response.status} - $responseText")
            }
        }

        // Parse successful response
        json.decodeFromString<RegisterResponse>(responseText)
    }

    suspend fun getAccountStatus(address: String): Result<AccountStatusResponse> = runCatching {
        Log.d(TAG, "Getting account status for: $address")
        val response = client.get("$baseUrl/v1/accounts/$address/status")
        val responseText = response.bodyAsText()
        Log.d(TAG, "Account status response: $responseText")

        if (!response.status.isSuccess()) {
            throw Exception("Failed to get account status: ${response.status}")
        }

        json.decodeFromString<AccountStatusResponse>(responseText)
    }

    suspend fun getBalance(address: String): Result<BalanceResponse> = runCatching {
        Log.d(TAG, "Getting balance for: $address")
        val response = client.get("$baseUrl/v1/accounts/$address/balance")
        val responseText = response.bodyAsText()
        Log.d(TAG, "Balance response: $responseText")

        if (!response.status.isSuccess()) {
            Log.e(TAG, "Get balance error: $responseText")
            throw Exception("Failed to get balance: ${response.status}")
        }

        json.decodeFromString<BalanceResponse>(responseText)
    }

    suspend fun getCells(
        address: String,
        limit: Int = 20,
        cursor: String? = null
    ): Result<CellsResponse> = runCatching {
        Log.d(TAG, "Getting cells for: $address, limit=$limit")
        val response = client.get("$baseUrl/v1/accounts/$address/cells") {
            parameter("limit", limit)
            cursor?.let { parameter("cursor", it) }
        }
        val responseText = response.bodyAsText()

        if (!response.status.isSuccess()) {
            Log.e(TAG, "Get cells error: $responseText")
            throw Exception("Failed to get cells: ${response.status}")
        }

        json.decodeFromString<CellsResponse>(responseText)
    }

    suspend fun getTransactions(
        address: String,
        limit: Int = 20,
        cursor: String? = null
    ): Result<TransactionsResponse> = runCatching {
        Log.d(TAG, "Getting transactions for: $address, limit=$limit")
        val response = client.get("$baseUrl/v1/accounts/$address/transactions") {
            parameter("limit", limit)
            cursor?.let { parameter("cursor", it) }
        }
        val responseText = response.bodyAsText()

        if (!response.status.isSuccess()) {
            Log.e(TAG, "Get transactions error: $responseText")
            throw Exception("Failed to get transactions: ${response.status}")
        }

        json.decodeFromString<TransactionsResponse>(responseText)
    }

    suspend fun sendTransaction(request: SendTransactionRequest): Result<SendTransactionResponse> = runCatching {
        Log.d(TAG, "Sending transaction")
        val response = client.post("$baseUrl/v1/transactions/send") {
            setBody(request)
        }
        val responseText = response.bodyAsText()
        Log.d(TAG, "Send transaction response: $responseText")

        if (!response.status.isSuccess()) {
            Log.e(TAG, "Send transaction error: $responseText")
            throw Exception("Failed to send transaction: ${response.status} - $responseText")
        }

        json.decodeFromString<SendTransactionResponse>(responseText)
    }

    suspend fun getTransactionStatus(txHash: String): Result<TransactionStatusResponse> = runCatching {
        Log.d(TAG, "Getting transaction status for: $txHash")
        val response = client.get("$baseUrl/v1/transactions/$txHash/status")
        val responseText = response.bodyAsText()
        Log.d(TAG, "Transaction status response: $responseText")

        if (!response.status.isSuccess()) {
            Log.e(TAG, "Get transaction status error: $responseText")
            throw Exception("Failed to get transaction status: ${response.status}")
        }

        json.decodeFromString<TransactionStatusResponse>(responseText)
    }
}
