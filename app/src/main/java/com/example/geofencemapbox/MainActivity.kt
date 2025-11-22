package com.example.geofencemapbox

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.plugin.locationcomponent.location
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    private lateinit var mapView: MapView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private var lastLocationToSend: Pair<Double, Double>? = null

    private lateinit var database: FirebaseDatabase
    private lateinit var geofencesRef: DatabaseReference
    private lateinit var deviceRef: DatabaseReference
    private lateinit var alertsRef: DatabaseReference

    // El ID del dispositivo ahora se obtiene de SharedPreferences
    private lateinit var deviceId: String

    private val geofences = mutableMapOf<String, GeofenceModel>()
    private var wasInsideAnyGeofence: Boolean = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkLocationSettingsAndStartUpdates()
        } else {
            Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_LONG).show()
        }
    }

    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "El GPS es necesario para continuar", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)

        // Obtenemos el ID único del dispositivo
        deviceId = getOrCreateDeviceId()

        val frameLayout = FrameLayout(this)
        val mapInitOptions = MapInitOptions(this, textureView = true)
        mapView = MapView(this, mapInitOptions)
        frameLayout.addView(mapView)

        // Botón de Enviar Ubicación
        val sendLocationButton = Button(this).apply {
            text = "Enviar Ubicación"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 100
            }
        }
        frameLayout.addView(sendLocationButton)

        // Botón de Cerrar Sesión
        val logoutButton = Button(this).apply {
            text = "Cerrar Sesión"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 20
                rightMargin = 20
            }
        }
        frameLayout.addView(logoutButton)

        setContentView(frameLayout)

        sendLocationButton.setOnClickListener {
            Toast.makeText(this, "Enviando ubicación...", Toast.LENGTH_SHORT).show()
            sendCurrentLocationToFirebase()
        }

        logoutButton.setOnClickListener {
            logout()
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        database = FirebaseDatabase.getInstance()
        geofencesRef = database.getReference("geofences")
        // La referencia al dispositivo ahora usa el ID real
        deviceRef = database.getReference("devices").child(deviceId)
        alertsRef = database.getReference("alerts")

        setupFirebaseListeners()
        checkLocationPermission()
    }

    private fun logout() {
        FirebaseAuth.getInstance().signOut()
        // Detenemos las actualizaciones de ubicación al cerrar sesión
        fusedLocationClient.removeLocationUpdates(locationCallback!!)

        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun getOrCreateDeviceId(): String {
        val sharedPrefs = getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        var storedId = sharedPrefs.getString("device_id", null)
        if (storedId == null) {
            storedId = UUID.randomUUID().toString()
            sharedPrefs.edit().putString("device_id", storedId).apply()
        }
        return storedId
    }

    // ... (El resto de las funciones como checkLocationPermission, startLocationUpdates, etc., permanecen igual)

    private fun checkLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "Permiso ya concedido. Comprobando GPS...")
                checkLocationSettingsAndStartUpdates()
            }
            else -> {
                Log.d(TAG, "Permiso no concedido. Solicitando...")
                requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun checkLocationSettingsAndStartUpdates() {
        val locationRequest = LocationRequest.create().apply {
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }

        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        val client: SettingsClient = LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            Log.d(TAG, "Configuración de GPS es correcta. Iniciando actualizaciones.")
            startLocationUpdates()
        }

        task.addOnFailureListener { exception ->
            Log.w(TAG, "Configuración de GPS es incorrecta.")
            if (exception is ResolvableApiException) {
                try {
                    val isr = IntentSenderRequest.Builder(exception.resolution).build()
                    locationSettingsLauncher.launch(isr)
                } catch (sendEx: IntentSender.SendIntentException) {
                    Log.e(TAG, "Error al mostrar diálogo de GPS", sendEx)
                }
            } else {
                Toast.makeText(this, "No se puede activar el GPS automáticamente", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 5000
            fastestInterval = 2000
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                Log.d(TAG, "Nueva ubicación recibida!")
                val loc = result.lastLocation ?: return
                handleNewLocation(loc)
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback!!, Looper.getMainLooper()
        )
    }

    private fun handleNewLocation(location: Location) {
        val lat = location.latitude
        val lng = location.longitude

        showLocationOnMap(location)
        checkGeofenceTransitions(lat, lng)
        lastLocationToSend = Pair(lat, lng)

        sendCurrentLocationToFirebase()
    }

    private fun sendCurrentLocationToFirebase() {
        val loc = lastLocationToSend
        if (loc != null) {
            val (lat, lng) = loc
            val status = if (isInsideAnyGeofence(lat, lng)) "inside" else "outside"

            val updates = mapOf(
                "deviceId" to deviceId, "lat" to lat, "lng" to lng, "status" to status
            )
            deviceRef.updateChildren(updates) { error, _ ->
                if (error == null) {
                    Log.d(TAG, "Device update sent: $lat, $lng - status: $status")
                } else {
                    Log.w(TAG, "Update failed: ${error.message}")
                }
            }
        } else {
            Log.d(TAG, "No location available yet for sending")
        }
    }

    private fun isInsideAnyGeofence(lat: Double, lng: Double): Boolean {
        val now = System.currentTimeMillis()
        for (gf in geofences.values) {
            if (!gf.enabled) continue
            if (gf.start != null && gf.end != null) {
                if (now < gf.start || now > gf.end) continue
            }
            if (gf.polygon.isEmpty()) continue
            if (pointInPolygon(listOf(lng, lat), gf.polygon)) {
                return true
            }
        }
        return false
    }

    private fun checkGeofenceTransitions(lat: Double, lng: Double) {
        val isInside = isInsideAnyGeofence(lat, lng)
        if (wasInsideAnyGeofence && !isInside) {
            sendExitAlert(lat, lng)
        }
        wasInsideAnyGeofence = isInside
    }

    private fun sendExitAlert(lat: Double, lng: Double) {
        val timestamp = System.currentTimeMillis()
        val alertMap: MutableMap<String, Any?> = HashMap()
        alertMap["deviceId"] = deviceId
        alertMap["lat"] = lat
        alertMap["lng"] = lng
        alertMap["timestamp"] = timestamp
        alertMap["type"] = "exit"

        alertsRef.child(deviceId).child(timestamp.toString()).setValue(alertMap)
        val updates = mapOf("status" to "outside", "lat" to lat, "lng" to lng)
        deviceRef.updateChildren(updates)
    }

    private fun showLocationOnMap(location: Location) {
        mapView.mapboxMap.setCamera(
            CameraOptions.Builder().center(Point.fromLngLat(location.longitude, location.latitude))
                .zoom(14.0).build()
        )
        mapView.location.apply { enabled = true; pulsingEnabled = true }
    }

    private fun pointInPolygon(point: List<Double>, polygon: List<List<Double>>): Boolean {
        val x = point[0]
        val y = point[1]
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i][0]; val yi = polygon[i][1]
            val xj = polygon[j][0]; val yj = polygon[j][1]
            val intersect = ((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi + 0.0) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    private fun setupFirebaseListeners() { /* ... */ }
    data class GeofenceModel(val id: String, val enabled: Boolean, val start: Long?, val end: Long?, val polygon: List<List<Double>>)
}
