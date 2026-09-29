package com.lecteur.player.jam

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.ServerSocket

/** Une jam annoncée sur le réseau local. */
data class NearbyJam(val code: String, val hostName: String)

/**
 * Jams sur le même Wi-Fi, sans taper de code : l'hôte s'annonce sur le réseau local (NSD, « Bonjour »),
 * les autres écoutent les annonces. Seul le code circule ; tout le reste passe par Firestore.
 */
object NearbyJams {

    private const val SERVICE_TYPE = "_reaperjam._tcp."

    private var registration: NsdManager.RegistrationListener? = null
    private var socket: ServerSocket? = null

    /** L'hôte annonce sa jam ; un seul appel actif à la fois. */
    fun advertise(context: Context, code: String, hostName: String) {
        stopAdvertising(context)
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        // NSD exige un port : on en réserve un, sans jamais rien y écouter.
        val port = runCatching { ServerSocket(0).also { socket = it }.localPort }.getOrNull() ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "Reaper $code"
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("code", code)
            setAttribute("host", hostName.take(40))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stopAdvertising(context: Context) {
        val nsd = context.getSystemService(NsdManager::class.java)
        registration?.let { runCatching { nsd?.unregisterService(it) } }
        registration = null
        runCatching { socket?.close() }
        socket = null
    }

    /** Jams annoncées à proximité, mises à jour en continu tant qu'on collecte. */
    fun discover(context: Context): Flow<List<NearbyJam>> = callbackFlow {
        val app = context.applicationContext
        val nsd = app.getSystemService(NsdManager::class.java)
        if (nsd == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        // Sans ce verrou, beaucoup de téléphones filtrent les paquets de découverte.
        val lock = runCatching {
            app.getSystemService(WifiManager::class.java)?.createMulticastLock("reaper-jam")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        val found = linkedMapOf<String, NearbyJam>()
        val main = Handler(Looper.getMainLooper())
        // Android ne résout qu'un service à la fois sur les anciennes versions : on les traite en file.
        val toResolve = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            if (resolving) return
            val next = toResolve.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post { resolving = false; resolveNext() }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val code = serviceInfo.attributes["code"]?.toString(Charsets.UTF_8)
                    val host = serviceInfo.attributes["host"]?.toString(Charsets.UTF_8) ?: "Quelqu'un"
                    main.post {
                        if (code != null && code.length == 6) {
                            found[serviceInfo.serviceName] = NearbyJam(code, host)
                            trySend(found.values.toList())
                        }
                        resolving = false
                        resolveNext()
                    }
                }
            })
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                main.post { toResolve.addLast(serviceInfo); resolveNext() }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                main.post {
                    if (found.remove(serviceInfo.serviceName) != null) trySend(found.values.toList())
                }
            }
        }
        trySend(emptyList())
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(discovery) }
            runCatching { lock?.release() }
        }
    }
}
