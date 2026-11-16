package cl.alarmaiot

import cl.alarmaiot.security.SecureChannel
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

/**
 * Prueba de integración contra el broker real (requiere Internet).
 * Se ejecuta con:  MQTT_IT=1 ./gradlew testDebugUnitTest --tests "*MqttIntegrationTest*"
 *
 * Simula los dos teléfonos: el "Panel" publica un comando cifrado y la
 * "Central" lo recibe por TLS, lo descifra y responde con su estado.
 */
class MqttIntegrationTest {

    private val broker = System.getenv("MQTT_BROKER") ?: "ssl://broker.hivemq.com:8883"

    private fun client(id: String): MqttAsyncClient {
        val c = MqttAsyncClient(broker, id, MemoryPersistence())
        val opts = MqttConnectOptions().apply {
            isCleanSession = true
            connectionTimeout = 20
            socketFactory = SSLSocketFactory.getDefault()
            isHttpsHostnameVerificationEnabled = true
        }
        c.connect(opts).waitForCompletion(25_000)
        return c
    }

    @Test
    fun panel_y_central_se_comunican_cifrado_por_tls() {
        assumeTrue("Definir MQTT_IT=1 para ejecutar", System.getenv("MQTT_IT") == "1")

        val home = "it-" + UUID.randomUUID().toString().take(8)
        val base = "alarmaiot/v1/$home"
        val key = SecureChannel("Prueba2026", home)
        val central = client("it-central-$home")
        val panel = client("it-panel-$home")
        val atCentral = LinkedBlockingQueue<Pair<String, String>>()
        val atPanel = LinkedBlockingQueue<Pair<String, String>>()

        try {
            central.subscribe("$base/cmd", 1) { t, m -> atCentral.put(t to String(m.payload)) }.waitForCompletion(10_000)
            panel.subscribe("$base/state", 1) { t, m -> atPanel.put(t to String(m.payload)) }.waitForCompletion(10_000)

            // Panel -> Central: comando A cifrado
            val cmd = JSONObject().put("cmd", "A").put("user", "admin").put("role", "ADMIN")
                .put("ts", System.currentTimeMillis()).put("nonce", "n1")
            val start = System.nanoTime()
            panel.publish("$base/cmd", key.seal(cmd.toString(), "$base/cmd").toByteArray(), 1, false)

            val (topic, payload) = atCentral.poll(15, TimeUnit.SECONDS) ?: error("La central no recibió el comando")
            val received = JSONObject(key.open(payload, topic) ?: error("No se pudo descifrar"))
            assertEquals("A", received.getString("cmd"))
            // Un tercero con otro código no puede leerlo
            assertNull(SecureChannel("OtroCodigo9", home).open(payload, topic))

            // Central -> Panel: estado ARMADA (retenido)
            val state = JSONObject().put("state", "ARMADA").put("ts", System.currentTimeMillis()).put("nonce", "n2")
            central.publish("$base/state", key.seal(state.toString(), "$base/state").toByteArray(), 1, true)
            val (t2, p2) = atPanel.poll(15, TimeUnit.SECONDS) ?: error("El panel no recibió el estado")
            assertEquals("ARMADA", JSONObject(key.open(p2, t2)!!).getString("state"))
            val ms = (System.nanoTime() - start) / 1_000_000
            println("Ida y vuelta Panel → Central → Panel por TLS: $ms ms")

            central.publish("$base/state", ByteArray(0), 1, true) // limpia el retenido
        } finally {
            listOf(central, panel).forEach {
                try { it.disconnect().waitForCompletion(5_000); it.close() } catch (_: Exception) {}
            }
        }
    }
}
