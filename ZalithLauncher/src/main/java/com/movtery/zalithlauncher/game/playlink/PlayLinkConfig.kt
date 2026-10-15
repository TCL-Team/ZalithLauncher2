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
import org.json.JSONObject

/** Public Firebase client configuration. The API key is not a server secret. */
data class PlayLinkConfig(
    val projectId: String,
    val apiKey: String,
    val storageBucket: String,
    val region: String
) {
    val isConfigured: Boolean
        get() = projectId.matches(PROJECT_ID_PATTERN) &&
            apiKey.isNotBlank() &&
            storageBucket.matches(STORAGE_BUCKET_PATTERN) &&
            region.matches(REGION_PATTERN)

    val hostingBaseUrl: String
        get() = "https://$projectId.web.app"

    val functionsBaseUrl: String
        get() = "https://$region-$projectId.cloudfunctions.net/playLinkApi"

    companion object {
        const val ASSET_FILE = "playlink-config.json"
        private val PROJECT_ID_PATTERN = Regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$")
        private val STORAGE_BUCKET_PATTERN = Regex("^[a-z0-9][a-z0-9._-]*$")
        private val REGION_PATTERN = Regex("^[a-z]+-[a-z]+[0-9]$")

        fun load(context: Context): PlayLinkConfig = runCatching {
            val json = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            val values = JSONObject(json)
            PlayLinkConfig(
                projectId = values.optString("projectId").trim(),
                apiKey = values.optString("apiKey").trim(),
                storageBucket = values.optString("storageBucket").trim(),
                region = values.optString("region", "us-central1").trim().ifBlank { "us-central1" }
            )
        }.getOrDefault(
            PlayLinkConfig(
                projectId = "",
                apiKey = "",
                storageBucket = "",
                region = "us-central1"
            )
        )
    }
}
