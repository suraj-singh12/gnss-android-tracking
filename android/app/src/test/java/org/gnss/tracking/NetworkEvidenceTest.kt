package org.gnss.tracking

import android.app.Application
import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NetworkEvidenceTest {
    @Test
    fun offlineRouterDoesNotNeedInternetValidationAndRadioIsSeparate() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val wifi = app.getSystemService(WifiManager::class.java)
        shadowOf(cm).clearAllNetworks()
        wifi.setWifiEnabled(true)
        val health = DeviceHealth(app, LatestLocation(FakeClock()))
        assertTrue(health.networkState(0).radioEnabled!!)
        assertFalse(health.networkState(0).routeAvailable!!)
        val network = ShadowNetwork.newInstance(42)
        shadowOf(cm)
            .addNetwork(
                network,
                ShadowNetworkInfo.newInstance(
                    NetworkInfo.DetailedState.CONNECTED,
                    ConnectivityManager.TYPE_WIFI,
                    0,
                    true,
                    true,
                ),
            )
        shadowOf(cm)
            .setNetworkCapabilities(
                network,
                shadowOf(org.robolectric.shadows.ShadowNetworkCapabilities.newInstance())
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI),
            )
        val links = LinkProperties()
        val address =
            org.robolectric.util.ReflectionHelpers.callConstructor(
                LinkAddress::class.java,
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(
                    java.net.InetAddress::class.java,
                    java.net.InetAddress.getByName("192.168.1.2"),
                ),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(
                    Int::class.javaPrimitiveType!!,
                    24,
                ),
            )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Boolean>(
            links,
            "addLinkAddress",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(
                LinkAddress::class.java,
                address,
            ),
        )
        shadowOf(cm).setLinkProperties(network, links)
        assertEquals(network, health.wifiNetwork())
        val state = health.networkState(1)
        assertTrue(state.routeAvailable!!)
        assertFalse(state.internetValidated!!)
        assertTrue(state.ipv4Available!!)
        assertFalse(state.ipv6Available!!)
        assertFalse(com.google.gson.Gson().toJson(state).contains("192.168.1.2"))
        shadowOf(cm).removeNetwork(network)
        assertNull(health.wifiNetwork())
        assertTrue(health.networkState(2).radioEnabled!!)
    }

    @Test
    fun networkBindingInvalidationIsNonblockingAndRetainsGeneration() = runBlocking {
        val transport = LanTransport { null }
        repeat(100) { transport.invalidate() }
        val field =
            LanTransport::class.java.getDeclaredField("generation").apply { isAccessible = true }
        assertEquals(100L, (field.get(transport) as java.util.concurrent.atomic.AtomicLong).get())
        try {
            transport.post("http://localhost", "immutable")
            fail("missing Wi-Fi should not fall back")
        } catch (_: java.io.IOException) {}
    }
}
