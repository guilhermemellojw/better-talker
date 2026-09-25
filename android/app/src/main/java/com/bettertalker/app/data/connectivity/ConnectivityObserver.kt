package com.bettertalker.app.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fase 16 — estado de conectividade para o chat (§22 F15).
 *
 * O app é offline-first: o acervo local (Room) e as respostas determinísticas
 * continuam funcionando sem rede. Este observador existe só para o Copilot
 * remoto, que precisa dizer "você está offline" em vez de fingir uma resposta.
 *
 * Reativo, sem polling e sem requisição de rede extra — igual ao web, que
 * escuta os eventos `online`/`offline`.
 */
class ConnectivityObserver(context: Context) {

    private val cm: ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager

    private val _online = MutableStateFlow(isCurrentlyOnline())

    /** Estado atual, para leitura síncrona na composição. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _online.value = true
            }

            override fun onLost(network: Network) {
                // onLost pode vir de uma rede secundária: só desliga se
                // nenhuma outra continuar valendo.
                _online.value = isCurrentlyOnline()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        })
    }

    /**
     * `VALIDATED` = internet realmente verificada. Uma rede Wi-Fi captive não
     * conta como online, senão o chat tentaria e falharia com atraso.
     */
    private fun isCurrentlyOnline(): Boolean {
        val c = cm ?: return false
        val caps = c.getNetworkCapabilities(c.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/** Versão em Flow para quem preferir coletar em vez de ler o StateFlow. */
fun ConnectivityObserver.asFlow() = online

/** Consulta pontual, sem registrar callback (código não-composável). */
fun currentOnlineState(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
