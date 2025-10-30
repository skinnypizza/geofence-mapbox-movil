package com.example.geofencemapbox

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.firebase.FirebaseApp
import com.google.firebase.database.*
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.plugin.locationcomponent.location

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    private lateinit var mapView: MapView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    // Ubicación
    private var lastLocationToSend: Pair<Double, Double>? = null

    // Envío periódico de ubicación
    private val locationSendIntervalMs = 2 * 60 * 1000L // 2 minutos
    private val sendHandler = android.os.Handler(Looper.getMainLooper())
    private val sendRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "Ejecutando envío periódico...")
            sendCurrentLocationToFirebase()
            sendHandler.postDelayed(this, locationSendIntervalMs)
        }
    }

    // Firebase
    private lateinit var database: FirebaseDatabase
    private lateinit var geofencesRef: DatabaseReference
    private lateinit var deviceRef: DatabaseReference
    private lateinit var alertsRef: DatabaseReference

    private val deviceId = "device_001"

    // Guarda polígonos parseados: id -> GeofenceModel
    private val geofences = mutableMapOf<String, GeofenceModel>()

    // Estado previo para detectar transiciones
    private var wasInsideAnyGeofence: Boolean = false

    // Lanzador para la solicitud de permisos
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseApp.initializeApp(this)

        // 1. Crear el Layout principal
        val frameLayout = FrameLayout(this)

        // 2. Configurar y añadir el MapView
        val mapInitOptions = MapInitOptions(this, textureView = true)
        mapView = MapView(this, mapInitOptions)
        frameLayout.addView(mapView)

        // 3. Crear y añadir el Botón
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

        // 4. Establecer el layout como el contenido de la actividad
        setContentView(frameLayout)

        // 5. Configurar el listener del botón
        sendLocationButton.setOnClickListener {
            Toast.makeText(this, "Enviando ubicación...", Toast.LENGTH_SHORT).show()
            sendCurrentLocationToFirebase()
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Inicializar Firebase
        database = FirebaseDatabase.getInstance()
        geofencesRef = database.getReference("geofences")
        deviceRef = database.getReference("device")
        alertsRef = database.getReference("alerts")

        // Leer estado inicial del nodo device
        deviceRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java)
                wasInsideAnyGeofence = status == "inside"
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "deviceRef read cancelled: ${error.message}")
            }
        })

        // Escuchar geofences en la DB
        geofencesRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                geofences.clear()
                for (child in snapshot.children) {
                    val id = child.key ?: continue
                    val enabled = child.child("enabled").getValue(Boolean::class.java) ?: false
                    val start = child.child("start").getValue(Long::class.java)
                    val end = child.child("end").getValue(Long::class.java)

                    val coords = parseCoordinates(child)

                    geofences[id] = GeofenceModel(
                        id = id,
                        enabled = enabled,
                        start = start,
                        end = end,
                        polygon = coords
                    )
                }
                Log.d(TAG, "Loaded geofences: ${geofences.keys}")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "geofencesRef cancelled: ${error.message}")
            }
        })

        checkLocationPermissionAndGetLocation()
    }

    /**
     * Parsear coordenadas de GeoJSON Polygon
     * Estructura: geometry.coordinates[0] = exterior ring
     * Cada punto es [lng, lat]
     */
    private fun parseCoordinates(child: DataSnapshot): List<List<Double>> {
        val coords = mutableListOf<List<Double>>()
        try {
            // geometry.coordinates[0] es el exterior ring
            val coordsArray = child.child("geometry").child("coordinates").child("0")
            for (pointSnap in coordsArray.children) {
                // Cada punto puede ser un array o valores numéricos
                if (pointSnap.hasChildren()) {
                    val lng = pointSnap.child("0").getValue(Double::class.java)
                    val lat = pointSnap.child("1").getValue(Double::class.java)
                    if (lng != null && lat != null) {
                        coords.add(listOf(lng, lat))
                    }
                } else {
                    // Intentar como lista directa
                    val raw = pointSnap.getValue()
                    if (raw is List<*>) {
                        val lng = (raw.getOrNull(0) as? Number)?.toDouble()
                        val lat = (raw.getOrNull(1) as? Number)?.toDouble()
                        if (lng != null && lat != null) {
                            coords.add(listOf(lng, lat))
                        }
                    }
                }
            }
            Log.d(TAG, "Parsed ${coords.size} coordinates for polygon")
        } catch (e: Exception) {
            Log.w(TAG, "Error parseando coordinates: ${e.message}")
        }
        return coords
    }

    private fun sendCurrentLocationToFirebase() {
        val loc = lastLocationToSend
        if (loc != null) {
            val (lat, lng) = loc
            val status = if (isInsideAnyGeofence(lat, lng)) "inside" else "outside"

            // Usar update en lugar de setValue para no sobrescribir otros campos
            val updates = mapOf(
                "deviceId" to deviceId,
                "lat" to lat,
                "lng" to lng,
                "status" to status
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
            Toast.makeText(this, "Ubicación no disponible todavía", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkLocationPermissionAndGetLocation() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                startLocationUpdates()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                handleNewLocation(location)
            } else {
                Toast.makeText(this, "No se pudo obtener la ubicación inicial", Toast.LENGTH_LONG)
                    .show()
            }
        }

        val locationRequest = LocationRequest.create().apply {
            interval = 5000
            fastestInterval = 2000
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                handleNewLocation(loc)
            }
        }
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )

        startPeriodicLocationSending()
    }

    private fun handleNewLocation(location: Location) {
        val lat = location.latitude
        val lng = location.longitude

        showLocationOnMap(location)
        checkGeofenceTransitions(lat, lng)
        lastLocationToSend = Pair(lat, lng)
    }

    private fun startPeriodicLocationSending() {
        sendHandler.removeCallbacks(sendRunnable)
        sendHandler.post(sendRunnable)
    }

    private fun stopPeriodicLocationSending() {
        sendHandler.removeCallbacks(sendRunnable)
    }

    private fun checkGeofenceTransitions(lat: Double, lng: Double) {
        val isInside = isInsideAnyGeofence(lat, lng)

        if (wasInsideAnyGeofence && !isInside) {
            sendExitAlert(lat, lng)
        }
        wasInsideAnyGeofence = isInside
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

    private fun sendExitAlert(lat: Double, lng: Double) {
        val timestamp = System.currentTimeMillis()
        val alertMap: MutableMap<String, Any?> = HashMap()
        alertMap["deviceId"] = deviceId
        alertMap["lat"] = lat
        alertMap["lng"] = lng
        alertMap["timestamp"] = timestamp
        alertMap["type"] = "exit"

        alertsRef.child(deviceId).child(timestamp.toString()).setValue(alertMap) { error, _ ->
            if (error == null) {
                Log.i(TAG, "Alert saved: $timestamp")
            } else {
                Log.w(TAG, "Failed to save alert: ${error.message}")
            }
        }

        // Actualizar estado sin perder otros campos
        val updates = mapOf(
            "status" to "outside",
            "lat" to lat,
            "lng" to lng
        )
        deviceRef.updateChildren(updates)
    }

    private fun showLocationOnMap(location: Location) {
        mapView.mapboxMap.setCamera(
            CameraOptions.Builder()
                .center(Point.fromLngLat(location.longitude, location.latitude))
                .zoom(14.0)
                .build()
        )

        mapView.location.apply {
            enabled = true
            pulsingEnabled = true
        }
    }

    private fun pointInPolygon(point: List<Double>, polygon: List<List<Double>>): Boolean {
        val x = point[0]
        val y = point[1]
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i][0]
            val yi = polygon[i][1]
            val xj = polygon[j][0]
            val yj = polygon[j][1]
            val intersect = ((yi > y) != (yj > y)) &&
                    (x < (xj - xi) * (y - yi) / (yj - yi + 0.0) + xi)
            if (intersect) inside = !inside
        }
        return inside
    }

    data class GeofenceModel(
        val id: String,
        val enabled: Boolean,
        val start: Long?,
        val end: Long?,
        val polygon: List<List<Double>>
    )
}