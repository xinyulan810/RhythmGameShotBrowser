package com.rhythm.shots

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.rhythm.shots.data.ShotBadge
import com.rhythm.shots.data.ShotItem
import com.rhythm.shots.data.badge
import com.rhythm.shots.databinding.ItemShotThumbBinding

/** 内层：某游戏截图横向缩略图行（滚动性能优化：固定采样尺寸 / 复用 drawable / 零对象分配） */
class ShotThumbAdapter : ListAdapter<ShotItem, ShotThumbAdapter.ThumbVH>(Diff) {

    var onClick: ((List<ShotItem>, Int) -> Unit)? = null
    var onLongClick: ((ShotItem) -> Unit)? = null

    companion object {
        /** 固定采样尺寸（px，≈130dp@2.5x+余量）：避免 ImageView 尚未布局时 Coil 按原图解码 */
        private const val THUMB_PX = 340
    }

    inner class ThumbVH(val binding: ItemShotThumbBinding) : RecyclerView.ViewHolder(binding.root) {
        // 角标背景只创建一次，滚动时复用（避免每帧 new GradientDrawable）
        private val fcBg = GradientDrawable().apply {
            cornerRadius = 6 * binding.root.resources.displayMetrics.density
            setColor(0xFF1E88E5.toInt())
        }
        private val apBg = GradientDrawable().apply {
            cornerRadius = 6 * binding.root.resources.displayMetrics.density
            setColor(0xFFF4511E.toInt())
        }

        fun bind(item: ShotItem) {
            binding.thumbImage.load(item.path) {
                size(THUMB_PX, THUMB_PX)
            }
            val song = if (item.songName.isNotBlank()) item.songName
            else if (item.songCn.isNotBlank()) item.songCn
            else ""
            if (song.isNotBlank()) {
                binding.songText.text = song
                binding.songText.visibility = android.view.View.VISIBLE
            } else {
                binding.songText.visibility = android.view.View.GONE
            }
            binding.dateText.text = formatDate(item.date)
            // FC / AP 角标
            val badge = item.badge()
            if (badge != ShotBadge.NONE) {
                binding.badgeText.text = badge.label
                binding.badgeText.visibility = android.view.View.VISIBLE
                binding.badgeText.background = if (badge == ShotBadge.AP) apBg else fcBg
            } else {
                binding.badgeText.visibility = android.view.View.GONE
            }
            binding.root.setOnClickListener {
                onClick?.invoke(currentList, bindingAdapterPosition)
            }
            binding.root.setOnLongClickListener {
                onLongClick?.invoke(item)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbVH {
        val b = ItemShotThumbBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ThumbVH(b)
    }

    override fun onBindViewHolder(holder: ThumbVH, position: Int) {
        holder.bind(getItem(position))
    }

    /** 纯字符串格式化，避免 SimpleDateFormat 反复创建 */
    private fun formatDate(yyyyMMdd: String): String =
        if (yyyyMMdd.length == 8) {
            yyyyMMdd.substring(0, 4) + "-" + yyyyMMdd.substring(4, 6) + "-" + yyyyMMdd.substring(6, 8)
        } else yyyyMMdd

    object Diff : DiffUtil.ItemCallback<ShotItem>() {
        override fun areItemsTheSame(oldItem: ShotItem, newItem: ShotItem) = oldItem.path == newItem.path
        override fun areContentsTheSame(oldItem: ShotItem, newItem: ShotItem) = oldItem == newItem
    }
}
