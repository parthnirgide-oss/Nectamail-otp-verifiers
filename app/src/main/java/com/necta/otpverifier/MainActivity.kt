package com.necta.otpverifier

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.telephony.SubscriptionManager
import android.util.Base64
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.material.card.MaterialCardView
import com.google.firebase.messaging.FirebaseMessaging
import com.necta.otpverifier.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val client = OkHttpClient()
    private val baseUrl = "https://nectamail-otp-backend.vercel.app"
    private val prefs by lazy { getSharedPreferences("necta_vault_prefs", Context.MODE_PRIVATE) }

    // Step 1 Launcher: Google Phone Number Hint API
    private val phoneHintLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            val rawPhone = Identity.getSignInClient(this).getPhoneNumberFromIntent(result.data)
            val digits = rawPhone.replace(Regex("\\D"), "")
            if (digits.length >= 10) {
                bindHardwareSim(digits, "Google Phone Number Hint")
            } else {
                startStep2SubscriptionFallback()
            }
        } catch (e: Exception) {
            // Step 1 unavailable or declined -> Immediately fall back to Step 2
            startStep2SubscriptionFallback()
        }
    }

    // Step 2 Permission Launcher: READ_PHONE_NUMBERS for SubscriptionManager
    private val readPhonePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            readSimFromSubscriptionManager()
        } else {
            binding.tvStatus.text = "Blocked: Hardware SIM verification is mandatory. No manual entry allowed."
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.btnVerifySim.setOnClickListener { startStep1PhoneHint() }
        binding.swipeRefresh.setOnRefreshListener { refreshInboxAndHistory() }
        binding.btnCopyOtp.setOnClickListener {
            val code = binding.tvActiveOtp.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Necta OTP", code))
            Toast.makeText(this, "Copied $code", Toast.LENGTH_SHORT).show()
        }

        val savedPhone = prefs.getString("verified_phone", null)
        if (savedPhone != null) {
            binding.tvSimNumber.text = "+$savedPhone"
            binding.tvStatus.text = "Hardware SIM Locked • Pull down to refresh"
            refreshInboxAndHistory()
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getString("verified_phone", null) != null) {
            refreshInboxAndHistory()
        }
    }

    // STEP 1: Google Phone Number Hint API (Zero permissions)
    private fun startStep1PhoneHint() {
        binding.tvStatus.text = "Checking Google Play Services SIM Hint..."
        val request = GetPhoneNumberHintIntentRequest.builder().build()
        Identity.getSignInClient(this)
            .getPhoneNumberHintIntent(request)
            .addOnSuccessListener { pendingIntent ->
                phoneHintLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            }
            .addOnFailureListener {
                startStep2SubscriptionFallback()
            }
    }

    // STEP 2: SubscriptionManager Direct SIM Fallback
    private fun startStep2SubscriptionFallback() {
        binding.tvStatus.text = "Falling back to direct SIM slot verification..."
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            readSimFromSubscriptionManager()
        } else {
            readPhonePermLauncher.launch(Manifest.permission.READ_PHONE_NUMBERS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readSimFromSubscriptionManager() {
        try {
            val subManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeList = subManager.activeSubscriptionInfoList.orEmpty()
            var detectedDigits = ""

            for (info in activeList) {
                val num = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    subManager.getPhoneNumber(info.subscriptionId)
                } else {
                    @Suppress("DEPRECATION")
                    info.number
                }
                val clean = (num ?: "").replace(Regex("\\D"), "")
                if (clean.length >= 10) {
                    detectedDigits = if (clean.length == 10) "91$clean" else clean
                    break
                }
            }

            if (detectedDigits.length >= 10) {
                bindHardwareSim(detectedDigits, "SubscriptionManager Hardware SIM")
            } else {
                // HARD STOP: Neither Step 1 nor Step 2 found a SIM number
                binding.tvStatus.text =
                    "Blocked: No active SIM number readable on this device. Registration denied."
            }
        } catch (e: Exception) {
            binding.tvStatus.text = "SIM Read Error: ${e.message}"
        }
    }

    private fun getOrCreateHardwarePublicKey(): String {
        val alias = "necta_device_ec_key"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(alias)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            kpg.initialize(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            kpg.generateKeyPair()
        }
        val pubKeyBytes = keyStore.getCertificate(alias).publicKey.encoded
        return Base64.encodeToString(pubKeyBytes, Base64.NO_WRAP)
    }

    private fun bindHardwareSim(phoneDigits: String, method: String) {
        binding.tvStatus.text = "Binding SIM via $method..."
        lifecycleScope.launch {
            try {
                val fcmToken = FirebaseMessaging.getInstance().token.await()
                val pubKey = getOrCreateHardwarePublicKey()

                val json = JSONObject().apply {
                    put("phoneNumber", phoneDigits)
                    put("fcmToken", fcmToken)
                    put("publicKey", pubKey)
                    put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
                }

                val responseBody = withContext(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url("$baseUrl/api/device/bind")
                        .post(json.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(req).execute().body?.string().orEmpty()
                }

                val resJson = JSONObject(responseBody)
                if (resJson.optBoolean("success")) {
                    prefs.edit().putString("verified_phone", phoneDigits).apply()
                    binding.tvSimNumber.text = "+$phoneDigits"
                    binding.tvStatus.text = "Bound & Protected ($method)"
                    refreshInboxAndHistory()
                } else {
                    binding.tvStatus.text = "Bind Failed: ${resJson.optString("error")}"
                }
            } catch (e: Exception) {
                binding.tvStatus.text = "Network Error: ${e.message}"
            }
        }
    }

    private fun refreshInboxAndHistory() {
        val phone = prefs.getString("verified_phone", null) ?: run {
            binding.swipeRefresh.isRefreshing = false
            return
        }

        lifecycleScope.launch {
            try {
                val responseBody = withContext(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url("$baseUrl/api/otp/history/$phone")
                        .get()
                        .build()
                    client.newCall(req).execute().body?.string().orEmpty()
                }

                val resJson = JSONObject(responseBody)
                if (!resJson.optBoolean("success")) return@launch

                val history = resJson.getJSONArray("history")
                binding.historyContainer.removeAllViews()
                var foundPending = false
                val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

                for (i in 0 until history.length()) {
                    val item = history.getJSONObject(i)
                    val status = item.getString("status")
                    val otp = item.getString("otp")
                    val ip = item.getString("requesterIp")
                    val created = item.optLong("createdAt", 0L)

                    if (!foundPending && status == "PENDING") {
                        foundPending = true
                        binding.cardActiveOtp.visibility = View.VISIBLE
                        binding.tvActiveOtp.text = otp
                        binding.tvActiveIp.text = "Requester IP: $ip"
                    }

                    val card = MaterialCardView(this@MainActivity).apply {
                        radius = 28f
                        strokeWidth = 2
                        val lp = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        lp.bottomMargin = 20
                        layoutParams = lp
                    }

                    val tv = TextView(this@MainActivity).apply {
                        setPadding(36, 28, 36, 28)
                        textSize = 13f
                        val timeStr = if (created > 0) sdf.format(Date(created)) else ""
                        text = "Code: $otp   [$status]\nRequester IP: $ip\nTime: $timeStr"
                    }
                    card.addView(tv)
                    binding.historyContainer.addView(card)
                }

                if (!foundPending) {
                    binding.cardActiveOtp.visibility = View.GONE
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Sync failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }
}
