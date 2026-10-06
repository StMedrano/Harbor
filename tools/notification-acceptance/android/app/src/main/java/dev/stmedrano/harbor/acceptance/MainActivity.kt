package dev.stmedrano.harbor.acceptance

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var evidence: TextView
    private lateinit var scroll: ScrollView
    private var feedback = ""
    private val executor = Executors.newSingleThreadExecutor()
    private val buttons = mutableListOf<Button>()
    private fun runtime() = AcceptanceRuntime.get(this)
    private fun render() {
        if (BuildConfig.CI_FIXTURE) { status.text = "CI fixture build. Install a build with your matching development Firebase configuration for live acceptance."; return }
        val permission = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val paired = runtime().identity.binding != null
        val registered = runtime().registration.confirmedToken != null
        status.text = "Notifications allowed: $permission. Paired: $paired. Backend FCM registration confirmed: $registered."
        if (feedback.isNotEmpty()) status.append("\n$feedback")
        evidence.text = runtime().receipts.receipts().joinToString("\n\n").ifEmpty { "No receipt evidence yet." }
    }
    private fun showFeedback(message: String) {
        if (isDestroyed) return
        feedback = message
        render()
        scroll.post { scroll.smoothScrollTo(0, 0) }
    }
    private fun network(work: () -> String) {
        if (BuildConfig.CI_FIXTURE) { render(); return }
        buttons.forEach { it.isEnabled = false }
        showFeedback("Working…")
        executor.execute {
            val result = try { work() } catch (failure: HarborFailure) { failure.message ?: "Request failed" } catch (_: Exception) { "Request failed. Check configuration/session and retry. After operator cleanup, use Reset enrollment before pairing again." }
            runOnUiThread { if (!isDestroyed) { showFeedback(result); buttons.forEach { it.isEnabled = true } } }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        layout.addView(TextView(this).apply { text = "Harbor development notification acceptance"; textSize = 22f })
        status = TextView(this); layout.addView(status)
        val code = EditText(this).apply { hint = "Fresh six-digit pairing code"; inputType = InputType.TYPE_CLASS_NUMBER; isSaveEnabled = false }
        layout.addView(code)
        fun button(label: String, action: () -> Unit) {
            val view = Button(this).apply { text = label; setOnClickListener { action() } }
            buttons.add(view); layout.addView(view)
        }
        button("Allow notifications") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),1)
            else render()
        }
        button("Pair test device") {
            val pairing = code.text.toString(); code.setText("")
            network { runtime().claim(pairing); "Device claimed. Register FCM next." }
        }
        button("Get current FCM token") {
            if (!BuildConfig.CI_FIXTURE) {
                requestFcmToken({ success, failure ->
                    FirebaseApp.initializeApp(this)
                    FirebaseMessaging.getInstance().isAutoInitEnabled = true
                    FirebaseMessaging.getInstance().token.addOnSuccessListener { success(it) }
                        .addOnFailureListener { failure() }
                }, { runtime().onToken(it) }, ::showFeedback)
            } else render()
        }
        button("Register FCM / retry") {
            network {
                when (runtime().registration.registerCurrentToken()) {
                    RegistrationResult.Registered -> "Backend registration confirmed. Actual receipt still requires delivery evidence."
                    RegistrationResult.NeedsBinding -> "Pair this test device first. Token remains pending."
                    RegistrationResult.RetryableFailure -> "Registration unconfirmed. Obtain a current token, then retry."
                }
            }
        }
        button("Verify signed sync") {
            network { val result = JSONObject(runtime().sync()); "Signed sync verified. Desired-state version: ${result.getLong("desiredStateVersion")}." }
        }
        button("Verify signed FCM denial") {
            network {runtime().verifySignedRegistration(); "HTTP 204. Registration accepted; revoked-device denial is not verified."}
        }
        button("Reset enrollment after cleanup") {
            if (!BuildConfig.CI_FIXTURE) AlertDialog.Builder(this)
                .setTitle("Reset local enrollment?")
                .setMessage("Checkpoint the device and anonymous Auth binding with the operator and complete its cleanup first. This clears the old session, binding and registration confirmation. Receipt evidence stays available.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Cleanup done; reset") { _,_ -> network {runtime().resetEnrollment(); "Enrollment reset. Enter a fresh pairing code to create a new anonymous identity."} }
                .show()
        }
        button("Refresh receipt evidence") { render() }
        evidence = TextView(this); layout.addView(evidence)
        scroll = ScrollView(this).apply {
            addView(layout)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                    view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
        render()
    }
    override fun onResume() { super.onResume(); if (::status.isInitialized) render() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
        if (requestCode == 1 && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) showFeedback("Notification permission refused. Enable it in Android app settings before the delivery check.")
    }
    override fun onDestroy() { executor.shutdown(); super.onDestroy() }
}
