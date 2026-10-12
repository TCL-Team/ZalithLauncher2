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

package com.movtery.zalithlauncher.game.launch

import android.content.Context
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.coroutine.Task
import com.movtery.zalithlauncher.coroutine.TaskSystem
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.account.auth_server.ResponseException
import com.movtery.zalithlauncher.game.account.isLocalAccount
import com.movtery.zalithlauncher.game.account.microsoft.MinecraftProfileException
import com.movtery.zalithlauncher.game.account.microsoft.NotPurchasedMinecraftException
import com.movtery.zalithlauncher.game.account.microsoft.XboxLoginException
import com.movtery.zalithlauncher.game.account.microsoft.toLocal
import com.movtery.zalithlauncher.game.version.download.DownloadMode
import com.movtery.zalithlauncher.game.version.download.MinecraftDownloader
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionFolders
import com.movtery.zalithlauncher.game.version.mod.AllModReader
import com.movtery.zalithlauncher.ui.AndroidStringText
import com.movtery.zalithlauncher.ui.activities.runGame
import com.movtery.zalithlauncher.ui.androidText
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.isNetworkAvailable
import com.movtery.zalithlauncher.utils.network.toLocal
import com.movtery.zalithlauncher.viewmodel.ErrorViewModel
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

private const val TAG = "LaunchGame"

object LaunchGame {
    var isLaunching: Boolean = false
        private set

    fun launchGame(
        context: Context,
        version: Version,
        exitActivity: () -> Unit,
        submitError: (ErrorViewModel.ThrowableMessage) -> Unit,
        skipAccountRefresh: Boolean = false
    ) {
        if (isLaunching) return
        val account = AccountsManager.currentAccountFlow.value ?: return
        isLaunching = true

        //检查是否联网，根据这个条件决定是否登录账号
        //以及，没有联网时，让微软账号、外置账号作为离线账号登录
        val hasNetwork = isNetworkAvailable(context)

        val downloadTask = createDownloadTask(
            context = context,
            version = version,
            account = account,
            exitActivity = exitActivity,
            submitError = submitError
        )
        fun startDownloadTask() {
            TaskSystem.submitTask(downloadTask) { isLaunching = false }
        }

        val loginTask = if (skipAccountRefresh) null else createLoginTask(
            context = context,
            hasNetwork = hasNetwork,
            account = account,
            submitError = submitError
        ) {
            startDownloadTask()
        }

        if (loginTask != null) {
            TaskSystem.submitTask(loginTask)
        } else {
            if (!hasNetwork && !account.isLocalAccount()) {
                //没联网时作为离线账号登录
                version.offlineAccountLogin = true
            }
            startDownloadTask()
        }
    }

    private fun createDownloadTask(
        context: Context,
        version: Version,
        account: Account,
        exitActivity: () -> Unit,
        submitError: (ErrorViewModel.ThrowableMessage) -> Unit
    ): Task {
        return MinecraftDownloader(
            context = context,
            version = version.getVersionInfo()?.minecraftVersion ?: version.getVersionName(),
            customName = version.getVersionName(),
            verifyIntegrity = !version.skipGameIntegrityCheck(),
            mode = DownloadMode.VERIFY_AND_REPAIR,
            onCompletion = { task ->
                task.updateProgress(-1f)
                task.updateMessage(null)
                checkEnableTouchProxy(version)

                runGame(context, version, account)
                exitActivity()
            },
            onError = { message ->
                submitError(
                    ErrorViewModel.ThrowableMessage(
                        title = androidText(R.string.minecraft_download_failed),
                        message = androidText(message)
                    )
                )
            }
        ).getDownloadTask()
    }

    /**
     * 检查是否安装了 TouchController，安装后开启控制代理
     */
    private suspend fun checkEnableTouchProxy(version: Version) {
        val modsDir = VersionFolders.MOD.getDir(version.getGameDir())
        val reader = AllModReader(modsDir)
        for (mod in reader.readAllLocals()) {
            if (mod.id == "touchcontroller") {
                version.enableTouchProxy = true
                break
            }
        }
    }

    private fun createLoginTask(
        context: Context,
        hasNetwork: Boolean,
        account: Account,
        submitError: (ErrorViewModel.ThrowableMessage) -> Unit,
        onFinally: () -> Unit
    ): Task? {
        val needsRefresh = hasNetwork && System.currentTimeMillis() > account.expiresAt - 5 * 60 * 1000

        return if (needsRefresh) {
            AccountsManager.performLoginTask(
                context = context,
                account = account,
                onSuccess = { acc, _ ->
                    AccountsManager.suspendSaveAccount(acc)
                },
                onFailed = { error ->
                    val message: AndroidStringText = when (error) {
                        is NotPurchasedMinecraftException -> toLocal()
                        is MinecraftProfileException -> error.toLocal()
                        is XboxLoginException -> error.toLocal()
                        is ResponseException -> androidText(error.responseMessage)
                        is HttpRequestTimeoutException -> androidText(R.string.error_timeout)
                        is UnknownHostException, is UnresolvedAddressException -> androidText(R.string.error_network_unreachable)
                        is ConnectException -> androidText(R.string.error_connection_failed)
                        is io.ktor.client.plugins.ResponseException -> error.toLocal()
                        else -> {
                            Logger.error(TAG, "An unknown exception was caught!", error)
                            androidText(
                                error.localizedMessage ?: error.message ?: error::class.qualifiedName ?: "Unknown error"
                            )
                        }
                    }

                    submitError(
                        ErrorViewModel.ThrowableMessage(
                            title = androidText(R.string.account_logging_in_failed),
                            message = message
                        )
                    )
                },
                onFinally = onFinally
            )
        } else {
            null
        }
    }
}
