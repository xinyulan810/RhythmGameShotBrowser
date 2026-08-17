package com.rhythm.shots

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rhythm.shots.data.ShotItem
import com.rhythm.shots.databinding.ItemGameSectionBinding

/**
 * 外层：游戏分组列表。每个 section = header + 横向缩略图行。
 * 性能优化：所有内层横向列表共享 RecycledViewPool + 关闭嵌套滚动/动画。
 */
class ShotSectionAdapter : ListAdapter<Pair<String, List<ShotItem>>, ShotSectionAdapter.SectionVH>(Diff) {

    var onShotClick: ((List<ShotItem>, Int) -> Unit)? = null
    var onShotLongClick: ((ShotItem) -> Unit)? = null

    /** 所有内层横向列表共享 ViewHolder 池，section 滚出/滚入时复用缩略图项 */
    private val sharedPool = RecyclerView.RecycledViewPool()

    inner class SectionVH(val binding: ItemGameSectionBinding) : RecyclerView.ViewHolder(binding.root) {
        private val thumbAdapter = ShotThumbAdapter()

        init {
            binding.thumbList.apply {
                layoutManager =
                    LinearLayoutManager(binding.root.context, RecyclerView.HORIZONTAL, false)
                adapter = thumbAdapter
                // 横向行不参与垂直嵌套滚动，也不需要增删动画，减少滚动开销
                isNestedScrollingEnabled = false
                itemAnimator = null
                setHasFixedSize(true)
                setRecycledViewPool(sharedPool)
            }
            thumbAdapter.onClick = { shots, index -> onShotClick?.invoke(shots, index) }
            thumbAdapter.onLongClick = { item -> onShotLongClick?.invoke(item) }
        }

        fun bind(game: String, shots: List<ShotItem>) {
            binding.sectionHeader.text = "$game（${shots.size}）"
            thumbAdapter.submitList(shots)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionVH {
        val b = ItemGameSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SectionVH(b)
    }

    override fun onBindViewHolder(holder: SectionVH, position: Int) {
        val (game, shots) = getItem(position)
        holder.bind(game, shots)
    }

    object Diff : DiffUtil.ItemCallback<Pair<String, List<ShotItem>>>() {
        override fun areItemsTheSame(
            oldItem: Pair<String, List<ShotItem>>,
            newItem: Pair<String, List<ShotItem>>
        ) = oldItem.first == newItem.first

        override fun areContentsTheSame(
            oldItem: Pair<String, List<ShotItem>>,
            newItem: Pair<String, List<ShotItem>>
        ) = oldItem == newItem
    }
}
