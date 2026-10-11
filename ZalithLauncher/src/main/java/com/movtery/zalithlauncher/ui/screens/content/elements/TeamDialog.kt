package com.movtery.zalithlauncher.ui.screens.content.elements

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R

@Composable
fun TeamDialog(
    onDismissRequest: () -> Unit
) {
    val grouped = remember { teamMembers.groupBy { it.category } }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(R.string.team_dialog_title)) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                grouped.forEach { (category, members) ->
                    item {
                        Text(
                            text = category,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(members) { member ->
                        TeamMemberRow(member = member)
                    }
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
private fun TeamMemberRow(member: TeamMember) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            modifier = Modifier
                .size(48.dp)
                .clip(shape = CircleShape),
            painter = painterResource(member.photoRes),
            contentDescription = null,
            contentScale = ContentScale.Crop
        )
        Column {
            Text(text = stringResource(member.nameRes), style = MaterialTheme.typography.bodyLarge)
            Text(text = stringResource(member.categoryRes), style = MaterialTheme.typography.bodySmall)
        }
    }
}
