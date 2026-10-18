// Sustituye tu MainActivity.kt (deja tu línea "package ..." arriba).
//
// AÑADE AL AndroidManifest.xml:
//  1) Encima de <application>:
//     <uses-permission android:name="android.permission.INTERNET" />
//     <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
//     <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
//     <uses-permission android:name="android.permission.VIBRATE" />
//  2) Dentro de <application> (junto a <activity>):
//     <receiver android:name=".RestReceiver" android:exported="false" />
//
// Requiere minSdk 29.
import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

const val CH_SV = "descanso_sv" // sonido + vibración
const val CH_V = "descanso_v"   // solo vibración (silencioso)

class RestReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (MainActivity.fg) return // si la app está abierta ya avisa la propia web
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val silent = i.getStringExtra("m") == "v"
        val n = NotificationCompat.Builder(c, if (silent) CH_V else CH_SV)
            .setSmallIcon(c.applicationInfo.icon)
            .setContentTitle("¡Descanso terminado!")
            .setContentText("Siguiente serie 💪")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(c).notify(1, n)
    }
}

class MainActivity : AppCompatActivity() {

    companion object { @Volatile var fg = false }

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val picker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        fileCallback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
        )
        fileCallback = null
    }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private fun restIntent(mode: String = "vs") = PendingIntent.getBroadcast(
        this, 1, Intent(this, RestReceiver::class.java).putExtra("m", mode),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    inner class Bridge {
        @JavascriptInterface
        fun save(name: String, mime: String, b64: String) {
            try {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/Forja")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                contentResolver.openOutputStream(uri)!!.use {
                    it.write(Base64.decode(b64, Base64.DEFAULT))
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Guardado en Descargas/Forja: $name", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "No se pudo guardar", Toast.LENGTH_LONG).show()
                }
            }
        }

        // La web avisa cuando empieza un descanso (segundos restantes)
        @JavascriptInterface
        fun rest(seconds: Int, mode: String) {
            val am = getSystemService(AlarmManager::class.java)
            val at = System.currentTimeMillis() + seconds * 1000L
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, restIntent(mode))
            else
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, restIntent(mode))
        }

        @JavascriptInterface
        fun restCancel() {
            getSystemService(AlarmManager::class.java).cancel(restIntent())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT // siempre en vertical
        web = findViewById(R.id.webview)
        web.keepScreenOn = true

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SV, "Descanso (sonido y vibración)", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_V, "Descanso (solo vibración)", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 100, 250)
            }
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            mediaPlaybackRequiresUserGesture = false // permite el pitido del aviso
        }
        web.addJavascriptInterface(Bridge(), "Android")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                loader.shouldInterceptRequest(request.url)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                picker.launch(params.createIntent())
                return true
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Atrás: la web decide (cierra ventana, vuelve a Inicio...). Si no hay nada que hacer, sale.
                web.evaluateJavascript("window.appBack?window.appBack():false") { r ->
                    if (r != "true") finish()
                }
            }
        })
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onResume() { super.onResume(); fg = true }
    override fun onPause() { super.onPause(); fg = false }
}
