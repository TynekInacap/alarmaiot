package cl.alarmaiot.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import cl.alarmaiot.R
import cl.alarmaiot.alarm.AlarmState
import cl.alarmaiot.data.EventCategory
import cl.alarmaiot.mqtt.MqttManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Muestra el código de recuperación UNA sola vez. La app solo guarda su hash,
 * así que después no se puede volver a ver.
 */
fun AppCompatActivity.showRecoveryCode(code: String, owner: String, onDone: () -> Unit) {
    val pad = (20 * resources.displayMetrics.density).toInt()
    val codeView = android.widget.TextView(this).apply {
        text = code
        textSize = 26f
        typeface = android.graphics.Typeface.MONOSPACE
        setTextIsSelectable(true)
        gravity = android.view.Gravity.CENTER
        setPadding(pad, pad / 2, pad, 0)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("Código de recuperación de $owner")
        .setMessage(
            "Anótalo y guárdalo en un lugar seguro. Es la única forma de recuperar la contraseña " +
                "si se olvida. No se volverá a mostrar."
        )
        .setView(codeView)
        .setCancelable(false)
        .setPositiveButton("Ya lo anoté") { _, _ -> onDone() }
        .show()
}

/** Configura la pantalla de borde a borde (Android 15+) y la barra superior. */
fun AppCompatActivity.setupScreen(root: View, toolbar: MaterialToolbar?, title: CharSequence?, back: Boolean = true) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
        )
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
    toolbar?.let { tb ->
        title?.let { tb.title = it }
        if (back) {
            tb.setNavigationIcon(R.drawable.ic_arrow_back)
            tb.setNavigationContentDescription("Volver")
            tb.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
    }
}

fun Context.stateColor(state: AlarmState?): Int = ContextCompat.getColor(
    this,
    when (state) {
        AlarmState.DESARMADA -> R.color.estado_desarmada
        AlarmState.ARMADA -> R.color.estado_armada
        AlarmState.ALERTA -> R.color.estado_alerta
        null -> R.color.estado_desconocido
    }
)

fun Context.categoryColor(category: EventCategory): Int = ContextCompat.getColor(
    this,
    when (category) {
        EventCategory.ALERTA -> R.color.estado_alerta
        EventCategory.COMANDO -> R.color.brand
        EventCategory.ESTADO -> R.color.estado_desarmada
        EventCategory.SEGURIDAD -> R.color.estado_armada
        EventCategory.CONEXION -> R.color.estado_desconocido
    }
)

fun Chip.showLink(link: MqttManager.Link, detail: String = "") {
    text = if (link == MqttManager.Link.ERROR && detail.isNotBlank()) "Error: $detail" else "Broker: ${link.label}"
    val color = when (link) {
        MqttManager.Link.CONECTADO -> R.color.estado_desarmada
        MqttManager.Link.CONECTANDO -> R.color.estado_armada
        MqttManager.Link.ERROR -> R.color.estado_alerta
        MqttManager.Link.DESCONECTADO -> R.color.estado_desconocido
    }
    chipIcon = ContextCompat.getDrawable(context, R.drawable.led_circle)
    chipIconTint = ColorStateList.valueOf(ContextCompat.getColor(context, color))
    chipIconSize = 24f
}

fun formatDistance(cm: Float?): String = when {
    cm == null -> "— cm"
    cm >= 100f -> "≥ 100 cm (lejos)"
    else -> "%.0f cm".format(Locale.US, cm)
}

private val timeFormat = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
private val shortTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

fun formatTime(ts: Long): String = synchronized(timeFormat) { timeFormat.format(Date(ts)) }
fun formatShortTime(ts: Long): String = synchronized(shortTime) { shortTime.format(Date(ts)) }

fun View.tint(color: Int) {
    backgroundTintList = ColorStateList.valueOf(color)
}
