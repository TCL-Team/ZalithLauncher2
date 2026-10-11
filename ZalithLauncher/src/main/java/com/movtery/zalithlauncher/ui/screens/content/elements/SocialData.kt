package com.movtery.zalithlauncher.ui.screens.content.elements

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.path.URL_COMMUNITY
import com.movtery.zalithlauncher.path.URL_PROJECT
import com.movtery.zalithlauncher.path.URL_SUPPORT
import com.movtery.zalithlauncher.path.URL_WEBLATE

/** Official ZalithLauncher destinations exposed by the Social dialog. */
data class SocialLink(
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    @DrawableRes val iconRes: Int,
    val url: String
)

val socialLinks = listOf(
    SocialLink(
        nameRes = R.string.social_project_name,
        descriptionRes = R.string.social_project_description,
        iconRes = R.drawable.ic_github,
        url = URL_PROJECT
    ),
    SocialLink(
        nameRes = R.string.social_contributors_name,
        descriptionRes = R.string.social_contributors_description,
        iconRes = R.drawable.ic_group_filled,
        url = URL_COMMUNITY
    ),
    SocialLink(
        nameRes = R.string.social_translations_name,
        descriptionRes = R.string.social_translations_description,
        iconRes = R.drawable.ic_public,
        url = URL_WEBLATE
    ),
    SocialLink(
        nameRes = R.string.social_support_name,
        descriptionRes = R.string.social_support_description,
        iconRes = R.drawable.ic_favorite_filled,
        url = URL_SUPPORT
    )
)
