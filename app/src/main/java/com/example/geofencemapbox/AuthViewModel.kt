package com.example.geofencemapbox

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val firebaseAuth = FirebaseAuth.getInstance()
    private val firebaseDatabase = FirebaseDatabase.getInstance()

    private val _authResult = MutableLiveData<AuthResult>()
    val authResult: LiveData<AuthResult> = _authResult

    fun loginUser(email: String, password: String) {
        viewModelScope.launch {
            _authResult.value = AuthResult.Loading
            try {
                val authResult = firebaseAuth.signInWithEmailAndPassword(email, password).await()
                val user = authResult.user!!
                linkDeviceToUser(user.uid)
                _authResult.value = AuthResult.Success(user)
            } catch (e: FirebaseAuthInvalidUserException) {
                _authResult.value = AuthResult.Error("No se encontró un usuario con ese correo.")
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                _authResult.value = AuthResult.Error("La contraseña es incorrecta.")
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Login Error: ", e)
                val friendlyMessage = when {
                    e.message?.contains("expired") == true -> "Error de seguridad. Revisa la configuración de Firebase (SHA-1)."
                    else -> e.message ?: "Ocurrió un error desconocido."
                }
                _authResult.value = AuthResult.Error(friendlyMessage)
            }
        }
    }

    fun registerUser(email: String, password: String) {
        viewModelScope.launch {
            _authResult.value = AuthResult.Loading
            try {
                val authResult = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
                val user = authResult.user!!
                saveUserInDatabase(user)
                linkDeviceToUser(user.uid)
                _authResult.value = AuthResult.Success(user)
            } catch (e: FirebaseAuthUserCollisionException) {
                _authResult.value = AuthResult.Error("Este correo electrónico ya está en uso.")
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Register Error: ", e)
                _authResult.value = AuthResult.Error(e.message ?: "Ocurrió un error desconocido.")
            }
        }
    }

    private suspend fun saveUserInDatabase(user: FirebaseUser) {
        val userMap = mapOf(
            "email" to user.email,
            "createdAt" to System.currentTimeMillis()
        )
        firebaseDatabase.getReference("users").child(user.uid).setValue(userMap).await()
    }

    private suspend fun linkDeviceToUser(userId: String) {
        val deviceId = getOrCreateDeviceId()
        val deviceRef = firebaseDatabase.getReference("devices").child(deviceId)
        deviceRef.child("userId").setValue(userId).await()
    }

    private fun getOrCreateDeviceId(): String {
        val sharedPrefs = getApplication<Application>().getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        var deviceId = sharedPrefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            sharedPrefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }
}

sealed class AuthResult {
    data class Success(val user: FirebaseUser) : AuthResult()
    data class Error(val message: String) : AuthResult()
    object Loading : AuthResult()
}