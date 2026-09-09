package com.vgc.earscope

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.IOException
import java.io.OutputStream

private val DarkPurpleBg = Color(0xFF271E2B)
private val OrangeButton = Color(0xFFFD8618)
private val ErrorRed = Color(0xFFE53935)
private val CardSurface = Color(0xFF382A3E)

class MainActivity : ComponentActivity() {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerWifiBinding()

        setContent {
            MaterialTheme {
                EarScopeScreen()
            }
        }
    }

    private fun registerWifiBinding() {
        val cm = getSystemService(ConnectivityManager::class.java)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                Log.i("MainActivity", "Bound process to Wi-Fi: $network")
            }

            override fun onLost(network: Network) {
                cm.bindProcessToNetwork(null)
                Log.i("MainActivity", "Unbound process from Wi-Fi.")
            }
        }

        networkCallback?.let {
            cm.registerNetworkCallback(request, it)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
    }
}

@Composable
fun EarScopeScreen() {
    val context = LocalContext.current
    var ipText by remember { mutableStateOf("192.168.10.123") }
    var stream by remember { mutableStateOf<CameraStream?>(null) }
    var rotationAngle by remember { mutableFloatStateOf(0f) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val state by stream?.state?.collectAsState() ?: remember { mutableStateOf(StreamState.STOPPED) }
    val errorMsg by stream?.errorMessage?.collectAsState() ?: remember { mutableStateOf("") }
    val frame by stream?.latestFrame?.collectAsState() ?: remember { mutableStateOf(null) }
    val fps by stream?.fps?.collectAsState() ?: remember { mutableIntStateOf(0) }
    val battery by stream?.batteryLevel?.collectAsState() ?: remember { mutableIntStateOf(-1) }
    val isLowBattery by stream?.isLowBattery?.collectAsState() ?: remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            stream?.stop()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkPurpleBg)
            .systemBarsPadding()
    ) {
        // Control Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = ipText,
                onValueChange = {
                    ipText = it
                    if (validationError != null) validationError = null
                },
                label = { Text("Camera IP", color = Color.White) },
                singleLine = true,
                isError = validationError != null,
                supportingText = {
                    validationError?.let {
                        Text(it, color = ErrorRed, fontSize = 12.sp)
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = state == StreamState.STOPPED,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = OrangeButton,
                    unfocusedBorderColor = Color.LightGray,
                    disabledTextColor = Color.LightGray,
                    disabledBorderColor = Color.DarkGray,
                    cursorColor = OrangeButton,
                    errorBorderColor = ErrorRed,
                    errorLabelColor = ErrorRed
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (state == StreamState.STOPPED) {
                        val trimmedIp = ipText.trim()
                        if (validateIpAddress(trimmedIp)) {
                            validationError = null
                            stream = CameraStream(trimmedIp).apply { start() }
                        } else {
                            validationError = "Invalid IPv4 format (e.g. 192.168.10.123)"
                        }
                    } else {
                        stream?.stop()
                        stream = null
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = OrangeButton,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = if (state == StreamState.STOPPED) "Connect" else "Disconnect",
                    color = Color.White
                )
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                enabled = state == StreamState.STREAMING && frame != null,
                onClick = {
                    frame?.let { saveSnapshot(context, it) }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = OrangeButton,
                    contentColor = Color.White,
                    disabledContainerColor = Color.DarkGray,
                    disabledContentColor = Color.LightGray
                )
            ) {
                Text("Save Snapshot", color = Color.White)
            }

            Button(
                onClick = { rotationAngle = (rotationAngle + 90f) % 360f },
                colors = ButtonDefaults.buttonColors(
                    containerColor = OrangeButton,
                    contentColor = Color.White
                )
            ) {
                Text("Rotate ↻", color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Video Surface / Message Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .background(Color.Black, shape = RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            val currentFrame = frame
            if (currentFrame != null && state == StreamState.STREAMING) {
                Image(
                    bitmap = currentFrame.asImageBitmap(),
                    contentDescription = "Camera Feed",
                    modifier = Modifier
                        .fillMaxSize()
                        .rotate(rotationAngle)
                )
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        when (state) {
                            StreamState.CONNECTING -> {
                                CircularProgressIndicator(
                                    color = OrangeButton,
                                    modifier = Modifier.size(36.dp),
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Connecting to $ipText...",
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Establishing UDP socket and initiating stream trigger.",
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                            StreamState.STALLED -> {
                                Text(
                                    text = "⚠️ Signal Interrupted",
                                    color = OrangeButton,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No frame received for over 2 seconds. The app is automatically retrying keepalive packets.",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                            StreamState.ERROR -> {
                                Text(
                                    text = "❌ Network Error",
                                    color = ErrorRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = errorMsg.ifBlank { "An unexpected network communication error occurred." },
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Retrying in the background...",
                                    color = Color.LightGray,
                                    fontSize = 11.sp
                                )
                            }
                            else -> {
                                Text(
                                    text = "Camera Disconnected",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "1. Connect your phone to the camera Wi-Fi network.\n2. Tap Connect above.",
                                    color = Color.LightGray,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Status Footer: [State] ------ [Battery Status] ------ [FPS]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val statusColor = when (state) {
                StreamState.STREAMING -> Color(0xFF2BB673)
                StreamState.CONNECTING, StreamState.STALLED -> Color(0xFFFD8618)
                StreamState.ERROR -> ErrorRed
                StreamState.STOPPED -> Color.LightGray
            }

            Text(
                text = "State: $state",
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )

            val (batteryText, batteryColor) = when {
                state != StreamState.STREAMING -> "🔋 --" to Color.LightGray
                isLowBattery -> "⚠️ LOW BATTERY" to ErrorRed
                battery >= 70 -> "🔋 Good" to Color(0xFF2BB673)
                battery >= 30 -> "🔋 Normal" to Color(0xFFFD8618)
                else -> "🔋 OK" to Color(0xFF2BB673)
            }

            Text(
                text = batteryText,
                color = batteryColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )

            Text(
                text = if (state == StreamState.STREAMING) "$fps fps" else "",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
        }
    }
}

private fun validateIpAddress(ip: String): Boolean {
    val parts = ip.split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        val numeric = part.toIntOrNull() ?: return false
        numeric in 0..255 && (part == "0" || !part.startsWith("0"))
    }
}

fun saveSnapshot(context: Context, bitmap: Bitmap) {
    val filename = "earscope_${System.currentTimeMillis()}.jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    try {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore entry.")

        val out: OutputStream? = resolver.openOutputStream(uri)
        out?.use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                throw IOException("JPEG compression failed.")
            }
        } ?: throw IOException("Failed to open storage output stream.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        Toast.makeText(context, "Saved snapshot to Photos", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to save snapshot: ${e.message}", e)
        Toast.makeText(context, "Failed to save snapshot: ${e.localizedMessage ?: "Storage error"}", Toast.LENGTH_LONG).show()
    }
}