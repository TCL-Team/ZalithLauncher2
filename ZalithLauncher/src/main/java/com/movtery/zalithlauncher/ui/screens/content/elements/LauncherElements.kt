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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.ui.screens.content.elements

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.account.isMicrosoftAccount
import com.movtery.zalithlauncher.game.launch.LaunchGame
import com.movtery.zalithlauncher.game.plugin.ApkPlugin
import com.movtery.zalithlauncher.game.plugin.natives.NativePluginManager
import com.movtery.zalithlauncher.game.plugin.renderer.RendererPluginManager
import com.movtery.zalithlauncher.game.renderer.RendererInterface
import com.movtery.zalithlauncher.game.renderer.Renderers
import com.movtery.zalithlauncher.game.renderer.renderers.NGGL4ESRenderer
import com.movtery.zalithlauncher.game.version.installed.GraphicsApi
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.hasVulkanBackend
import com.movtery.zalithlauncher.game.version.installed.utils.isBiggerVer
import com.movtery.zalithlauncher.game.version.installed.utils.isLowerVer
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.launcherMMKV
import com.movtery.zalithlauncher.setting.enums.BackgroundBlur
import com.movtery.zalithlauncher.ui.androidText
import com.movtery.zalithlauncher.ui.components.SimpleAlertDialog
import com.movtery.zalithlauncher.ui.components.VideoPlayer
import com.movtery.zalithlauncher.ui.screens.content.FirstLoginMenu
import com.movtery.zalithlauncher.utils.canHandlePermission
import com.movtery.zalithlauncher.utils.checkStoragePermissions
import com.movtery.zalithlauncher.utils.file.InvalidFilenameException
import com.movtery.zalithlauncher.utils.file.checkFilenameValidity
import com.movtery.zalithlauncher.utils.hasStoragePermission
import com.movtery.zalithlauncher.utils.string.isBiggerTo
import com.movtery.zalithlauncher.viewmodel.BackgroundViewModel
import com.movtery.zalithlauncher.viewmodel.ErrorViewModel
import com.movtery.zalithlauncher.viewmodel.EventViewModel
import com.movtery.zalithlauncher.viewmodel.sendToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import java.io.File

@Parcelize
sealed interface QuickPlay : Parcelable {
    /** 快速启动游玩存档  仅支持 1.20+ 23w14a+ */
    @Parcelize
    data class Save(val saveName: String): QuickPlay

    /** 快速启动游玩服务器 */
    @Parcelize
    data class Server(val serverAddress: String): QuickPlay
}

private const val MOBILEGLUES_PLUGIN_ID = "com.fcl.plugin.mobileglues"

/** Undo the temporary 26.3 renderer/backend overrides saved by earlier fork builds. */
private fun restoreMobileGluesSettings(version: Version, minecraftVersion: String) {
    if (!minecraftVersion.isBiggerTo("26.2")) return
    if (RendererPluginManager.getRendererList().none { it.getUniqueIdentifier() == MOBILEGLUES_PLUGIN_ID }) return

    val config = version.getVersionConfig()
    var changed = false

    // The short-lived NGGL4ES fallback wrote both values per version. Restore MobileGlues only
    // when that exact fallback state is present and the real plugin is installed.
    if (
        config.renderer == NGGL4ESRenderer.getUniqueIdentifier() &&
        config.graphicsApi == GraphicsApi.OPENGL
    ) {
        config.renderer = MOBILEGLUES_PLUGIN_ID
        changed = true
    }

    // An earlier Vulkan workaround persisted this per-version backend even though MobileGlues is GL.
    if (
        version.getRenderer() == MOBILEGLUES_PLUGIN_ID &&
        config.graphicsApi == GraphicsApi.VULKAN
    ) {
        config.graphicsApi = GraphicsApi.OPENGL
        changed = true
    }

    if (changed) config.save()
}

sealed interface LaunchGameOperation {
    data object None : LaunchGameOperation
    /** 没有安装版本/没有选中有效版本 */
    data object NoVersion : LaunchGameOperation
    /** 版本名称非法时 */
    data class InvalidVersionName(val th: InvalidFilenameException) : LaunchGameOperation
    /** 没有可用账号 */
    data object NoAccount : LaunchGameOperation
    /** 账号凭据失效，需要重新登录 */
    data class AccountRelogin(
        val account: Account,
        val version: Version,
        val quickPlay: QuickPlay?
    ) : LaunchGameOperation
    /** 启动前的账号刷新失败，可重试或跳过本次刷新 */
    data class AccountRefreshFailed(
        val account: Account,
        val th: Throwable,
        val version: Version,
        val quickPlay: QuickPlay?
    ) : LaunchGameOperation

    /** 渲染器可配置，但需要用到文件管理权限 */
    data class RendererNoStoragePermission(
        val renderer: RendererInterface,
        val version: Version,
        val quickPlay: QuickPlay?
    ) : LaunchGameOperation

    /** 当前渲染器不支持选中版本 */
    data class UnsupportedRenderer(
        val renderer: RendererInterface,
        val version: Version,
        val quickPlay: QuickPlay?
    ): LaunchGameOperation

    /** 当前已加载的插件不支持选中的版本 */
    data class UnsupportedPlugins(
        val plugins: List<ApkPlugin>,
        val version: Version,
        val quickPlay: QuickPlay?
    ): LaunchGameOperation

    /** 尝试启动：启动前检查一些东西 */
    data class TryLaunch(
        val version: Version?,
        val quickPlay: QuickPlay? = null
    ) : LaunchGameOperation

    /** 需要请求麦克风权限 */
    data class MicrophonePermission(
        val version: Version,
        val quickPlay: QuickPlay?
    ) : LaunchGameOperation

    /** 正式启动 */
    data class RealLaunch(
        val version: Version,
        val quickPlay: QuickPlay?,
        val skipAccountRefresh: Boolean = false
    ) : LaunchGameOperation
}

@Composable
fun LaunchGameOperation(
    activity: Activity,
    eventViewModel: EventViewModel,
    launchGameOperation: LaunchGameOperation,
    updateOperation: (LaunchGameOperation) -> Unit,
    exitActivity: () -> Unit,
    ensureVulkanSupported: suspend (Version) -> Boolean,
    submitError: (ErrorViewModel.ThrowableMessage) -> Unit,
    toAccountManageScreen: (FirstLoginMenu) -> Unit = {},
    toVersionManageScreen: () -> Unit = {}
) {
    when (launchGameOperation) {
        is LaunchGameOperation.None -> {}
        is LaunchGameOperation.NoVersion -> {
            LaunchedEffect(Unit) {
                eventViewModel.sendToast(androidText(R.string.game_launch_no_version))
                toVersionManageScreen()
                updateOperation(LaunchGameOperation.None)
            }
        }
        is LaunchGameOperation.InvalidVersionName -> {
            val th = launchGameOperation.th
            SimpleAlertDialog(
                title = stringResource(R.string.versions_manage_invalid),
                text = th.getInvalidSummary(),
                confirmText = stringResource(R.string.generic_cancel),
                onDismiss = {
                    updateOperation(LaunchGameOperation.None)
                }
            )
        }
        is LaunchGameOperation.NoAccount -> {
            LaunchedEffect(Unit) {
                eventViewModel.sendToast(androidText(R.string.game_launch_no_account))
                // Keep the launcher’s local account chooser available for both Microsoft and offline login.
                toAccountManageScreen(FirstLoginMenu.NORMAL)
                updateOperation(LaunchGameOperation.None)
            }
        }
        is LaunchGameOperation.AccountRelogin -> {
            val account = launchGameOperation.account
            val message = if (account.isMicrosoftAccount()) {
                stringResource(R.string.account_relogin_microsoft_message)
            } else {
                stringResource(R.string.account_relogin_password_message, account.username)
            }
            SimpleAlertDialog(
                title = stringResource(R.string.account_relogin_title),
                text = message,
                confirmText = stringResource(R.string.account_relogin),
                dismissText = stringResource(R.string.generic_cancel),
                dismissByDialog = false,
                onConfirm = {
                    toAccountManageScreen(
                        if (account.isMicrosoftAccount()) FirstLoginMenu.MICROSOFT
                        else FirstLoginMenu.NORMAL
                    )
                    updateOperation(LaunchGameOperation.None)
                },
                onDismiss = { updateOperation(LaunchGameOperation.None) }
            )
        }
        is LaunchGameOperation.AccountRefreshFailed -> {
            val baseMessage = stringResource(R.string.account_refresh_failed_skip_message)
            val errorMessage = launchGameOperation.th.localizedMessage
                ?.takeIf { it.isNotBlank() }
            val message = if (errorMessage != null) "$baseMessage\n\n$errorMessage" else baseMessage
            SimpleAlertDialog(
                title = stringResource(R.string.account_refresh_failed_title),
                text = message,
                confirmText = stringResource(R.string.account_refresh_failed_skip),
                dismissText = stringResource(R.string.account_refresh_failed_retry),
                dismissByDialog = false,
                onConfirm = {
                    updateOperation(
                        LaunchGameOperation.RealLaunch(
                            version = launchGameOperation.version,
                            quickPlay = launchGameOperation.quickPlay,
                            skipAccountRefresh = true
                        )
                    )
                },
                onDismiss = {
                    updateOperation(
                        LaunchGameOperation.TryLaunch(
                            version = launchGameOperation.version,
                            quickPlay = launchGameOperation.quickPlay
                        )
                    )
                }
            )
        }
        is LaunchGameOperation.RendererNoStoragePermission -> {
            LaunchedEffect(Unit) {
                val renderer = launchGameOperation.renderer
                val version = launchGameOperation.version
                val quickPlay = launchGameOperation.quickPlay
                withContext(Dispatchers.Main) {
                    checkStoragePermissions(
                        activity = activity,
                        message = activity.getString(R.string.renderer_version_storage_permissions, renderer.getRendererName()),
                        messageSdk30 = activity.getString(R.string.renderer_version_storage_permissions_sdk30, renderer.getRendererName()),
                        onDialogCancel = {
                            //用户拒绝授权，但仍然允许启动（不过这会导致配置无法读取）
                            updateOperation(LaunchGameOperation.RealLaunch(version, quickPlay))
                        }
                    )
                }
                updateOperation(LaunchGameOperation.None)
            }
        }
        is LaunchGameOperation.UnsupportedRenderer -> {
            val renderer = launchGameOperation.renderer
            val version = launchGameOperation.version
            val quickPlay = launchGameOperation.quickPlay
            SimpleAlertDialog(
                title = stringResource(R.string.generic_warning),
                text = stringResource(R.string.renderer_version_unsupported_warning, renderer.getRendererName()),
                confirmText = stringResource(R.string.generic_anyway),
                onConfirm = {
                    updateOperation(LaunchGameOperation.RealLaunch(version, quickPlay))
                },
                onDismiss = {
                    updateOperation(LaunchGameOperation.None)
                }
            )
        }
        is LaunchGameOperation.UnsupportedPlugins -> {
            val plugins = launchGameOperation.plugins
            val version = launchGameOperation.version
            val quickPlay = launchGameOperation.quickPlay
            SimpleAlertDialog(
                title = stringResource(R.string.generic_warning),
                text = stringResource(R.string.plugin_unsupported_warning, plugins.joinToString(", ") { it.appName }),
                confirmText = stringResource(R.string.generic_anyway),
                onConfirm = {
                    updateOperation(LaunchGameOperation.RealLaunch(version, quickPlay))
                },
                onDismiss = {
                    updateOperation(LaunchGameOperation.None)
                }
            )
        }
        is LaunchGameOperation.MicrophonePermission -> {
            val version = launchGameOperation.version
            val quickPlay = launchGameOperation.quickPlay

            val requestPermissionLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                    launcherMMKV().putBoolean("microphone_asked", true)
                    updateOperation(LaunchGameOperation.RealLaunch(version, quickPlay))
                }

            SimpleAlertDialog(
                title = stringResource(R.string.microphone_check_title),
                text = activity.getString(R.string.microphone_launch_dialog),
                confirmText = stringResource(R.string.microphone_allow),
                dismissText = stringResource(R.string.microphone_skip_ask),
                dismissByDialog = false,
                onConfirm = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        launcherMMKV().putBoolean("microphone_asked", true)
                        updateOperation(LaunchGameOperation.RealLaunch(version, quickPlay))
                    }
                },
                onDismiss = {
                    launcherMMKV().putBoolean("microphone_asked", true)
                    updateOperation(LaunchGameOperation.RealLaunch(version, quickPlay))
                }
            )
        }
        is LaunchGameOperation.TryLaunch -> {
            LaunchedEffect(Unit) {
                val version = launchGameOperation.version ?: run {
                    updateOperation(LaunchGameOperation.NoVersion)
                    return@LaunchedEffect
                }

                try {
                    checkFilenameValidity(version.getVersionName())
                } catch (th: InvalidFilenameException) {
                    updateOperation(LaunchGameOperation.InvalidVersionName(th))
                    return@LaunchedEffect
                }

                val quickPlay = launchGameOperation.quickPlay

                AccountsManager.currentAccountFlow.value ?: run {
                    updateOperation(LaunchGameOperation.NoAccount)
                    return@LaunchedEffect
                }

                val mcVer = version.getVersionInfo()!!.minecraftVersion

                restoreMobileGluesSettings(version, mcVer)

                //开始检查渲染器的版本支持情况
                Renderers.setCurrentRenderer(version.getRenderer())
                val currentRenderer = Renderers.getCurrentRenderer()
                val rendererMinVer = currentRenderer.getMinMCVersion()
                val rendererMaxVer = currentRenderer.getMaxMCVersion()

                // Match official Zalith's per-version Vulkan gate: only skip renderer version
                // limits when this client has a Vulkan backend and the selected driver supports it.
                if (!version.hasVulkanBackend() || !ensureVulkanSupported(version)) {
                    val isRendererUnsupported =
                        (rendererMinVer?.let { mcVer.isLowerVer(it) } ?: false) ||
                                (rendererMaxVer?.let { mcVer.isBiggerVer(it) } ?: false)

                    if (isRendererUnsupported) {
                        updateOperation(LaunchGameOperation.UnsupportedRenderer(currentRenderer, version, quickPlay))
                        return@LaunchedEffect
                    }
                }

                val unsupportedPlugins = NativePluginManager.getCheckedPlugins().filter { plugin ->
                    (plugin.minMCVer?.let { mcVer.isLowerVer(it) } ?: false) ||
                            (plugin.maxMCVer?.let { mcVer.isBiggerVer(it) } ?: false)
                }
                if (unsupportedPlugins.isNotEmpty()) {
                    updateOperation(LaunchGameOperation.UnsupportedPlugins(unsupportedPlugins, version, quickPlay))
                    return@LaunchedEffect
                }

                //为可配置的渲染器检查文件管理权限
                //前提：系统支持这个设置
                if (
                    canHandlePermission &&  !hasStoragePermission &&
                    RendererPluginManager.isConfigurablePlugin(version.getRenderer())
                ) {
                    updateOperation(LaunchGameOperation.RendererNoStoragePermission(currentRenderer, version, quickPlay))
                    return@LaunchedEffect
                }

                //首次启动时请求麦克风权限（Simple Voice Chat等mod需要）
                if (!launcherMMKV().getBoolean("microphone_asked", false) &&
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                ) {
                    updateOperation(LaunchGameOperation.MicrophonePermission(version, quickPlay))
                    return@LaunchedEffect
                }

                //正式启动游戏
                updateOperation(LaunchGameOperation.RealLaunch(version, quickPlay))
            }
        }
        is LaunchGameOperation.RealLaunch -> {
            LaunchedEffect(Unit) {
                val version = launchGameOperation.version
                val quickPlay = launchGameOperation.quickPlay
                version.apply {
                    offlineAccountLogin = false
                    quickPlaySingle = quickPlay
                }
                LaunchGame.launchGame(
                    context = activity,
                    version = version,
                    exitActivity = exitActivity,
                    submitError = submitError,
                    skipAccountRefresh = launchGameOperation.skipAccountRefresh
                )
                updateOperation(LaunchGameOperation.None)
            }
        }
    }
}

/**
 * 启动器背景图片/视频层
 */
@Composable
fun Background(
    viewModel: BackgroundViewModel,
    modifier: Modifier = Modifier,
    allowVideo: Boolean = true
) {
    val context = LocalContext.current
    val blur = AllSettings.backgroundBlur.state
    val opacity = AllSettings.launcherBackgroundOpacity.state
    val backgroundMode = AllSettings.backgroundBlurType.state == BackgroundBlur.Background
    val backgroundBlurEnabled = backgroundMode && blur > 0 && opacity < 100
    val layerCapture = !backgroundMode && blur > 0 && opacity < 100 && viewModel.isValid
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .then(
                if (backgroundBlurEnabled && Build.VERSION.SDK_INT >= 31) {
                    Modifier.blur(blur.dp)
                } else {
                    Modifier
                }
            )
            .backgroundCapture(
                store = viewModel,
                recordContent = layerCapture,
                blurRadiusPx = blur * density.density,
                whiteOverlayAlpha = if (backgroundBlurEnabled) whiteOverlayAlpha(blur) else 0f
            )
    ) {
        if (viewModel.isValid) {
            when {
                viewModel.isVideo && allowVideo -> {
                    VideoPlayer(
                        videoUri = Uri.fromFile(viewModel.backgroundFile),
                        modifier = Modifier.fillMaxSize(),
                        refreshTrigger = viewModel.refreshTrigger,
                        volume = AllSettings.videoBackgroundVolume.state / 100f
                    )
                }
                viewModel.isImage -> {
                    val blurred = viewModel.blurredBackground
                    if (backgroundBlurEnabled && Build.VERSION.SDK_INT < 31 && blurred != null) {
                        Image(
                            bitmap = blurred,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        BackgroundImage(
                            modifier = Modifier.fillMaxSize(),
                            imageFile = viewModel.backgroundFile,
                            refreshTrigger = viewModel.refreshTrigger
                        )
                    }
                }
            }
        } else if (allowVideo) {
            VideoPlayer(
                videoUri = Uri.parse("android.resource://${context.packageName}/${R.raw.default_background}"),
                modifier = Modifier.fillMaxSize(),
                volume = AllSettings.videoBackgroundVolume.state / 100f
            )
        }
    }
}

@Composable
private fun BackgroundImage(
    refreshTrigger: Any,
    imageFile: File,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val request = remember(refreshTrigger, imageFile) {
        ImageRequest.Builder(context)
            .data(imageFile)
            .allowHardware(false)
            .crossfade(false)
            .build()
    }

    AsyncImage(
        modifier = modifier,
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop
    )
}