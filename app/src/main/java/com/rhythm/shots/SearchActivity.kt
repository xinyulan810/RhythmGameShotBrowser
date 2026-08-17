package com.rhythm.shots

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.rhythm.shots.data.ShotBadge
import com.rhythm.shots.data.ShotDb
import com.rhythm.shots.data.ShotItem
import com.rhythm.shots.data.badge
import com.rhythm.shots.databinding.ActivitySearchBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var db: ShotDb
    private lateinit var deleter: ShotDeleter
    private val adapter = SearchAdapter()
    private var lastQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = ShotDb(this)
        deleter = ShotDeleter(this)

        binding.resultList.layoutManager = LinearLayoutManager(this)
        binding.resultList.adapter = adapter

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(binding.searchInput.text?.toString()?.trim().orEmpty())
                true
            } else false
        }

        // 实时搜索：输入即搜（防抖 300ms）
        var searchJob: Job? = null
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = MainScope().launch {
                    delay(300)
                    doSearch(s?.toString()?.trim().orEmpty())
                }
            }
        })

        adapter.onClick = { shots, index ->
            startActivity(
                Intent(this, ViewerActivity::class.java)
                    .putStringArrayListExtra(ViewerActivity.EXTRA_PATHS, ArrayList(shots.map { it.path }))
                    .putExtra(ViewerActivity.EXTRA_INDEX, index)
            )
        }
        // 长按搜索结果：分享 / 复制识别文本 / 删除
        adapter.onLongClick = { item ->
            ShotActions.showActionsMenu(this, item) {
                deleter.delete(item.path) {
                    doSearch(lastQuery)
                }
            }
        }
    }

    private fun doSearch(q: String) {
        lastQuery = q
        if (q.isEmpty()) {
            binding.emptyText.text = "输入关键词搜索（基于 OCR 识别出的文字）"
            binding.emptyText.visibility = View.VISIBLE
            adapter.submitList(emptyList())
            return
        }
        val results = db.search(q)
        adapter.submitList(results)
        if (results.isEmpty()) {
            binding.emptyText.text = "没有找到「$q」相关截图"
            binding.emptyText.visibility = View.VISIBLE
        } else {
            binding.emptyText.visibility = View.GONE
        }
    }
}

class SearchAdapter :
    androidx.recyclerview.widget.ListAdapter<ShotItem, SearchAdapter.VH>(
        object : androidx.recyclerview.widget.DiffUtil.ItemCallback<ShotItem>() {
            override fun areItemsTheSame(a: ShotItem, b: ShotItem) = a.path == b.path
            override fun areContentsTheSame(a: ShotItem, b: ShotItem) = a == b
        }
    ) {

    var onClick: ((List<ShotItem>, Int) -> Unit)? = null
    var onLongClick: ((ShotItem) -> Unit)? = null

    inner class VH(val binding: com.rhythm.shots.databinding.ItemSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = com.rhythm.shots.databinding.ItemSearchResultBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.binding.apply {
            resultImage.load(item.path)
            val badge = item.badge()
            val badgePrefix = if (badge != ShotBadge.NONE) "[${badge.label}] " else ""
            resultGame.text = badgePrefix +
                com.rhythm.shots.data.GameSettings.effectiveName(holder.binding.root.context, item.gameName)
            val song = if (item.songName.isNotBlank()) item.songName
            else if (item.songCn.isNotBlank()) item.songCn
            else ""
            if (song.isNotBlank()) {
                resultSong.text = song
                resultSong.visibility = View.VISIBLE
            } else {
                resultSong.visibility = View.GONE
            }
            resultDate.text = item.date.let {
                try {
                    val d = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).parse(it)
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(d)
                } catch (_: Exception) { it }
            }
            resultOcr.text = item.ocrText.ifBlank { "（无 OCR 文本）" }
        }
        holder.binding.root.setOnClickListener {
            onClick?.invoke(currentList, holder.bindingAdapterPosition)
        }
        holder.binding.root.setOnLongClickListener {
            onLongClick?.invoke(getItem(holder.bindingAdapterPosition))
            true
        }
    }
}
