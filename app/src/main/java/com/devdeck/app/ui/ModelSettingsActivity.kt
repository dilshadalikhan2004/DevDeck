package com.devdeck.app.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.devdeck.app.databinding.ActivityModelSettingsBinding
import com.devdeck.app.model.ModelConfig
import com.devdeck.app.model.ModelManager
import kotlinx.coroutines.launch

class ModelSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelSettingsBinding
    private lateinit var modelManager: ModelManager
    private lateinit var adapter: ModelListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelManager = ModelManager(this)

        setupRecyclerView()
        setupCustomPathButton()

        binding.backButton.setOnClickListener { finish() }
    }

    private fun catalogModels(): List<ModelConfig> =
        modelManager.getPredefinedModels().filter { it.id != "custom" || it.isActive }

    private fun setupRecyclerView() {
        val models = catalogModels()

        adapter = ModelListAdapter(
            models = models,
            onModelSelected = { model -> selectModel(model) },
            onVerifyClicked = { model -> verifyModel(model) }
        )

        binding.modelRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.modelRecyclerView.adapter = adapter
    }

    private fun selectModel(model: ModelConfig) {
        if (!modelManager.isModelAvailable(model.filePath)) {
            AlertDialog.Builder(this)
                .setTitle("Install ${model.displayName}")
                .setMessage(
                    "File size about ${model.sizeGB} GB. This app does not download multi-gigabyte models over the air.\n\n" +
                        "1. On the laptop, copy the MediaPipe .bin next to the other models.\n" +
                        "2. adb push <file> ${model.filePath}\n" +
                        "3. Tap Check again.\n\nCurrent path:\n${model.filePath}"
                )
                .setPositiveButton("Check again") { _, _ ->
                    if (modelManager.isModelAvailable(model.filePath)) {
                        activateAfterVerify(model)
                    } else {
                        Toast.makeText(this, "Still missing at ${model.filePath}", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        activateAfterVerify(model)
    }

    private fun activateAfterVerify(model: ModelConfig) {
        val previous = modelManager.getCurrentModelPath()
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val (success, tps, error) = modelManager.verifyModel(model.filePath)
            binding.progressBar.visibility = View.GONE
            if (success) {
                modelManager.setModelPath(model.filePath, model.displayName)
                adapter.updateModels(catalogModels())
                Toast.makeText(
                    this@ModelSettingsActivity,
                    "Active: ${model.displayName} (~${tps.toInt()} tok/s). Restart diagnoses to use it.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                modelManager.setModelPath(previous)
                AlertDialog.Builder(this@ModelSettingsActivity)
                    .setTitle("Load failed — kept previous model")
                    .setMessage(error ?: "The new file did not initialize. Active model was not changed.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun verifyModel(model: ModelConfig) {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val (success, tps, error) = modelManager.verifyModel(model.filePath)

            binding.progressBar.visibility = View.GONE

            if (success) {
                AlertDialog.Builder(this@ModelSettingsActivity)
                    .setTitle("✅ Model Verified")
                    .setMessage("${model.displayName}\n\nSpeed: ${tps.toInt()} tokens/sec\nStatus: Operational")
                    .setPositiveButton("Use This Model") { _, _ -> selectModel(model) }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                AlertDialog.Builder(this@ModelSettingsActivity)
                    .setTitle("❌ Verification Failed")
                    .setMessage(error ?: "Unknown error initializing or running model.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun setupCustomPathButton() {
        binding.customPathButton.setOnClickListener {
            val input = EditText(this).apply {
                hint = "/data/local/tmp/my-model.bin"
                setText(modelManager.getCurrentModelPath())
            }

            AlertDialog.Builder(this)
                .setTitle("Custom Model Path")
                .setMessage("Enter on-device path to MediaPipe-compatible .bin model:")
                .setView(input)
                .setPositiveButton("Set") { _, _ ->
                    val path = input.text.toString().trim()
                    if (path.isNotEmpty()) {
                        modelManager.setModelPath(path, com.devdeck.app.model.ModelDisplayNames.fromPath(path))
                        Toast.makeText(this, "Custom model path updated", Toast.LENGTH_SHORT).show()
                        adapter.updateModels(catalogModels())
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
