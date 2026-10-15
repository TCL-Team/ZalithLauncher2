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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.ui.screens.content

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.viewmodel.ScreenBackStackViewModel

private enum class PlayLinkPage {
    HOME,
    OPEN,
    CREATE
}

@Composable
fun PlayLinkScreen(backStackViewModel: ScreenBackStackViewModel) {
    var page by remember { mutableStateOf(PlayLinkPage.HOME) }

    BaseScreen(
        screenKey = NormalNavKey.PlayLink,
        currentKey = backStackViewModel.mainScreen.currentKey
    ) {
        when (page) {
            PlayLinkPage.HOME -> PlayLinkHome(
                onOpenPlayLink = { page = PlayLinkPage.OPEN },
                onCreatePlayLink = { page = PlayLinkPage.CREATE }
            )
            PlayLinkPage.OPEN -> OpenPlayLinkPage(
                onBack = { page = PlayLinkPage.HOME }
            )
            PlayLinkPage.CREATE -> CreatePlayLinkPage(
                onBack = { page = PlayLinkPage.HOME }
            )
        }
    }
}

@Composable
private fun PlayLinkHome(
    onOpenPlayLink: () -> Unit,
    onCreatePlayLink: () -> Unit
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
                buttonText = stringResource(R.string.playlink_open_button),
                iconRes = R.drawable.ic_link,
                onClick = onOpenPlayLink
            )
            PlayLinkActionCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.playlink_create_button),
                description = stringResource(R.string.playlink_create_description),
                buttonText = stringResource(R.string.playlink_create_button),
                iconRes = R.drawable.ic_share_filled,
                onClick = onCreatePlayLink
            )
        }
    }
}

@Composable
private fun PlayLinkActionCard(
    modifier: Modifier,
    title: String,
    description: String,
    buttonText: String,
    iconRes: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
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
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onClick
            ) {
                Text(buttonText)
            }
        }
    }
}

@Composable
private fun OpenPlayLinkPage(onBack: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val backendStatus = stringResource(R.string.playlink_backend_pending)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) {
            Icon(
                modifier = Modifier.size(18.dp),
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = null
            )
            Text(stringResource(R.string.generic_back))
        }
        Text(
            text = stringResource(R.string.playlink_open_button),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = link,
            onValueChange = {
                link = it
                submitted = false
            },
            label = { Text(stringResource(R.string.playlink_input_hint)) },
            singleLine = true
        )
        Button(
            onClick = { submitted = true },
            enabled = link.isNotBlank()
        ) {
            Text(stringResource(R.string.playlink_view_details))
        }
        if (submitted) {
            Text(
                text = backendStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CreatePlayLinkPage(onBack: () -> Unit) {
    val versions by VersionsManager.versions.collectAsState()
    var selectedVersion by remember { mutableStateOf<Version?>(null) }
    var submitted by remember { mutableStateOf(false) }
    val backendStatus = stringResource(R.string.playlink_backend_pending)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(onClick = onBack) {
            Icon(
                modifier = Modifier.size(18.dp),
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = null
            )
            Text(stringResource(R.string.generic_back))
        }
        Text(
            text = stringResource(R.string.playlink_create_button),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.playlink_select_version),
            style = MaterialTheme.typography.bodyLarge
        )

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
                items(
                    items = versions,
                    key = { it.getVersionName() }
                ) { version ->
                    val selected = selectedVersion == version
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable {
                                selectedVersion = version
                                submitted = false
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = {
                                selectedVersion = version
                                submitted = false
                            }
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = version.getVersionName(),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = version.getVersionInfo()?.minecraftVersion.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = { submitted = true },
            enabled = selectedVersion != null
        ) {
            Text(stringResource(R.string.playlink_generate_link))
        }
        if (submitted) {
            Text(
                text = backendStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
