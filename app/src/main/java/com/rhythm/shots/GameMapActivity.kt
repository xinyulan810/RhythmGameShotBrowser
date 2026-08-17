package com.rhythm.shots

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rhythm.shots.data.GameMap
import com.rhythm.shots.databinding.ActivityGameMapBinding
import com.rhythm.shots.databinding.ItemGameMapBinding

class GameMapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameMapBinding
    private val map = LinkedHashMap<String, String>()
    private val adapter = MapAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.mapList.layoutManager = LinearLayoutManager(this)
        binding.mapList.adapter = adapter

        reload()

        binding.addButton.setOnClickListener { showEditDialog(null) }
        adapter.onEdit = { pkg -> showEditDialog(pkg) }
        adapter.onDelete = { pkg ->
            GameMap.delete(this, pkg)
            reload()
        }
    }

    private fun reload() {
        map.clear()
        map.putAll(GameMap.load(this))
        adapter.submitList(map.toList())
    }

    private fun save() {
        GameMap.save(this, map)
        reload()
    }

    private fun showEditDialog(pkgToEdit: String?) {
        val container = LinearLayoutHolder(this)
        val pkgInput = EditText(this).apply {
            hint = "包名（如 moe.low.arc）"
            setText(pkgToEdit ?: "")
            isEnabled = pkgToEdit == null // 已存在的不改包名
        }
        val gameInput = EditText(this).apply {
            hint = "游戏名（如 Arcaea）"
            setText(pkgToEdit?.let { map[it] } ?: "")
        }
        container.addView(pkgInput)
        container.addView(gameInput)

        AlertDialog.Builder(this)
            .setTitle(if (pkgToEdit == null) "添加映射" else "编辑映射")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val pkg = pkgInput.text.toString().trim()
                val game = gameInput.text.toString().trim()
                if (pkg.isEmpty() || game.isEmpty()) return@setPositiveButton
                map[pkg] = game
                save()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private class LinearLayoutHolder(context: android.content.Context) :
        android.widget.LinearLayout(context) {
        init {
            orientation = VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
    }
}

class MapAdapter :
    androidx.recyclerview.widget.ListAdapter<Pair<String, String>, MapAdapter.VH>(
        object : androidx.recyclerview.widget.DiffUtil.ItemCallback<Pair<String, String>>() {
            override fun areItemsTheSame(a: Pair<String, String>, b: Pair<String, String>) = a.first == b.first
            override fun areContentsTheSame(a: Pair<String, String>, b: Pair<String, String>) = a == b
        }
    ) {

    var onEdit: ((String) -> Unit)? = null
    var onDelete: ((String) -> Unit)? = null

    inner class VH(val binding: ItemGameMapBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemGameMapBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (pkg, game) = getItem(position)
        holder.binding.apply {
            mapPkg.text = pkg
            mapGame.text = game
            root.setOnClickListener { onEdit?.invoke(pkg) }
            deleteButton.setOnClickListener { onDelete?.invoke(pkg) }
        }
    }
}
