package com.movtery.zalithlauncher.ui.screens.content.elements

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.movtery.zalithlauncher.R

/** ZalithLauncher project contributors shown in the in-app team dialog. */
data class TeamMember(
    @StringRes val nameRes: Int,
    @StringRes val categoryRes: Int,
    @DrawableRes val photoRes: Int
)

val teamMembers = listOf(
    TeamMember(
        nameRes = R.string.team_member_movtery,
        categoryRes = R.string.team_category_project_author,
        photoRes = R.drawable.img_avatar_movtery
    ),
    TeamMember(
        nameRes = R.string.team_member_community,
        categoryRes = R.string.team_category_contributors,
        photoRes = R.drawable.ic_group_filled
    )
)
