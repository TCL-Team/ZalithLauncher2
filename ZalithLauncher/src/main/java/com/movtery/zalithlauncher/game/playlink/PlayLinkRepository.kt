/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.game.playlink

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MAX_PLAYLINK_PACK_BYTES = 512L * 1024L * 1024L
private const val PLAYLINK_AUTH_PREFS = "playlink-anonymous-auth"
private const val AUTH_ID_TOKEN = "id_token"
private const val AUTH_REFRESH_TOKEN = "refresh_token"
private const val AUTH_EXPIRES_AT = "expires_at"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()

private val playLinkHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(30, TimeUnit.MINUTES)
        .build()
}

data class PlayLinkMetadata(
    val id: String,
    val packName: String,
    val description: String,
    val minecraftVersion: String,
    val loaderName: String,
    val loaderVersion: String,
    val modCount: Int,
    val fileSizeBytes: Long,
    val downloadUrl: String
)

private object PlayLinkAnonymousAuth {
    private val mutex = Mutex()

    suspend fun getIdToken(context: Context, config: PlayLinkConfig): String = mutex.withLock {
        if (!config.isConfigured) throw IOException("PlayLink cloud configuration is missing.")
        val prefs = context.getSharedPreferences(PLAYLINK_AUTH_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val token = prefs.getString(AUTH_ID_TOKEN, null)
        if (!token.isNullOrBlank() && prefs.getLong(AUTH_EXPIRES_AT, 0L) > now + 60_000L) {
            return@withLock token
        }

        val refreshToken = prefs.getString(AUTH_REFRESH_TOKEN, null)
        if (!refreshToken.isNullOrBlank()) {
            val refreshed = try {
                refresh(config, refreshToken)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (refreshed != null) {
                save(prefs, refreshed)
                return@withLock refreshed.idToken
            }
        }

        val created = signInAnonymously(config)
        save(prefs, created)
        created.idToken
    }

    private suspend fun signInAnonymously(config: PlayLinkConfig): AuthTokens {
        val url = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${encode(config.apiKey)}"
        val request = Request.Builder()
            .url(url)
            .post(JSONObject().put("returnSecureToken", true).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return parseTokens(awaitJson(request))
    }

    private suspend fun refresh(config: PlayLinkConfig, refreshToken: String): AuthTokens {
        val body = "grant_type=refresh_token&refresh_token=${encode(refreshToken)}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder()
            .url("https://securetoken.googleapis.com/v1/token?key=${encode(config.apiKey)}")
            .post(body)
            .build()
        return parseTokens(awaitJson(request))
    }

    private fun parseTokens(json: JSONObject): AuthTokens {
        val idToken = json.optString("idToken").ifBlank { json.optString("id_token") }
        val refreshToken = json.optString("refreshToken").ifBlank { json.optString("refresh_token") }
        val expiresIn = json.optString("expiresIn").ifBlank { json.optString("expires_in") }.toLongOrNull()
            ?: throw IOException("Firebase Auth returned an invalid token lifetime.")
        if (idToken.isBlank() || refreshToken.isBlank()) {
            throw IOException("Firebase Auth did not return an anonymous session token.")
        }
        return AuthTokens(
            idToken = idToken,
            refreshToken = refreshToken,
            expiresAt = System.currentTimeMillis() + expiresIn * 1_000L
        )
    }

    private fun save(
        prefs: android.content.SharedPreferences,
        tokens: AuthTokens
    ) {
        prefs.edit()
            .putString(AUTH_ID_TOKEN, tokens.idToken)
            .putString(AUTH_REFRESH_TOKEN, tokens.refreshToken)
            .putLong(AUTH_EXPIRES_AT, tokens.expiresAt)
            .apply()
    }

    private data class AuthTokens(
        val idToken: String,
        val refreshToken: String,
        val expiresAt: Long
    )
}

object PlayLinkRepository {
    /** Creates a random cloud record only after the modpack file has been uploaded. */
    suspend fun createLink(
        context: Context,
        config: PlayLinkConfig,
        packFile: File,
        metadata: PlayLinkMetadata
    ): String {
        if (!config.isConfigured) throw IOException("PlayLink cloud configuration is missing.")
        if (!packFile.isFile || packFile.length() <= 0L || packFile.length() > MAX_PLAYLINK_PACK_BYTES) {
            throw IOException("The exported pack is empty or exceeds the 512 MB PlayLink limit.")
        }

        val idToken = PlayLinkAnonymousAuth.getIdToken(context, config)
        val ticket = postJson(
            url = "${config.functionsBaseUrl}/playlink/upload-ticket",
            body = JSONObject()
                .put("packName", metadata.packName)
                .put("description", metadata.description)
                .put("minecraftVersion", metadata.minecraftVersion)
                .put("loaderName", metadata.loaderName)
                .put("loaderVersion", metadata.loaderVersion)
                .put("modCount", metadata.modCount)
                .put("fileSizeBytes", packFile.length()),
            idToken = idToken
        )
        val id = ticket.optString("id").takeIf { it.isValidId() }
            ?: throw IOException("The PlayLink service returned an invalid link ID.")
        val objectPath = ticket.optString("objectPath").ifBlank { "playlinks/$id/pack.mrpack" }

        uploadPack(
            config = config,
            idToken = idToken,
            objectPath = objectPath,
            file = packFile
        )

        val finalized = postJson(
            url = "${config.functionsBaseUrl}/playlink/finalize",
            body = JSONObject().put("id", id),
            idToken = idToken
        )
        return finalized.optString("shareUrl").ifBlank { "${config.hostingBaseUrl}/play/$id" }
    }

    suspend fun resolveLink(
        config: PlayLinkConfig,
        input: String
    ): PlayLinkMetadata {
        if (!config.isConfigured) throw IOException("PlayLink cloud configuration is missing.")
        val id = parseId(config, input)
        val json = getJson("${config.functionsBaseUrl}/playlink/$id")
        val downloadUrl = json.optString("downloadUrl")
        if (!downloadUrl.isSafeStorageUrl()) {
            throw IOException("The PlayLink service returned an unsafe download address.")
        }
        return PlayLinkMetadata(
            id = json.optString("id", id),
            packName = json.optString("packName").ifBlank { "Minecraft pack" },
            description = json.optString("description"),
            minecraftVersion = json.optString("minecraftVersion").ifBlank { "Unknown" },
            loaderName = json.optString("loaderName").ifBlank { "Vanilla" },
            loaderVersion = json.optString("loaderVersion"),
            modCount = json.optInt("modCount", 0).coerceAtLeast(0),
            fileSizeBytes = json.optLong("fileSizeBytes", 0L).coerceAtLeast(0L),
            downloadUrl = downloadUrl
        )
    }

    suspend fun downloadPack(
        context: Context,
        metadata: PlayLinkMetadata
    ): File {
        if (!metadata.downloadUrl.isSafeStorageUrl()) {
            throw IOException("The PlayLink service returned an unsafe download address.")
        }
        val destinationDir = File(context.cacheDir, "playlink/imports")
        if (!destinationDir.exists() && !destinationDir.mkdirs()) {
            throw IOException("Unable to create the PlayLink download cache.")
        }
        val destination = File(destinationDir, "${metadata.id}.mrpack")
        destination.delete()

        val request = Request.Builder().url(metadata.downloadUrl).get().build()
        val response = awaitResponse(request)
        try {
            if (!response.isSuccessful) {
                throw IOException("Pack download failed (HTTP ${response.code}).")
            }
            val body = response.body ?: throw IOException("The PlayLink pack download was empty.")
            val responseLength = body.contentLength()
            if (responseLength > MAX_PLAYLINK_PACK_BYTES) {
                throw IOException("The PlayLink pack exceeds the 512 MB download limit.")
            }

            withContext(Dispatchers.IO) {
                body.byteStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            if (copied > MAX_PLAYLINK_PACK_BYTES) {
                                throw IOException("The PlayLink pack exceeds the 512 MB download limit.")
                            }
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
            }
            if (destination.length() <= 0L) throw IOException("The PlayLink pack download was empty.")
            return destination
        } catch (error: Throwable) {
            destination.delete()
            throw error
        } finally {
            response.close()
        }
    }

    private suspend fun uploadPack(
        config: PlayLinkConfig,
        idToken: String,
        objectPath: String,
        file: File
    ) {
        val bucket = encode(config.storageBucket)
        val name = encode(objectPath)
        val startRequest = Request.Builder()
            .url("https://firebasestorage.googleapis.com/v0/b/$bucket/o?uploadType=resumable&name=$name")
            .header("Authorization", "Firebase $idToken")
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", BINARY_MEDIA_TYPE.toString())
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val uploadUrl = awaitResponse(startRequest).use { response ->
            if (!response.isSuccessful) {
                throw IOException("Could not start the PlayLink pack upload (HTTP ${response.code}).")
            }
            response.header("X-Goog-Upload-URL") ?: response.header("Location")
                ?: throw IOException("Firebase Storage did not return an upload session.")
        }
        if (!uploadUrl.isSafeStorageUrl()) throw IOException("Firebase Storage returned an unsafe upload address.")

        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Command", "upload, finalize")
            .header("X-Goog-Upload-Offset", "0")
            .put(file.asRequestBody(BINARY_MEDIA_TYPE))
            .build()
        awaitResponse(uploadRequest).use { response ->
            if (!response.isSuccessful) {
                throw IOException("PlayLink pack upload failed (HTTP ${response.code}).")
            }
        }
    }

    private suspend fun postJson(
        url: String,
        body: JSONObject,
        idToken: String
    ): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $idToken")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return awaitJson(request)
    }

    private suspend fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).get().build()
        return awaitJson(request)
    }

    private suspend fun awaitJson(request: Request): JSONObject {
        awaitResponse(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    val error = JSONObject(text).opt("error")
                    if (error is JSONObject) error.optString("message") else error?.toString()
                }.getOrNull().orEmpty()
                throw IOException(message.ifBlank { "PlayLink request failed (HTTP ${response.code})." })
            }
            return runCatching { JSONObject(text) }
                .getOrElse { throw IOException("PlayLink service returned an invalid response.", it) }
        }
    }

    private fun parseId(config: PlayLinkConfig, input: String): String {
        val value = input.trim()
        if (value.isValidId()) return value

        val uri = runCatching { Uri.parse(value) }.getOrNull()
            ?: throw IOException("Enter a valid PlayLink URL or code.")
        val allowedHosts = setOf("${config.projectId}.web.app", "${config.projectId}.firebaseapp.com")
        if (uri.scheme != "https" || uri.host !in allowedHosts) {
            throw IOException("This URL is not a PlayLink for the configured cloud project.")
        }
        val segments = uri.pathSegments
        val id = if (segments.size >= 2 && segments[0] == "play") segments[1] else ""
        if (!id.isValidId()) throw IOException("The PlayLink URL does not contain a valid link code.")
        return id
    }

    private fun String.isValidId(): Boolean = matches(Regex("^[A-Za-z0-9_-]{32}$"))

    private fun String.isSafeStorageUrl(): Boolean = runCatching {
        val uri = Uri.parse(this)
        uri.scheme == "https" && uri.host != null &&
            (uri.host == "storage.googleapis.com" || uri.host!!.endsWith(".googleapis.com"))
    }.getOrDefault(false)

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private suspend fun awaitResponse(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = playLinkHttpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    continuation.resume(response)
                } catch (_: IllegalStateException) {
                    response.close()
                }
            }
        })
    }
}
