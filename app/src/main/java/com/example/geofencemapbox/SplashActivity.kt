package com.example.geofencemapbox

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Usamos un Handler para dar una pequeña pausa y decidir a dónde navegar.
        Handler(Looper.getMainLooper()).postDelayed({
            checkUserSession()
        }, 500) // 0.5 segundos de retraso
    }

    private fun checkUserSession() {
        val firebaseAuth = FirebaseAuth.getInstance()
        if (firebaseAuth.currentUser != null) {
            // Usuario ya logueado, vamos a la MainActivity
            goToActivity(MainActivity::class.java)
        } else {
            // No hay sesión activa, vamos al LoginActivity
            goToActivity(LoginActivity::class.java)
        }
    }

    private fun <T> goToActivity(activityClass: Class<T>) {
        val intent = Intent(this, activityClass)
        startActivity(intent)
        finish() // Finalizamos SplashActivity para que no se pueda volver a ella
    }
}
