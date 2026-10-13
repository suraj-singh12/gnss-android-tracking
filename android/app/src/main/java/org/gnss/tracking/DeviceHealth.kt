package org.gnss.tracking

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.*
import android.net.wifi.WifiInfo
import android.os.BatteryManager

class DeviceHealth(private val context: Context, private val latest: LatestLocation) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun networkState(changes: Long): NetworkState {
        val wifi = wifiNetwork()
        val capabilities = wifi?.let { connectivity.getNetworkCapabilities(it) }
        val links = wifi?.let { connectivity.getLinkProperties(it) }
        return NetworkState(
            runCatching {
                    context.getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled
                }
                .getOrNull(),
            wifi != null,
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            links?.linkAddresses?.any { it.address is java.net.Inet4Address },
            links?.linkAddresses?.any { it.address is java.net.Inet6Address },
            changes,
        )
    }

    // Select Wi-Fi even if it has no internet validation or cellular is the default.
    fun wifiNetwork(): Network? =
        connectivity.allNetworks.firstOrNull {
            connectivity
                .getNetworkCapabilities(it)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

    fun snapshot(): Health {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent =
            if (level >= 0 && scale > 0) (level * 100 / scale).takeIf { it in 0..100 } else null
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging =
            when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL -> true
                BatteryManager.BATTERY_STATUS_DISCHARGING,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                else -> null
            }
        val wifi = wifiNetwork()
        val info =
            if (android.os.Build.VERSION.SDK_INT >= 29)
                wifi?.let { connectivity.getNetworkCapabilities(it)?.transportInfo as? WifiInfo }
            else null
        val rssi = info?.rssi?.takeIf { it in -126..0 } // -127 is the Android unavailable sentinel.
        return Health(
            batteryPercent,
            charging,
            wifi != null,
            rssi,
            latest.status(),
            latest.satellites,
        )
    }
}
