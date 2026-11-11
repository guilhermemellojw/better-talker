package com.bettertalker.app.ui.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bettertalker.app.data.voice.PttController
import kotlinx.coroutines.delay

/**
 * Rota do Assistente sem nota vinculada: home global + PTT/TTS + chat global.
 *
 * A home global e o [NoteChatHost] são mutuamente exclusivos; cada um registra
 * os callbacks do PTT enquanto está composto e cancela o microfone ao sair.
 */
@Composable
fun AssistantGlobalRoute(
    vm: GlobalAssistantViewModel,
    notes: List<GlobalNote>,
    loading: Boolean,
    ptt: PttController,
    onPickNote: () -> Unit,
    onOpenMyOutlines: () -> Unit,
    onCreateNote: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenPrepare: () -> Unit,
    onOpenTrain: () -> Unit,
    onOpenPresentRoot: () -> Unit,
    onOpenModel: () -> Unit,
    onStartTrain: (String) -> Unit,
    onOpenPresent: (String) -> Unit,
    onOpenHelp: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    openChatRequest: Int = 0,
) {
    val ctx = LocalContext.current
    val turns by vm.turns.collectAsState()
    val busy by vm.busy.collectAsState()
    val pending by vm.pending.collectAsState()
    val pttState by ptt.state.collectAsState()
    val pttPartial by ptt.partial.collectAsState()
    val ttsSpeaking by vm.speaking.collectAsState()
    val ttsReady by vm.ttsReady.collectAsState()

    var chatOpen by rememberSaveable { mutableStateOf(false) }
    var focusComposer by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(openChatRequest) {
        if (openChatRequest > 0) {
            focusComposer = true
            chatOpen = true
        }
    }
    var pttAwaiting by remember { mutableStateOf(false) }
    var pttStartIndex by remember { mutableStateOf(0) }
    val latestTurns by rememberUpdatedState(turns)
    val latestStartTrain by rememberUpdatedState(onStartTrain)
    val latestOpenPresent by rememberUpdatedState(onOpenPresent)
    val latestOpenModel by rememberUpdatedState(onOpenModel)
    val latestOpenMyOutlines by rememberUpdatedState(onOpenMyOutlines)
    val latestCreateNote by rememberUpdatedState(onCreateNote)
    val latestOpenLibrary by rememberUpdatedState(onOpenLibrary)
    val latestOpenPrepare by rememberUpdatedState(onOpenPrepare)
    val latestOpenTrain by rememberUpdatedState(onOpenTrain)
    val latestOpenPresentRoot by rememberUpdatedState(onOpenPresentRoot)

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (vm.speaking.value) vm.stopSpeaking()
            if (vm.busy.value) vm.stopGeneration()
            ptt.press()
        }
    }
    val pttPressChecked: () -> Unit = {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            if (vm.speaking.value) vm.stopSpeaking()
            if (vm.busy.value) vm.stopGeneration()
            ptt.press()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(ptt, vm) {
        ptt.onTranscript = { text ->
            pttAwaiting = true
            pttStartIndex = latestTurns.size
            vm.send(text, fromVoice = true)
        }
        ptt.onError = { message -> vm.onVoiceError(message) }
        ptt.onStopSpeaking = { vm.stopSpeaking() }
        ptt.onStopThinking = { vm.stopGeneration() }
        onDispose {
            ptt.cancel()
            vm.stopSpeaking()
        }
    }

    LaunchedEffect(turns, busy, pttAwaiting, ttsReady) {
        if (!pttAwaiting || busy) return@LaunchedEffect
        val answer = turns.drop(pttStartIndex)
            .lastOrNull { !it.fromMe && it.text.isNotBlank() }
            ?: return@LaunchedEffect
        if (!answer.error && ttsReady) {
            vm.speak(answer.text)
            ptt.onResponseFinished(spoken = true)
        } else {
            ptt.onResponseFinished(spoken = false)
        }
        pttAwaiting = false
    }
    LaunchedEffect(ptt, pttAwaiting) {
        if (!pttAwaiting) return@LaunchedEffect
        delay(15_000)
        if (pttAwaiting) {
            ptt.onResponseFinished(spoken = false)
            pttAwaiting = false
        }
    }
    LaunchedEffect(ttsSpeaking) {
        if (ttsSpeaking) ptt.onSpeakingStarted() else ptt.onSpeakingFinished()
    }

    // Background/rotação: microfone e fala param; a geração é cancelada.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, ptt, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                ptt.cancel()
                vm.stopSpeaking()
                vm.stopGeneration()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is GlobalAssistantEvent.StartTrain -> latestStartTrain(event.noteId)
                is GlobalAssistantEvent.OpenPresent -> latestOpenPresent(event.noteId)
                GlobalAssistantEvent.ConfigureAi -> latestOpenModel()
                GlobalAssistantEvent.OpenCreateSheet -> latestCreateNote()
                GlobalAssistantEvent.OpenMyOutlines -> latestOpenMyOutlines()
                GlobalAssistantEvent.OpenLibrary -> latestOpenLibrary()
                GlobalAssistantEvent.OpenPrepare -> latestOpenPrepare()
                GlobalAssistantEvent.OpenTrain -> latestOpenTrain()
                GlobalAssistantEvent.OpenPresentRoot -> latestOpenPresentRoot()
            }
        }
    }

    AssistantGlobalScreen(
        notes = notes,
        loading = loading,
        turns = turns,
        busy = busy,
        pttState = pttState,
        pttPartial = pttPartial,
        ttsSpeaking = ttsSpeaking,
        pending = pending,
        onPttPress = pttPressChecked,
        onPttRelease = { ptt.release() },
        onPttCancel = { ptt.cancel() },
        onOpenChat = {
            focusComposer = false
            chatOpen = true
        },
        onPickNote = {
            ptt.cancel()
            vm.stopSpeaking()
            onPickNote()
        },
        onOpenMyOutlines = {
            ptt.cancel()
            vm.stopSpeaking()
            latestOpenMyOutlines()
        },
        onCreateNote = {
            ptt.cancel()
            vm.stopSpeaking()
            latestCreateNote()
        },
        onOpenLibrary = {
            ptt.cancel()
            vm.stopSpeaking()
            latestOpenLibrary()
        },
        onOpenPrepare = {
            ptt.cancel()
            vm.stopSpeaking()
            latestOpenPrepare()
        },
        onOpenTrain = {
            ptt.cancel()
            vm.stopSpeaking()
            latestOpenTrain()
        },
        onOpenPresent = {
            ptt.cancel()
            vm.stopSpeaking()
            latestOpenPresentRoot()
        },
        onConfigureAi = onOpenModel,
        onHelp = onOpenHelp,
        onOpenSettings = {
            ptt.cancel()
            vm.stopSpeaking()
            onOpenSettings()
        },
        onRetry = { vm.retryLast() },
        onSendText = { text -> vm.send(text) },
    )

    if (chatOpen) {
        AssistantGlobalChatSheet(
            turns = turns,
            busy = busy,
            onSend = { text -> vm.send(text) },
            onConfigureAi = {
                chatOpen = false
                onOpenModel()
            },
            onRetry = { vm.retryLast() },
            onDismiss = {
                chatOpen = false
                focusComposer = false
            },
            focusComposer = focusComposer,
        )
    }
}
