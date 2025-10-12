package com.indrek.slopper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.Observer
import com.indrek.slopper.databinding.ActivityAutofillSettingsBinding
import com.indrek.slopper.service.FloatingToolService
import com.indrek.slopper.service.ScreenCaptureService

class AutofillSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAutofillSettingsBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>

    companion object {
        const val TAG = "AutofillSettings"
        const val PREFS_NAME = "slopper_prefs"
        const val KEY_TOOL_ENABLED = "tool_enabled"
        const val KEY_SELECTED_MODEL = "selected_model"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutofillSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        setupModelSpinner()

        screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startScreenCaptureService(result.resultCode, result.data!!)
            } else {
                Toast.makeText(this, "Screen capture permission is required.", Toast.LENGTH_SHORT).show()
            }
        }

        overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                // User granted permission. Now save the desired state and start the service.
                prefs.edit { putBoolean(KEY_TOOL_ENABLED, true) }
                startFloatingToolService()
            } else {
                Toast.makeText(this, "Overlay permission is required.", Toast.LENGTH_SHORT).show()
                // Ensure the preference and switch reflect the failure.
                prefs.edit { putBoolean(KEY_TOOL_ENABLED, false) }
                binding.swEnableTool.isChecked = false
            }
        }

        binding.btnOpenAutofillSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }

        binding.btnStartCapture.setOnClickListener {
            if (ScreenCaptureService.isServiceRunning.value == true) {
                stopScreenCaptureService()
            } else {
                requestScreenCapturePermission()
            }
        }

        // The listener now only expresses the user's INTENT. The UI update is handled by the observer.
        binding.swEnableTool.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (isChecked) {
                prefs.edit { putBoolean(KEY_TOOL_ENABLED, true) }
                requestOverlayPermission()
            } else {
                prefs.edit { putBoolean(KEY_TOOL_ENABLED, false) }
                stopFloatingToolService()
                stopScreenCaptureService()
            }
        }

        // Observe BOTH services to get a complete and accurate picture of the app's state.
        FloatingToolService.isServiceRunning.observe(this, Observer { updateUiStates() })
        ScreenCaptureService.isServiceRunning.observe(this, Observer { updateUiStates() })
    }

    override fun onResume() {
        super.onResume()
        // Always refresh the UI when returning to the screen to ensure it's in sync.
        updateUiStates()
    }

    private fun updateUiStates() {
        // The UI state is now driven by the services' actual running status, not SharedPreferences.
        val isToolServiceRunning = FloatingToolService.isServiceRunning.value ?: false
        val isCaptureServiceRunning = ScreenCaptureService.isServiceRunning.value ?: false

        // Temporarily detach the listener to prevent feedback loops when we programmatically update the switch.
        binding.swEnableTool.setOnCheckedChangeListener(null)
        binding.swEnableTool.isChecked = isToolServiceRunning
        binding.swEnableTool.setOnCheckedChangeListener { _, isChecked ->
            // Re-attach the listener logic after the programmatic update is complete.
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (isChecked) {
                prefs.edit { putBoolean(KEY_TOOL_ENABLED, true) }
                requestOverlayPermission()
            } else {
                prefs.edit { putBoolean(KEY_TOOL_ENABLED, false) }
                stopFloatingToolService()
                stopScreenCaptureService()
            }
        }

        // Update the capture button's visibility and text based on the actual service states.
        binding.btnStartCapture.visibility = if (isToolServiceRunning) View.VISIBLE else View.GONE
        binding.btnStartCapture.text = if (isCaptureServiceRunning) "Stop Capture Session" else "Start Capture Session"
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            overlayPermissionLauncher.launch(intent)
        } else {
            // Permission was already granted, so we can just start the service.
            startFloatingToolService()
        }
    }

    private fun requestScreenCapturePermission() {
        val screenCaptureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(screenCaptureIntent)
    }

    private fun startFloatingToolService() {
        startService(Intent(this, FloatingToolService::class.java))
        Toast.makeText(this, "Floating tool enabled!", Toast.LENGTH_SHORT).show()
    }

    private fun stopFloatingToolService() {
        stopService(Intent(this, FloatingToolService::class.java))
        Toast.makeText(this, "Floating tool disabled.", Toast.LENGTH_SHORT).show()
    }

    private fun startScreenCaptureService(resultCode: Int, data: Intent) {
        startForegroundService(ScreenCaptureService.getStartIntent(this, resultCode, data))
        Toast.makeText(this, "Capture session started!", Toast.LENGTH_SHORT).show()
    }

    private fun stopScreenCaptureService() {
        if (ScreenCaptureService.isServiceRunning.value == true) {
            stopService(Intent(this, ScreenCaptureService::class.java))
            Toast.makeText(this, "Capture session stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupModelSpinner() {
        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modelDisplayNames = Model.values().map { model ->
            val locationLabel = when (model.location) {
                ModelLocation.ASSETS -> "📦"
                ModelLocation.EXTERNAL -> "📁"
            }
            "$locationLabel ${model.displayName}"
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelDisplayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerModelSelection.adapter = adapter

        val savedModelName = sharedPrefs.getString(KEY_SELECTED_MODEL, Model.GEMMA_2B.name)
        val savedModel = try { Model.valueOf(savedModelName ?: Model.GEMMA_2B.name) } catch (e: Exception) { Model.GEMMA_2B }
        val selectionPosition = Model.values().indexOf(savedModel)
        binding.spinnerModelSelection.setSelection(selectionPosition)

        binding.spinnerModelSelection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedModel = Model.values()[position]
                sharedPrefs.edit {
                    putString(KEY_SELECTED_MODEL, selectedModel.name)
                    apply()
                }

                val locationInfo = when (selectedModel.location) {
                    ModelLocation.ASSETS -> "from app assets"
                    ModelLocation.EXTERNAL -> "from external storage"
                }
                Toast.makeText(
                    this@AutofillSettingsActivity,
                    "Model set to: ${selectedModel.displayName} ($locationInfo)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}