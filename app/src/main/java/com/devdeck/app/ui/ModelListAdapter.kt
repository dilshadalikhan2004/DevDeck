package com.devdeck.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.devdeck.app.databinding.ItemModelCardBinding
import com.devdeck.app.model.ModelConfig
import com.devdeck.app.model.ModelTier

class ModelListAdapter(
    private var models: List<ModelConfig>,
    private val onModelSelected: (ModelConfig) -> Unit,
    private val onVerifyClicked: (ModelConfig) -> Unit
) : RecyclerView.Adapter<ModelListAdapter.ModelViewHolder>() {

    inner class ModelViewHolder(private val binding: ItemModelCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(model: ModelConfig) {
            binding.modelName.text = model.displayName
            binding.modelDescription.text = model.description
            binding.activeBadge.isVisible = model.isActive
            binding.recommendationLabel.isVisible = !model.recommendation.isNullOrBlank()
            binding.recommendationLabel.text = model.recommendation ?: ""
            binding.availabilityLabel.text = when {
                model.isAvailable -> "On this phone"
                else -> "Not installed — adb push required"
            }

            // Tier badge
            binding.tierBadge.text = model.tier.name
            binding.tierBadge.setBackgroundColor(
                when (model.tier) {
                    ModelTier.FAST -> android.graphics.Color.parseColor("#0B8A78")
                    ModelTier.ADVANCED -> android.graphics.Color.parseColor("#3B6FD1")
                }
            )

            // Metadata line
            if (model.id == "custom") {
                binding.modelMeta.text = "Custom path"
            } else {
                binding.modelMeta.text = "${model.sizeGB}GB • ${model.estimatedTPS.toInt()} tok/s • ${model.specialty}"
            }

            // Button clicks
            binding.btnSelect.setOnClickListener { onModelSelected(model) }
            binding.btnVerify.setOnClickListener { onVerifyClicked(model) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val binding = ItemModelCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ModelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        holder.bind(models[position])
    }

    override fun getItemCount() = models.size

    fun updateModels(newModels: List<ModelConfig>) {
        models = newModels
        notifyDataSetChanged()
    }
}
