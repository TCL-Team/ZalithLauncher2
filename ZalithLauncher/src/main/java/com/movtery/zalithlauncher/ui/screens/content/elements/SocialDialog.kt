package com.movtery.zalithlauncher.ui.screens.content.elements

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import androidx.core.net.toUri

@Composable
fun SocialDialog(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(R.string.social_dialog_title)) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(socialLinks) { link ->
                    SocialLinkRow(
                        link = link,
                        onOpen = {
                            val intent = Intent(Intent.ACTION_VIEW, link.url.toUri())
                            context.startActivity(intent)
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(R.string.generic_close))
            }
        }
    )
}

@Composable
private fun SocialLinkRow(
    link: SocialLink,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            modifier = Modifier
                .size(44.dp)
                .clip(shape = CircleShape),
            painter = painterResource(link.iconRes),
            contentDescription = null,
            contentScale = ContentScale.Crop
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(link.nameRes), style = MaterialTheme.typography.bodyLarge)
            Text(text = stringResource(link.descriptionRes), style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onOpen) {
            Text(text = stringResource(R.string.generic_open_link))
        }
    }
}
