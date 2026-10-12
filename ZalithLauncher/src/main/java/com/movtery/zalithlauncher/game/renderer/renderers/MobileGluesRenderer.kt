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

package com.movtery.zalithlauncher.game.renderer.renderers

import com.movtery.zalithlauncher.game.renderer.RendererInterface

/**
 * MobileGlues binaries are fetched from the upstream stable release APK during the Android build.
 */
object MobileGluesRenderer : RendererInterface {
    /** Keep the plugin package id so existing MobileGlues selections remain valid. */
    override fun getRendererId(): String = "opengles3"

    override fun getUniqueIdentifier(): String = "com.fcl.plugin.mobileglues"

    override fun getRendererName(): String = "MobileGlues"

    override fun getMinMCVersion(): String = "1.17"

    // MobileGlues 2.0.0 has a known shader compiler failure with the 26.3 RenderPearl pipeline.
    // Keep OpenGL launches on the last confirmed compatible release while allowing a native
    // Vulkan backend to bypass this renderer limit for that version.
    override fun getMaxMCVersion(): String = "26.2"

    // Keep the current support baseline visible in the renderer picker.
    override fun getDisplayMinMCVersion(): String = "26.2"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        mapOf(
            "LIBGL_ES" to "3",
            "LIBGL_EGL" to "libmobileglues.so",
            "MG_COUNT_LAUNCH" to "1"
        )
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libmobileglues.so"

    override fun getRendererEGL(): String = "libmobileglues.so"
}
