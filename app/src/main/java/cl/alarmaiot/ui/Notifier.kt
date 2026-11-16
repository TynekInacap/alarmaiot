package cl.alarmaiot.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cl.alarmaiot.R

/** Notificación de alta prioridad cuando la Central reporta una intrusión. */
object Notifier {

    private const val CHANNEL_ID = "alertas_intrusion"
    private const val ALERT_ID = 133

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Alertas de intrusión", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Aviso inmediato cuando la alarma detecta una intrusión"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 800)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun intrusion(context: Context, text: String) {
        if (!canNotify(context)) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, PanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dial = PendingIntent.getActivity(
            context, 1,
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:133")),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("¡Intrusión detectada!")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setContentTitle("Alarma IoT: alerta")
                    .build()
            )
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_phone, "Llamar al 133", dial)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ALERT_ID, notification)
        } catch (_: SecurityException) {
        }
    }
}
