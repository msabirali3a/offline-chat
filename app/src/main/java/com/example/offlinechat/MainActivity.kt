package com.example.offlinechat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject
import java.util.UUID

/*
 * OfflineChat: internet ke baghair chat (Bluetooth + Wi-Fi Direct)
 *
 * Kaise kaam karti hai:
 * 1. Har phone apne aap ko "advertise" karta hai (main yahan hun)
 *    aur saath hi doosre phones ko "discover" karta hai (kaun aas paas hai).
 * 2. Phone milte hi khud connect ho jate hain.
 * 3. Message aane par phone usay dikhata hai AUR apne baqi jude phones ko
 *    aage bhej deta hai. Isi ko "mesh" kehte hain: A -> B -> C.
 * 4. Har message ki ek unique ID hai, taake ek hi message baar baar
 *    ghoomta na rahe.
 */
class MainActivity : AppCompatActivity() {

    // Sab phones par ye naam bilkul same hona chahiye, warna woh ek doosre ko nahi dhoondenge
    private val serviceId = "com.example.offlinechat.MESH"

    // P2P_CLUSTER: har phone kai phones se jud sakta hai (mesh ke liye zaroori)
    private val strategy = Strategy.P2P_CLUSTER

    // Message zyada se zyada kitne phones se "hop" kar ke aage jaye
    private val maxHops = 5

    private lateinit var client: ConnectionsClient
    private var myName = ""
    private var started = false

    private val connectedPhones = mutableMapOf<String, String>() // endpointId -> naam
    private val pendingNames = mutableMapOf<String, String>()
    private val seenMessageIds = mutableSetOf<String>()
    private val messages = mutableListOf<String>()

    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var statusText: TextView
    private lateinit var nameInput: EditText
    private lateinit var startButton: Button
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button

    // Permissions maangne ka nizaam
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            startMesh()
        } else {
            toast("Permissions ke baghair app kaam nahi karegi. Settings mein ja kar allow karein.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        nameInput = findViewById(R.id.nameInput)
        startButton = findViewById(R.id.startButton)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, messages)
        findViewById<ListView>(R.id.messageList).adapter = adapter

        client = Nearby.getConnectionsClient(this)
        updateStatus()

        startButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                toast("Pehle apna naam likhein")
                return@setOnClickListener
            }
            myName = name
            if (hasAllPermissions()) startMesh()
            else permissionLauncher.launch(requiredPermissions())
        }

        sendButton.setOnClickListener { sendMyMessage() }
    }

    // ---------------- Permissions ----------------

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            list += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return list.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    // ---------------- Network shuru karna ----------------

    private fun startMesh() {
        if (started) return
        started = true
        nameInput.isEnabled = false
        startButton.isEnabled = false

        val advOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(myName, serviceId, connectionCallback, advOptions)
            .addOnFailureListener { addLine("⚠ Advertising error: ${it.message}") }

        val discOptions = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(serviceId, discoveryCallback, discOptions)
            .addOnFailureListener { addLine("⚠ Discovery error: ${it.message}") }

        addLine("✅ Shuru ho gaya. Aas paas ke phones dhoond rahe hain...")
        updateStatus()
    }

    // Jab koi phone mile
    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (connectedPhones.containsKey(endpointId)) return
            // Dono phones ek doosre ko dhoondte hain. Takraav se bachne ke liye
            // sirf woh phone request bhejta hai jis ka naam alphabet mein pehle aata hai.
            if (myName <= info.endpointName) {
                client.requestConnection(myName, endpointId, connectionCallback)
                    .addOnFailureListener { /* aksar "pehle se juda hua" error, nazar andaz */ }
            }
        }

        override fun onEndpointLost(endpointId: String) {}
    }

    // Connection ka poora safar: request -> accept -> juda / toota
    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pendingNames[endpointId] = info.endpointName
            // Note: abhi har connection khud accept ho raha hai (seekhne ke liye theek hai)
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val name = pendingNames.remove(endpointId) ?: "?"
            if (resolution.status.isSuccess) {
                connectedPhones[endpointId] = name
                addLine("🔗 $name se jud gaye")
            }
            updateStatus()
        }

        override fun onDisconnected(endpointId: String) {
            val name = connectedPhones.remove(endpointId)
            if (name != null) addLine("❌ $name se rabta toot gaya")
            updateStatus()
        }
    }

    // ---------------- Messages ----------------

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val json = try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                return
            }

            val id = json.optString("id")
            // Agar ye message pehle dekh chuke hain to chhor do (loop se bachao)
            if (id.isEmpty() || !seenMessageIds.add(id)) return

            addLine("${json.optString("from")}: ${json.optString("text")}")

            // Mesh: message ko baqi phones tak aage bhejo
            val hops = json.optInt("hops", 0) + 1
            if (hops < maxHops) {
                json.put("hops", hops)
                sendToAll(json, except = endpointId)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun sendMyMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        if (!started) {
            toast("Pehle 'Shuru Karo' dabayein")
            return
        }

        val id = UUID.randomUUID().toString()
        seenMessageIds.add(id)
        val json = JSONObject()
            .put("id", id)
            .put("from", myName)
            .put("text", text)
            .put("hops", 0)

        sendToAll(json, except = null)
        addLine("Main: $text")
        messageInput.setText("")

        if (connectedPhones.isEmpty()) {
            toast("Abhi koi phone juda nahi, message kisi tak nahi gaya")
        }
    }

    private fun sendToAll(json: JSONObject, except: String?) {
        val targets = connectedPhones.keys.filter { it != except }
        if (targets.isEmpty()) return
        val payload = Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))
        client.sendPayload(targets, payload)
    }

    // ---------------- Chhote helpers ----------------

    private fun addLine(line: String) {
        messages.add(line)
        adapter.notifyDataSetChanged()
    }

    private fun updateStatus() {
        statusText.text = if (!started) {
            "Status: band hai. Naam likh kar 'Shuru Karo' dabayein."
        } else {
            val names = connectedPhones.values.joinToString(", ").ifEmpty { "koi nahi" }
            "Aap: $myName  |  Jude hue (${connectedPhones.size}): $names"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        if (started) {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        }
    }
}
