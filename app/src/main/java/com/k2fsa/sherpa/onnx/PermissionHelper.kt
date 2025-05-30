package com.k2fsa.sherpa.onnx

import android.app.Activity
import android.content.pm.PackageManager
import android.util.SparseArray
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionHelper {
    private var currentRequestCode = 1000
    private val callbacks = SparseArray<(Boolean) -> Unit>()

    fun requestPermissions(
        activity: Activity,
        permissions: Array<String>,
        callback: (Boolean) -> Unit
    ) {
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            callback(true)
            return
        }

        val requestCode = currentRequestCode++
        callbacks.put(requestCode, callback)
        ActivityCompat.requestPermissions(activity, notGranted.toTypedArray(), requestCode)
    }

    fun handlePermissionsResult(
        requestCode: Int,
        grantResults: IntArray
    ) {
        val callback = callbacks[requestCode] ?: return
        val granted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        callback(granted)
        callbacks.remove(requestCode)
    }
}
