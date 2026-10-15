/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ui.screens.content

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.playlink.PlayLinkConfig
import com.movtery.zalithlauncher.game.playlink.PlayLinkMetadata
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.ui.screens.content.elements.TitleTaskFlowDialog
import com.movtery.zalithlauncher.utils.file.formatFileSize
import com.movtery.zalithlauncher.viewmodel.EventViewModel
import com.movtery.zalithlauncher.viewmodel.ModpackImportViewModel
import com.movtery.zalithlauncher.viewmodel.PlayLinkOperation
import com.movtery.zalithlauncher.viewmodel.PlayLinkViewModel
import com.movtery.zalithlauncher.viewmodel.ScreenBackStackViewModel

private enum class PlayLinkPage {
    HOME,
    ENTER,
    GENERATE
}

@Composable
fun PlayLinkScreen(
    backStackViewModel: ScreenBackStackViewModel,
    modpackImportViewModel: ModpackImportViewModel,
    eventViewModel: EventViewModel
) {
    var page by remember { mutableStateOf(PlayLinkPage.HOME) }
    val playLinkViewModel: PlayLinkViewModel = viewModel(key = "PlayLink")

    BusyOperation(
        viewModel = playLinkViewModel
    )

    BaseScreen(
        screenKey = NormalNavKey.PlayLink,
        currentKey = backStackViewModel.mainScreen.currentKey
    ) {
        when (page) {
            PlayLinkPage.HOME -> PlayLinkHome(
                onEnter = {
                    playLinkViewModel.clearResolvedLink()
                    page = PlayLinkPage.ENTER
                },
                onGenerate = {
                    playLinkViewModel.clearGeneratedLink()
                    page = PlayLinkPage.GENERATE
                }
            )
            PlayLinkPage.ENTER -> EnterPlayLinkPage(
                viewModel = playLinkViewModel,
                importerViewModel = modpackImportViewModel,
                eventViewModel = eventViewModel,
                onBack = { page = PlayLinkPage.HOME }
            )
            PlayLinkPage.GENERATE -> GeneratePlayLinkPage(
                viewModel = playLinkViewModel,
                onBack = { page = PlayLinkPage.HOME }
            )
        }
    }
}

@Composable
private fun PlayLinkHome(
    onEnter: () -> Unit,
    onGenerate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.playlink_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.playlink_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PlayLinkActionCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.playlink_open_button),
                description = stringResource(R.string.playlink_open_description),
                iconRes = R.drawable.ic_link,
                onClick = onEnter
            )
            PlayLinkActionCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.playlink_create_button),
                description = stringResource(R.string.playlink_create_description),
                iconRes = R.drawable.ic_share_filled,
                onClick = onGenerate
            )
        }
    }
}

@Composable
private fun PlayLinkActionCard(
    modifier: Modifier,
    title: String,
    description: String,
    iconRes: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    modifier = Modifier.size(32.dp),
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
                Text(title)
            }
        }
    }
}

@Composable
private fun EnterPlayLinkPage(
    viewModel: PlayLinkViewModel,
    importerViewModel: ModpackImportViewModel,
    eventViewModel: EventViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val config = remember(context) { PlayLinkConfig.load(context) }
    val metadata = viewModel.resolvedLink
    var input by remember { mutableStateOf("") }
    var showImportConfirmation by remember { mutableStateOf(false) }

    if (showImportConfirmation && metadata != null) {
        AlertDialog(
            onDismissRequest = { showImportConfirmation = false },
            title = { Text(stringResource(R.string.playlink_confirm_import_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.playlink_confirm_import_message))
                    Text("${metadata.packName} · ${metadata.minecraftVersion}")
                    Text(
                        text = "${stringResource(R.string.playlink_pack_size)}: ${metadata.fileSizeBytes.formatPlayLinkSize()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showImportConfirmation = false
                        viewModel.downloadAndImport(context, metadata, importerViewModel, eventViewModel)
                    }
                ) {
                    Text(stringResource(R.string.playlink_download_import))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirmation = false }) {
                    Text(stringResource(R.string.generic_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BackButton(onBack)
        Text(
            text = stringResource(R.string.playlink_open_button),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.playlink_input_hint)) },
                singleLine = true,
                enabled = viewModel.operation == PlayLinkOperation.IDLE
            )
            Button(
                onClick = { viewModel.resolveLink(context, input) },
                enabled = input.isNotBlank() && viewModel.operation == PlayLinkOperation.IDLE && config.isConfigured
            ) {
                Text(stringResource(R.string.playlink_view_details))
            }
        }
        if (!config.isConfigured) {
            SetupRequiredText()
        }
        viewModel.errorMessage?.let { InlineError(it) }
        metadata?.let { link ->
            PlayLinkDetailsCard(link)
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { showImportConfirmation = true },
                enabled = viewModel.operation == PlayLinkOperation.IDLE
            ) {
                Text(stringResource(R.string.playlink_download_import))
            }
        }
    }
}

@Composable
private fun GeneratePlayLinkPage(
    viewModel: PlayLinkViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val config = remember(context) { PlayLinkConfig.load(context) }
    val versions by VersionsManager.versions.collectAsStateWithLifecycle()
    var selectedVersion by remember { mutableStateOf<Version?>(null) }
    var packName by remember(selectedVersion) {
        mutableStateOf(selectedVersion?.getVersionName().orEmpty())
    }
    var description by remember { mutableStateOf("") }
    val link = viewModel.generatedLink

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BackButton(onBack)
        Text(
            text = stringResource(R.string.playlink_create_button),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        if (link == null) {
            Text(stringResource(R.string.playlink_select_version), style = MaterialTheme.typography.bodyLarge)
            if (versions.isEmpty()) {
                Text(
                    text = stringResource(R.string.playlink_no_versions),
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(versions, key = { it.getVersionName() }) { version ->
                        val selected = selectedVersion == version
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable {
                                    selectedVersion = version
                                    packName = version.getVersionName()
                                    viewModel.clearError()
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    selectedVersion = version
                                    packName = version.getVersionName()
                                    viewModel.clearError()
                                }
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(version.getVersionName(), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = version.getVersionInfo()?.getInfoString().orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = packName,
                    onValueChange = { packName = it },
                    label = { Text(stringResource(R.string.playlink_name_hint)) },
                    singleLine = true,
                    enabled = viewModel.operation == PlayLinkOperation.IDLE
                )
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = description,
                    onValueChange = { description = it.take(500) },
                    label = { Text(stringResource(R.string.playlink_description_hint)) },
                    maxLines = 2,
                    enabled = viewModel.operation == PlayLinkOperation.IDLE
                )
            }
            Text(
                text = stringResource(R.string.playlink_included_files),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!config.isConfigured) SetupRequiredText()
            viewModel.errorMessage?.let { InlineError(it) }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    selectedVersion?.let { version ->
                        viewModel.generateLink(context, version, packName, description)
                    }
                },
                enabled = selectedVersion != null &&
                    packName.isNotBlank() &&
                    viewModel.operation == PlayLinkOperation.IDLE &&
                    config.isConfigured
            ) {
                Text(stringResource(R.string.playlink_generate_link))
            }
        } else {
            Text(stringResource(R.string.playlink_generated_link), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = link,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.playlink_generated_link)) },
                maxLines = 2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { copyPlayLink(context, link) }
                ) {
                    Text(stringResource(R.string.playlink_copy_link))
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { sharePlayLink(context, link) }
                ) {
                    Text(stringResource(R.string.playlink_share_link))
                }
            }
            TextButton(onClick = { viewModel.clearGeneratedLink() }) {
                Text(stringResource(R.string.playlink_create_button))
            }
        }
    }
}

@Composable
private fun PlayLinkDetailsCard(metadata: PlayLinkMetadata) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(metadata.packName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (metadata.description.isNotBlank()) {
                Text(metadata.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DetailRow(stringResource(R.string.playlink_game_version), metadata.minecraftVersion)
            DetailRow(
                stringResource(R.string.playlink_loader),
                listOf(metadata.loaderName, metadata.loaderVersion).filter { it.isNotBlank() }.joinToString(" ")
            )
            DetailRow(stringResource(R.string.playlink_mod_count), metadata.modCount.toString())
            DetailRow(stringResource(R.string.playlink_pack_size), metadata.fileSizeBytes.formatPlayLinkSize())
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack) {
        Icon(
            modifier = Modifier.size(18.dp),
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = null
        )
        Text(stringResource(R.string.generic_back))
    }
}

@Composable
private fun SetupRequiredText() {
    Text(
        text = stringResource(R.string.playlink_not_configured),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.tertiary
    )
}

@Composable
private fun InlineError(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
}

@Composable
private fun BusyOperation(viewModel: PlayLinkViewModel) {
    when (viewModel.operation) {
        PlayLinkOperation.IDLE -> Unit
        PlayLinkOperation.PACKAGING -> {
            val exporter = viewModel.activeExporter
            if (exporter == null) {
                ProgressDialog(stringResource(R.string.playlink_exporting))
            } else {
                val tasks by exporter.taskFlow.collectAsStateWithLifecycle()
                if (tasks.isEmpty()) {
                    ProgressDialog(stringResource(R.string.playlink_exporting))
                } else {
                    TitleTaskFlowDialog(
                        title = stringResource(R.string.playlink_exporting),
                        tasks = tasks,
                        onCancel = viewModel::cancelPackExport
                    )
                }
            }
        }
        PlayLinkOperation.UPLOADING -> ProgressDialog(stringResource(R.string.playlink_uploading))
        PlayLinkOperation.RESOLVING -> ProgressDialog(stringResource(R.string.playlink_resolve_loading))
        PlayLinkOperation.DOWNLOADING -> ProgressDialog(stringResource(R.string.playlink_downloading))
    }
}

@Composable
private fun ProgressDialog(message: String) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.playlink_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(message)
            }
        },
        confirmButton = {}
    )
}

private fun Long.formatPlayLinkSize(): String =
    if (this <= 0L) stringResourceCompatUnknown() else formatFileSize(this)

private fun stringResourceCompatUnknown(): String = "Unknown"

private fun copyPlayLink(context: Context, link: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("PlayLink", link))
}

private fun sharePlayLink(context: Context, link: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, link)
    ContextCompat.startActivity(context, Intent.createChooser(intent, "PlayLink"), null)
}
