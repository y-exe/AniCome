package net.yexe.anicome

import android.content.*
import android.net.*
import android.os.BatteryManager
import androidx.work.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal fun currentDownloadConditions(context: Context): DownloadConditions {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val network = manager.getNetworkCapabilities(manager.activeNetwork)
    val connected = network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return DownloadConditions(connected, manager.isActiveNetworkMetered, status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
}
internal fun downloadConditions(context: Context) = callbackFlow {
    fun update() { trySend(currentDownloadConditions(context)) }
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update()
        override fun onLost(network: Network) = update()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update()
    }
    val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) = update() }
    manager.registerDefaultNetworkCallback(callback)
    val filter = IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED); addAction(Intent.ACTION_BATTERY_CHANGED) }
    if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, filter)
    update()
    awaitClose { manager.unregisterNetworkCallback(callback); context.unregisterReceiver(receiver) }
}.distinctUntilChanged()

open class ReservationDispatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            org.koin.core.context.GlobalContext.get().get<DownloadRepository>().pumpReservations()
            val required = ReservationSpec(Episode("dispatch", 1), wifi = inputData.getBoolean("wifi", false), charging = inputData.getBoolean("charging", false))
            if (currentDownloadConditions(applicationContext).permits(required)) Result.success() else Result.retry()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { Result.retry() }
    }
}
