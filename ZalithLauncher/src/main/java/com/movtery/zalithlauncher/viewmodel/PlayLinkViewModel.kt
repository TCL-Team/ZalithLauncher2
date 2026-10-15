/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.movtery.zalithlauncher.game.playlink.PlayLinkConfig
import com.movtery.zalithlauncher.game.playlink.PlayLinkMetadata
import com.movtery.zalithlauncher.game.playlink.PlayLinkRepository
import com.movtery.zalithlauncher.game.version.export.ExportInfo
import com.movtery.zalithlauncher.game.version.export.PackExporter
import com.movtery.zalithlauncher.game.version.export.PackType
import com.movtery.zalithlauncher.game.version.installed.Version
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MAX_EXPORT_FILES = 20_000

/** State for long-running PlayLink work. */
enum class PlayLinkOperation {
    IDLE,
    PACKAGING,
    UPLOADING,
    RESOLVING,
    DOWNLOADING
}

class PlayLinkViewModel : ViewModel() {
    var operation by mutableStateOf(PlayLinkOperation.IDLE)
        private set

    var activeExporter by mutableStateOf<PackExporter?>(null)
        private set

    var generatedLink by mutableStateOf<String?>(null)
        private set

    var resolvedLink by mutableStateOf<PlayLinkMetadata?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var operationJob: Job? = null

    fun generateLink(
        context: Context,
        version: Version,
        packName: String,
        description: String
    ) {
        if (operation != PlayLinkOperation.IDLE) return
        generatedLink = null
        errorMessage = null
        operation = PlayLinkOperation.PACKAGING

        operationJob = viewModelScope.launch {
            var packageFile: File? = null
            var exportWorkDirectory: File? = null
            try {
                val config = PlayLinkConfig.load(context)
                ensureConfigured(config)
                val versionInfo = version.getVersionInfo()
                    ?: throw IOException("This installed version has no readable Minecraft version details.")
                if (!version.isValid()) throw IOException("The selected game version is no longer available.")

                val safeName = sanitizePackName(packName)
                if (safeName.isBlank()) throw IOException("Enter a name for this PlayLink pack.")
                val safeDescription = description.trim().take(500)
                val outputDirectory = File(context.cacheDir, "playlink/packs")
                if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
                    throw IOException("Unable to create the temporary PlayLink pack folder.")
                }

                val operationId = UUID.randomUUID().toString()
                val packFile = File(outputDirectory, "$operationId.mrpack")
                packageFile = packFile
                val workDirectory = File(context.cacheDir, "playlink/work-$operationId")
                exportWorkDirectory = workDirectory
                val gamePath = version.getGameDir()
                val selectedFiles = withContext(Dispatchers.IO) {
                    selectShareableFiles(gamePath)
                }

                val primaryLoader = versionInfo.primaryLoader
                val exportInfo = ExportInfo(
                    gamePath = gamePath,
                    name = safeName,
                    summary = safeDescription.ifBlank { null },
                    version = "1.0.0",
                    mcVersion = versionInfo.minecraftVersion,
                    loader = primaryLoader?.let { ExportInfo.LoaderVersion(it.loader, it.version) },
                    selectedFiles = selectedFiles,
                    packType = PackType.Modrinth,
                    // Keep local mod files in the pack instead of depending on third-party APIs
                    // that may be unavailable or return a different file for a hash.
                    packModrinth = false,
                    packCurseForge = false
                )
                val exporter = PackExporter(
                    context = context,
                    exportInfo = exportInfo,
                    scope = viewModelScope,
                    workingDirectory = workDirectory
                )
                activeExporter = exporter
                exportPack(exporter, packFile, version)
                activeExporter = null

                operation = PlayLinkOperation.UPLOADING
                val modsRootPrefix = File(gamePath, "mods").absolutePath + File.separator
                val metadata = PlayLinkMetadata(
                    id = "",
                    packName = safeName,
                    description = safeDescription,
                    minecraftVersion = versionInfo.minecraftVersion,
                    loaderName = primaryLoader?.loader?.displayName ?: "Vanilla",
                    loaderVersion = primaryLoader?.version.orEmpty(),
                    modCount = selectedFiles.count { file ->
                        file.absolutePath.startsWith(modsRootPrefix) &&
                            file.extension.equals("jar", ignoreCase = true)
                    },
                    fileSizeBytes = packFile.length(),
                    downloadUrl = ""
                )
                generatedLink = PlayLinkRepository.createLink(
                    context = context,
                    config = config,
                    packFile = packFile,
                    metadata = metadata
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                errorMessage = error.message?.takeIf { it.isNotBlank() }
                    ?: "The PlayLink could not be created. Please try again."
            } finally {
                activeExporter = null
                packageFile?.delete()
                exportWorkDirectory?.deleteRecursively()
                operation = PlayLinkOperation.IDLE
                operationJob = null
            }
        }
    }

    fun resolveLink(context: Context, input: String) {
        if (operation != PlayLinkOperation.IDLE) return
        errorMessage = null
        resolvedLink = null
        operation = PlayLinkOperation.RESOLVING
        operationJob = viewModelScope.launch {
            try {
                val config = PlayLinkConfig.load(context)
                ensureConfigured(config)
                resolvedLink = PlayLinkRepository.resolveLink(config, input)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                errorMessage = error.message?.takeIf { it.isNotBlank() }
                    ?: "This PlayLink could not be loaded. Check the link and try again."
            } finally {
                operation = PlayLinkOperation.IDLE
                operationJob = null
            }
        }
    }

    fun downloadAndImport(
        context: Context,
        metadata: PlayLinkMetadata,
        importerViewModel: ModpackImportViewModel,
        eventViewModel: EventViewModel
    ) {
        if (operation != PlayLinkOperation.IDLE) return
        if (importerViewModel.importOperation != ModpackImportOperation.None) {
            errorMessage = "Another modpack import is already running."
            return
        }
        errorMessage = null
        operation = PlayLinkOperation.DOWNLOADING
        operationJob = viewModelScope.launch {
            var packFile: File? = null
            var handedToImporter = false
            try {
                val config = PlayLinkConfig.load(context)
                ensureConfigured(config)
                val downloadedPack = PlayLinkRepository.downloadPack(context, metadata)
                packFile = downloadedPack
                val fileForImport = downloadedPack
                importerViewModel.import(
                    context = context,
                    uri = Uri.fromFile(fileForImport),
                    onStart = { eventViewModel.sendKeepScreen(true) },
                    onStop = {
                        eventViewModel.sendKeepScreen(false)
                        fileForImport.delete()
                    }
                )
                handedToImporter = importerViewModel.importOperation != ModpackImportOperation.None
                if (!handedToImporter) {
                    throw IOException("The existing modpack importer could not start.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                errorMessage = error.message?.takeIf { it.isNotBlank() }
                    ?: "The PlayLink pack could not be downloaded. Please try again."
            } finally {
                if (!handedToImporter) packFile?.delete()
                operation = PlayLinkOperation.IDLE
                operationJob = null
            }
        }
    }

    fun cancelPackExport() {
        if (operation != PlayLinkOperation.PACKAGING) return
        operationJob?.cancel()
        activeExporter?.cancel()
    }

    fun clearError() {
        errorMessage = null
    }

    fun clearGeneratedLink() {
        generatedLink = null
        errorMessage = null
    }

    fun clearResolvedLink() {
        resolvedLink = null
        errorMessage = null
    }

    private suspend fun exportPack(
        exporter: PackExporter,
        outputFile: File,
        version: Version
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        continuation.invokeOnCancellation { exporter.cancel() }
        exporter.startExportToFile(
            outputFile = outputFile,
            version = version,
            onFinished = {
                if (continuation.isActive) continuation.resume(Unit)
            },
            onError = { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            },
            onCancelled = {
                if (continuation.isActive) continuation.cancel()
            }
        )
    }

    private fun ensureConfigured(config: PlayLinkConfig) {
        if (!config.isConfigured) {
            throw IOException(
                "Cloud links are not configured. Set the Firebase project ID, Web API key, and Storage bucket in ZalithLauncher/src/main/assets/playlink-config.json."
            )
        }
    }

    /** Only portable, non-personal game folders are included by default. */
    private fun selectShareableFiles(gamePath: File): List<File> {
        if (!gamePath.isDirectory) throw IOException("The selected game folder is unavailable.")
        val includedRoots = listOf(
            "mods",
            "config",
            "defaultconfigs",
            "resourcepacks",
            "shaderpacks",
            "kubejs",
            "scripts"
        )
        val excludedNames = setOf(
            "servers.dat",
            "servers.dat_old",
            "options.txt",
            "usercache.json",
            "launcher_profiles.json",
            "launcher_accounts.json",
            "accounts.json",
            "realms_persistence.json"
        )
        val sensitiveName = Regex("(?i).*(token|secret|password|credential|account|session).*", RegexOption.IGNORE_CASE)
        val files = mutableListOf<File>()
        includedRoots.forEach { rootName ->
            val root = File(gamePath, rootName)
            if (!root.isDirectory) return@forEach
            root.walkTopDown()
                .maxDepth(32)
                .filter { file ->
                    file.isFile &&
                        file.name !in excludedNames &&
                        !sensitiveName.matches(file.name) &&
                        file.name != ".DS_Store" &&
                        file.length() > 0L
                }
                .forEach { file ->
                    if (files.size >= MAX_EXPORT_FILES) {
                        throw IOException("This profile contains too many files to package safely.")
                    }
                    files += file
                }
        }
        return files
    }

    private fun sanitizePackName(value: String): String = value
        .trim()
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .replace(Regex("\\s+"), " ")
        .take(64)
}
