package com.example.ipotech

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.ipotech.databinding.FragmentTrendsBinding
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import com.google.android.material.chip.Chip
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class TrendsFragment : Fragment() {

    private var _binding: FragmentTrendsBinding? = null
    private val binding get() = _binding!!

    private lateinit var database: DatabaseReference
    private var temperatureListener: ValueEventListener? = null
    private var historyListener: ValueEventListener? = null

    private val TIME_1H = 1 * 60 * 60 * 1000L
    private val TIME_6H = 6 * 60 * 60 * 1000L
    private val TIME_24H = 24 * 60 * 60 * 1000L
    private val TIME_7D = 7 * 24 * 60 * 60 * 1000L

    private var selectedTimeRange = TIME_6H
    private var criticalThreshold = 35f

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrendsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = FirebaseDatabase.getInstance("https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/").reference

        loadCriticalThreshold()
        setupChart()
        setupTimeRangeChips()
        setupChipStyling()
        setupCurrentTempListener()
        loadTemperatureHistory()
    }
    
    private fun loadCriticalThreshold() {
        val context = context ?: return
        val prefs = context.getSharedPreferences(IpoTechApplication.PREFS_NAME, Context.MODE_PRIVATE)
        val thresholdStr = prefs.getString(IpoTechApplication.KEY_CRITICAL_THRESHOLD, "10") ?: "10"
        criticalThreshold = thresholdStr.toFloatOrNull() ?: 10f
    }
    
    private fun setupChipStyling() {
        if (_binding == null) return
        val chips = listOf(binding.chip1h, binding.chip6h, binding.chip24h, binding.chip7d)
        chips.forEach { chip ->
            updateChipStyle(chip, chip.isChecked)
            chip.setOnCheckedChangeListener { _, isChecked ->
                updateChipStyle(chip, isChecked)
            }
        }
    }
    
    private fun updateChipStyle(chip: Chip, isChecked: Boolean) {
        val ctx = context ?: return
        if (isChecked) {
            chip.setChipBackgroundColorResource(R.color.primary_teal)
            chip.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
        } else {
            chip.setChipBackgroundColorResource(R.color.industrial_stroke)
            chip.setTextColor(ContextCompat.getColor(ctx, R.color.industrial_text_inactive))
        }
        chip.chipStrokeWidth = 0f
    }

    private fun setupChart() {
        if (_binding == null || !isAdded) return
        
        binding.lineChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            setBackgroundColor(Color.TRANSPARENT)
            
            val ctx = context ?: return@apply
            marker = TempMarkerView(ctx)
            
            legend.isEnabled = false

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = ContextCompat.getColor(ctx, R.color.industrial_text_inactive)
                setDrawGridLines(true)
                gridColor = ContextCompat.getColor(ctx, R.color.industrial_stroke)
                gridLineWidth = 0.5f
                enableGridDashedLine(10f, 5f, 0f)
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                    override fun getFormattedValue(value: Float): String {
                        return dateFormat.format(Date(value.toLong()))
                    }
                }
            }

            axisLeft.apply {
                textColor = ContextCompat.getColor(ctx, R.color.industrial_text_inactive)
                setDrawGridLines(true)
                gridColor = ContextCompat.getColor(ctx, R.color.industrial_stroke)
                gridLineWidth = 0.5f
                enableGridDashedLine(10f, 5f, 0f)
                axisMinimum = 0f
                axisMaximum = 200f
                setDrawLabels(true)
                setLabelCount(6, true)
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return "${value.toInt()}°C"
                    }
                }
            }

            axisRight.isEnabled = false
            setNoDataText("Analyzing patterns...")
            setNoDataTextColor(ContextCompat.getColor(ctx, R.color.industrial_text_inactive))
        }
    }

    private fun setupTimeRangeChips() {
        if (_binding == null) return
        binding.chipGroupTimeRange.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            
            selectedTimeRange = when (checkedIds[0]) {
                R.id.chip_1h -> TIME_1H
                R.id.chip_6h -> TIME_6H
                R.id.chip_24h -> TIME_24H
                R.id.chip_7d -> TIME_7D
                else -> TIME_6H
            }
            loadTemperatureHistory()
        }
    }

    private fun setupCurrentTempListener() {
        temperatureListener = database.child("temperature/current").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null || !isAdded) return
                val temp = when (val value = snapshot.value) {
                    is Double -> value
                    is Long -> value.toDouble()
                    else -> value?.toString()?.toDoubleOrNull()
                }
                if (temp != null) {
                    binding.tvCurrentTemp.text = "Current: ${String.format("%.1f", temp)}°C"
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun loadTemperatureHistory() {
        if (_binding == null) return
        binding.progressLoading.visibility = View.VISIBLE
        binding.tvNoData.visibility = View.GONE

        historyListener?.let { database.child("temperature_history").removeEventListener(it) }

        val cutoffTime = System.currentTimeMillis() - selectedTimeRange

        historyListener = database.child("temperature_history")
            .orderByChild("timestamp")
            .startAt(cutoffTime.toDouble())
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (_binding == null || !isAdded) return

                    binding.progressLoading.visibility = View.GONE

                    val entries = mutableListOf<Entry>()
                    var minTemp = Float.MAX_VALUE
                    var maxTemp = Float.MIN_VALUE
                    var totalTemp = 0f

                    for (child in snapshot.children) {
                        val timestamp = child.child("timestamp").getValue(Long::class.java) ?: continue
                        val temperatureValue = child.child("value").value
                        val temperature = when (temperatureValue) {
                            is Double -> temperatureValue.toFloat()
                            is Long -> temperatureValue.toFloat()
                            else -> temperatureValue?.toString()?.toFloatOrNull() ?: continue
                        }

                        entries.add(Entry(timestamp.toFloat(), temperature))
                        if (temperature < minTemp) minTemp = temperature
                        if (temperature > maxTemp) maxTemp = temperature
                        totalTemp += temperature
                    }

                    if (entries.isEmpty()) {
                        binding.tvNoData.visibility = View.VISIBLE
                        binding.lineChart.clear()
                        binding.tvMinTemp.text = "--°C"
                        binding.tvAvgTemp.text = "--°C"
                        binding.tvMaxTemp.text = "--°C"
                        return
                    }

                    binding.tvNoData.visibility = if (entries.size < 2) View.VISIBLE else View.GONE
                    if (entries.size < 2) {
                        binding.tvNoData.text = "Insufficient data points"
                        binding.lineChart.clear()
                        return
                    }

                    val avgTemp = totalTemp / entries.size
                    binding.tvMinTemp.text = "${minTemp.toInt()}°C"
                    binding.tvMaxTemp.text = "${maxTemp.toInt()}°C"
                    binding.tvAvgTemp.text = "${avgTemp.toInt()}°C"

                    // Auto-scale Y-axis
                    val range = if (maxTemp == minTemp) 10f else (maxTemp - minTemp)
                    val padding = range * 0.3f
                    binding.lineChart.axisLeft.axisMinimum = (minTemp - padding).coerceAtLeast(0f)
                    binding.lineChart.axisLeft.axisMaximum = maxTemp + padding

                    val ctx = context ?: return
                    binding.lineChart.axisLeft.removeAllLimitLines()
                    
                    // Add critical threshold line if it's within a reasonable view
                    val targetHigh = 130f // Your base target
                    val limit = targetHigh + criticalThreshold
                    val limitLine = LimitLine(limit, "Critical Limit").apply {
                        lineWidth = 1.5f
                        lineColor = ContextCompat.getColor(ctx, R.color.exit_red)
                        enableDashedLine(10f, 5f, 0f)
                        textColor = ContextCompat.getColor(ctx, R.color.exit_red)
                        textSize = 10f
                    }
                    binding.lineChart.axisLeft.addLimitLine(limitLine)

                    val dataSet = LineDataSet(entries, "Temperature").apply {
                        color = ContextCompat.getColor(ctx, R.color.led_on)
                        lineWidth = 2.5f
                        setDrawCircles(entries.size < 30)
                        circleRadius = 4f
                        setCircleColor(ContextCompat.getColor(ctx, R.color.led_on))
                        circleHoleColor = ContextCompat.getColor(ctx, R.color.btn_active_off)
                        setDrawValues(false)
                        mode = LineDataSet.Mode.CUBIC_BEZIER
                        setDrawFilled(true)
                        fillDrawable = ContextCompat.getDrawable(ctx, R.drawable.chart_gradient_fill)
                        fillAlpha = 80
                        highLightColor = ContextCompat.getColor(ctx, R.color.primary_teal)
                        highlightLineWidth = 1.5f
                    }

                    binding.lineChart.data = LineData(dataSet)
                    binding.lineChart.invalidate()
                }

                override fun onCancelled(error: DatabaseError) {
                    if (_binding == null) return
                    binding.progressLoading.visibility = View.GONE
                    binding.tvNoData.visibility = View.VISIBLE
                }
            })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        temperatureListener?.let { database.child("temperature/current").removeEventListener(it) }
        historyListener?.let { database.child("temperature_history").removeEventListener(it) }
        _binding = null
    }
    
    inner class TempMarkerView(context: Context) : MarkerView(context, R.layout.chart_marker) {
        private val tvContent: TextView = findViewById(R.id.tv_marker_content)
        private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        override fun refreshContent(e: Entry?, highlight: Highlight?) {
            e?.let {
                val time = dateFormat.format(Date(e.x.toLong()))
                tvContent.text = "${String.format("%.1f", e.y)}°C\n$time"
            }
            super.refreshContent(e, highlight)
        }
        override fun getOffset() = MPPointF((-(width / 2)).toFloat(), (-height - 10).toFloat())
    }
}
