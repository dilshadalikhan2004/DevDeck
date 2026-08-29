package com.devdeck.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.devdeck.app.databinding.ItemReceiptCardBinding
import com.devdeck.app.model.ContextReceiptItem

class ReceiptListAdapter(
    private var items: List<ContextReceiptItem> = emptyList()
) : RecyclerView.Adapter<ReceiptListAdapter.ReceiptViewHolder>() {

    inner class ReceiptViewHolder(private val binding: ItemReceiptCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ContextReceiptItem) {
            binding.receiptFileName.text = item.file
            binding.receiptTokenCount.text = "${item.estimatedTokens} TOK"
            binding.receiptLines.text = "LINES ${item.lineStart}-${item.lineEnd}"
            
            binding.receiptReasons.text = item.reasons.joinToString("\n") { "• $it" }
            
            if (item.symbols.isNotEmpty()) {
                binding.symbolsLayout.visibility = View.VISIBLE
                binding.receiptSymbols.text = item.symbols.joinToString(", ")
            } else {
                binding.symbolsLayout.visibility = View.GONE
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReceiptViewHolder {
        val binding = ItemReceiptCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ReceiptViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ReceiptViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<ContextReceiptItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
