package cl.alarmaiot

import android.app.Application
import cl.alarmaiot.data.ConfigStore
import cl.alarmaiot.data.DbHelper
import cl.alarmaiot.data.EventRepository
import cl.alarmaiot.data.SessionManager
import cl.alarmaiot.data.UserRepository
import cl.alarmaiot.mqtt.MqttManager
import cl.alarmaiot.ui.Notifier
import cl.alarmaiot.ui.PanelRecorder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Punto de entrada: crea una sola instancia de cada componente (base de datos, MQTT, sesión). */
class AlarmaApp : Application() {

    lateinit var db: DbHelper
        private set
    lateinit var users: UserRepository
        private set
    lateinit var events: EventRepository
        private set
    lateinit var config: ConfigStore
        private set
    lateinit var mqtt: MqttManager
        private set
    val session = SessionManager()

    /** Hilo único para la base de datos y la criptografía (nunca en el hilo de la UI). */
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        db = DbHelper(this)
        users = UserRepository(db)
        events = EventRepository(db)
        config = ConfigStore(this)
        mqtt = MqttManager(this)
        Notifier.createChannel(this)
        mqtt.addListener(PanelRecorder(this))
    }

    fun logout() {
        mqtt.disconnect()
        session.end()
    }
}
