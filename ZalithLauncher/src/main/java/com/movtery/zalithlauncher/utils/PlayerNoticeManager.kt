/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.utils

import com.movtery.zalithlauncher.path.GLOBAL_CLIENT
import com.movtery.zalithlauncher.path.URL_PLAYER_NOTICE
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.enums.AppLanguage
import com.movtery.zalithlauncher.setting.launcherMMKV
import com.movtery.zalithlauncher.utils.logging.Logger
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.Locale

object PlayerNoticeManager {
    private const val MMKV_KEY = "player_notice_dismissed_hash"
    private const val TAG = "PlayerNoticeManager"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchNotice(): String {
        return try {
            val response = GLOBAL_CLIENT.get(URL_PLAYER_NOTICE)
            val body = response.bodyAsText().trim()
            if (body.isEmpty()) return ""

            runCatching {
                val root = json.parseToJsonElement(body).jsonObject
                val title = localizedValue(root["title"]?.jsonObject.orEmpty())
                val content = localizedValue(root["content"]?.jsonObject.orEmpty())
                when {
                    title.isNotEmpty() && content.isNotEmpty() -> "$title\n\n$content"
                    content.isNotEmpty() -> content
                    else -> body
                }
            }.getOrElse { body }
        } catch (e: Exception) {
            Logger.warning(TAG, "Failed to fetch launcher notice", e)
            ""
        }
    }

    private fun localizedValue(values: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val language = AllSettings.launcherLanguage.getValue()
        val localeTag = if (language == AppLanguage.FOLLOW_SYSTEM) {
            Locale.getDefault().toLanguageTag()
        } else {
            language.tag
        }
        val key = when (localeTag.lowercase(Locale.ROOT)) {
            "zh-cn", "zh-hans" -> "zh_cn"
            "zh-tw", "zh-hant" -> "zh_tw"
            "pt-br" -> "pt_br"
            else -> localeTag.substringBefore('-').lowercase(Locale.ROOT)
        }
        val candidateKeys = listOf(key, "${key}_us", "en_us", "en", "zh_cn")
        return candidateKeys.firstNotNullOfOrNull { candidate ->
            values[candidate]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }.orEmpty()
    }

    private fun hash(content: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun isDismissed(content: String): Boolean {
        if (content.isEmpty()) return true
        val saved = launcherMMKV().decodeString(MMKV_KEY) ?: return false
        return saved == hash(content)
    }

    fun dismiss(content: String) {
        launcherMMKV().encode(MMKV_KEY, hash(content))
    }
}
