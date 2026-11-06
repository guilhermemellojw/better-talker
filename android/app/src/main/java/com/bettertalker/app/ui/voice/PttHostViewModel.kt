package com.bettertalker.app.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bettertalker.app.data.voice.PttController
import com.bettertalker.app.data.voice.PttSttEngineProvider

/**
 * Dono do PTT no app: o motor (sherpa/on-device/sistema) é criado uma vez e
 * compartilhado entre a aba Assistente e o chat do editor. A tela ativa troca
 * apenas os callbacks (nota/foco atual); o modelo não recarrega a cada rota.
 */
class PttHostViewModel(ctx: Context) : ViewModel() {

    private val engines = PttSttEngineProvider(ctx.applicationContext) {
        // v4.0: sherpa local é o padrão; o provider cai para on-device/sistema
        // se o modelo não estiver baixado.
        true
    }

    val controller = PttController(engines)

    override fun onCleared() {
        controller.dispose()
        super.onCleared()
    }

    class Factory(private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PttHostViewModel(ctx) as T
    }
}
