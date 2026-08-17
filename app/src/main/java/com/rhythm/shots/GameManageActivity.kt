package com.rhythm.shots

import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rhythm.shots.data.GameMap
import com.rhythm.shots.data.GameSettings
import com.rhythm.shots.data.ShotDb
import com.rhythm.shots.databinding.ActivityGameManageBinding
import com.rhythm.shots.databinding.ItemGameManageBinding

/** 游戏管理：隐藏游戏 / 合并游戏 */
class GameManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameManageBinding
    private val adapter = ManageAdapter()

    // 所有游戏名（应用合并前的原始集合）
    private var allGames: List<String> = emptyList()
    private var hidden: MutableSet<String> = mutableSetOf()
    private var merged: MutableMap<String, String> = mutableMapOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.gameManageList.layoutManager = LinearLayoutManager(this)
        binding.gameManageList.adapter = adapter

        adapter.onToggleHidden = { game, isHidden ->
            if (isHidden) hidden.add(game) else hidden.remove(game)
            GameSettings.setHidden(this, hidden)
        }
        adapter.onMerge = { game -> showMergeDialog(game) }

        reload()
    }

    private fun reload() {
        hidden = GameSettings.hiddenGames(this)
        merged = GameSettings.mergedGames(this)
        val fromMap = GameMap.load(this).values.toSet()
        val fromDb = ShotDb(this).all().map { it.gameName }.toSet()
        // 已有命名：自动生成（映射/包名兜底）+ 手动修改（自定义映射、数据库）+ 已有合并目标
        allGames = (fromMap + fromDb + merged.values).sorted()
        adapter.setHiddenSet(hidden)
        adapter.submitList(allGames)
    }

    private fun showMergeDialog(game: String) {
        // 下拉候选：所有已有命名（自动生成 + 手动修改），排除当前游戏自身
        val choices = allGames.filter { it != game }
        val input = AutoCompleteTextView(this).apply {
            hint = "选择要合并到的游戏名（留空取消合并）"
            setText(merged[game] ?: "")
            setAdapter(
                ArrayAdapter(
                    this@GameManageActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    choices
                )
            )
            threshold = 0
            // 禁用软键盘：点击只展开下拉列表，不弹输入法（避免顶起对话框导致列表看不全）
            inputType = android.text.InputType.TYPE_NULL
            // 聚焦即展开全部候选，点击也可再次展开
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showDropDown() }
            setOnClickListener { showDropDown() }
        }
        AlertDialog.Builder(this)
            .setTitle("把「$game」合并到")
            .setMessage("合并后该游戏的截图会归入目标游戏分组，并影响 AI 识别提示词")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val target = input.text.toString().trim()
                if (target.isEmpty()) {
                    merged.remove(game)
                } else if (target != game) {
                    merged[game] = target
                }
                GameSettings.setMerged(this, merged)
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

class ManageAdapter :
    androidx.recyclerview.widget.ListAdapter<String, ManageAdapter.VH>(
        object : androidx.recyclerview.widget.DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(a: String, b: String) = a == b
            override fun areContentsTheSame(a: String, b: String) = a == b
        }
    ) {

    var onToggleHidden: ((String, Boolean) -> Unit)? = null
    var onMerge: ((String) -> Unit)? = null
    private var hidden: Set<String> = emptySet()

    fun setHiddenSet(h: Set<String>) {
        hidden = h
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemGameManageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemGameManageBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val game = getItem(position)
        holder.binding.apply {
            manageGameName.text = game
            manageHiddenSwitch.isChecked = game in hidden
            manageHiddenSwitch.setOnCheckedChangeListener { _, checked ->
                onToggleHidden?.invoke(game, checked)
            }
            manageMergeButton.setOnClickListener { onMerge?.invoke(game) }
        }
    }
}
