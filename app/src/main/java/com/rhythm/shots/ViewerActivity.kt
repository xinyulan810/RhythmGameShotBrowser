package com.rhythm.shots

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.rhythm.shots.data.ShotBadge
import com.rhythm.shots.data.ShotDb
import com.rhythm.shots.data.ShotItem
import com.rhythm.shots.data.badge
import com.rhythm.shots.databinding.ActivityViewerBinding
import com.rhythm.shots.databinding.ItemViewerPageBinding

class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATHS = "paths"
        const val EXTRA_INDEX = "index"
    }

    private lateinit var binding: ActivityViewerBinding
    private var items: List<ShotItem> = emptyList()
    private var overlayVisible = false
    private lateinit var deleter: ShotDeleter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 大图沉浸：隐藏手机状态栏（顶部下滑可临时呼出）
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        deleter = ShotDeleter(this)

        val paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: emptyList()
        val index = intent.getIntExtra(EXTRA_INDEX, 0)
        if (paths.isEmpty()) { finish(); return }

        val db = ShotDb(this)
        val byPath = db.all().associateBy { it.path }
        items = paths.mapNotNull { byPath[it] }
        if (items.isEmpty()) {
            items = paths.map { ShotItem(it, "", "", "", "") }
        }

        binding.pager.adapter = buildPagerAdapter()
        binding.pager.offscreenPageLimit = 1 // 预加载相邻页，左右滑动更顺
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateInfo(position)
            }
        })
        binding.pager.setCurrentItem(index, false)
        updateInfo(index)

        binding.viewerShareButton.setOnClickListener {
            currentItem()?.let { ShotActions.share(this, it.path) }
        }

        // 大图界面默认隐藏 OCR 覆盖层：点按屏幕显示 OCR 信息栏与右上角分享按钮，再点隐藏
        applyOverlay()
    }

    override fun onPause() {
        super.onPause()
        // 离开大图时恢复状态栏，避免返回其他界面状态栏异常
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.statusBars())
    }

    private fun buildPagerAdapter() = object : FragmentStateAdapter(this) {
        override fun getItemCount() = items.size
        override fun createFragment(position: Int) =
            ViewerFragment(items[position].path)
    }

    private fun currentItem(): ShotItem? = items.getOrNull(binding.pager.currentItem)

    /** 当前页图片被点按：显示/隐藏 OCR 信息栏与分享按钮 */
    fun onPageTapped() {
        overlayVisible = !overlayVisible
        applyOverlay()
    }

    /** 当前页图片被长按：弹出操作菜单 */
    fun onPageLongPressed() {
        val item = currentItem() ?: return
        ShotActions.showActionsMenu(this, item) {
            deleteCurrent()
        }
    }

    private fun applyOverlay() {
        binding.viewerInfoBar.visibility =
            if (overlayVisible) View.VISIBLE else View.GONE
        binding.viewerShareButton.visibility =
            if (overlayVisible) View.VISIBLE else View.GONE
    }

    private fun deleteCurrent() {
        val pos = binding.pager.currentItem
        val item = items.getOrNull(pos) ?: return
        deleter.delete(item.path) {
            // 从列表移除并重建分页器
            val updated = items.toMutableList().apply { removeAt(pos) }
            items = updated
            if (items.isEmpty()) {
                finish()
                return@delete
            }
            val keep = if (pos >= items.size) items.size - 1 else pos
            binding.pager.adapter = buildPagerAdapter()
            binding.pager.setCurrentItem(keep, false)
            updateInfo(binding.pager.currentItem)
        }
    }

    private fun updateInfo(position: Int) {
        val item = items.getOrNull(position) ?: return
        val song = when {
            item.songName.isNotBlank() -> item.songName
            item.songCn.isNotBlank() -> item.songCn
            else -> ""
        }
        val title = buildString {
            val badge = item.badge()
            if (badge != ShotBadge.NONE) append(badge.label).append(" · ")
            append(
                com.rhythm.shots.data.GameSettings.effectiveName(this@ViewerActivity, item.gameName)
                    .ifBlank { "未知" }
            )
            if (song.isNotBlank()) append(" · $song")
            append(" · ${formatDate(item.date)} · ${position + 1}/${items.size}")
        }
        binding.viewerInfo.text = title
        val detail = buildString {
            if (item.difficulty.isNotBlank()) append("难度 ${item.difficulty}  ")
            if (item.score.isNotBlank()) append("分数 ${item.score}  ")
            if (item.accuracy.isNotBlank()) append("ACC ${item.accuracy}  ")
            if (item.rank.isNotBlank()) append("评级 ${item.rank}  ")
            if (item.combo.isNotBlank()) append("COMBO ${item.combo}")
        }
        val ocr = if (item.ocrText.isNotBlank()) item.ocrText else item.vlmText
        binding.viewerOcr.text = if (detail.isNotBlank() && ocr.isNotBlank()) {
            detail + "\n" + ocr
        } else if (detail.isNotBlank()) detail
        else ocr
        binding.viewerOcr.visibility =
            if (binding.viewerOcr.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun formatDate(yyyyMMdd: String): String {
        if (yyyyMMdd.isBlank()) return ""
        return try {
            val d = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).parse(yyyyMMdd)
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(d)
        } catch (_: Exception) { yyyyMMdd }
    }
}

class ViewerFragment(private val path: String) : androidx.fragment.app.Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = ItemViewerPageBinding.inflate(inflater, container, false).apply {
        pageImage.load(path)
        // 点按图片切换 OCR 覆盖层，长按弹出操作菜单
        pageImage.setOnClickListener {
            (activity as? ViewerActivity)?.onPageTapped()
        }
        pageImage.setOnLongClickListener {
            (activity as? ViewerActivity)?.onPageLongPressed()
            true
        }
    }.root
}
