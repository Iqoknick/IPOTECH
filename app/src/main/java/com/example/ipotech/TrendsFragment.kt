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
    private var criticalThreshold = 10f
    private var targetLow = 120f
    private var targetHigh = 130f
    
    // Used to prevent Float precision loss with large timestamps
    private var chartReferenceTime = 0L

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

        loadSystemSettings()
        setupChart()
        setupTimeRangeChips()
        setupChipStyling()
        setupCurrentTempListener()
        loadTemperatureHistory()
    }
    
    private fun loadSystemSettings() {
        val context = context ?: return
        val prefs = context.getSharedPreferences(IpoTechApplication.PREFS_NAME, Context.MODE_PRIVATE)
        val thresholdStr = prefs.getString(IpoTechApplication.KEY_CRITICAL_THRESHOLD, "10") ?: "10"
        criticalThreshold = thresholdStr.toFloatOrNull() ?: 10f
        
        database.child("heater").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                targetLow = snapshot.child("temp_low").getValue(Float::class.java) ?: 120f
                targetHigh = snapshot.child("temp_high").getValue(Float::class.java) ?: 130f
                if (isAdded) setupChart()
            }
            override fun onCancelled(error: DatabaseError) {}
        })
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
        val ctx = context ?: return
        
        binding.lineChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            setBackgroundColor(Color.TRANSPARENT)
            
            marker = TempMarkerView(ctx)
            legend.isEnabled = false

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = ContextCompat.getColor(ctx, R.color.industrial_text_inactive)
                setDrawGridLines(true)
                gridColor = Color.parseColor("#1AFFFFFF")
                gridLineWidth = 0.5f
                granularity = 1f
                // Disable drawing axis line to prevent redundant baseline
                setDrawAxisLine(false)
                
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        // Reconstruct timestamp using reference
                        val actualTimestamp = value.toLong() + chartReferenceTime
                        val format = if (selectedTimeRange >= TIME_7D) "MM/dd HH:mm" else "HH:mm"
                        return SimpleDateFormat(format, Locale.getDefault()).format(Date(actualTimestamp))
                    }
                }
            }

            axisLeft.apply {
                textColor = ContextCompat.getColor(ctx, R.color.industrial_text_inactive)
                setDrawGridLines(true)
                gridColor = Color.parseColor("#1AFFFFFF")
                gridLineWidth = 0.5f
                axisMinimum = 0f
                axisMaximum = 200f
                setDrawAxisLine(false)
                setLabelCount(6, false)
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "${value.toInt()}°C"
                }
                
                removeAllLimitLines()
                val rangeLine = LimitLine(targetHigh, "Target Max").apply {
                    lineColor = ContextCompat.getColor(ctx, R.color.primary_teal)
                    lineWidth = 1f
                    enableDashedLine(10f, 10f, 0f)
                    textColor = ContextCompat.getColor(ctx, R.color.primary_teal)
                    textSize = 8f
                }
                addLimitLine(rangeLine)

                val limit = targetHigh + criticalThreshold
                val critLine = LimitLine(limit, "CRITICAL").apply {
                    lineColor = ContextCompat.getColor(ctx, R.color.exit_red)
                    lineWidth = 1.5f
                    textColor = ContextCompat.getColor(ctx, R.color.exit_red)
                    textSize = 9f
                    labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
                }
                addLimitLine(critLine)
            }

            axisRight.isEnabled = false
            setNoDataText("Synchronizing data...")
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
                    else -> value?.toString()?.toDoubleOrNull() ?: 0.0
                }
                binding.tvCurrentTemp.text = "Current: ${String.format("%.1f", temp)}°C"
                val color = when {
                    temp > targetHigh + criticalThreshold -> R.color.exit_red
                    temp > targetHigh -> R.color.led_on
                    else -> R.color.primary_teal
                }
                binding.tvCurrentTemp.setTextColor(ContextCompat.getColor(requireContext(), color))
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
        chartReferenceTime = cutoffTime // Set new reference for this window

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

                        // Use relative X to keep precision
                        entries.add(Entry((timestamp - chartReferenceTime).toFloat(), temperature))
                        if (temperature < minTemp) minTemp = temperature
                        if (temperature > maxTemp) maxTemp = temperature
                        totalTemp += temperature
                    }

                    if (entries.isEmpty()) {
                        binding.tvNoData.visibility = View.VISIBLE
                        binding.lineChart.clear()
                        return
                    }

                    binding.tvMinTemp.text = "${minTemp.toInt()}°C"
                    binding.tvMaxTemp.text = "${maxTemp.toInt()}°C"
                    binding.tvAvgTemp.text = "${(totalTemp / entries.size).toInt()}°C"

                    val padding = (maxTemp - minTemp).coerceAtLeast(20f) * 0.2f
                    binding.lineChart.axisLeft.axisMinimum = (minTemp - padding).coerceAtLeast(0f)
                    binding.lineChart.axisLeft.axisMaximum = (maxTemp + padding).coerceAtLeast(targetHigh + criticalThreshold + 10f)

                    val dataSet = LineDataSet(entries, "Process Temperature").apply {
                        color = ContextCompat.getColor(requireContext(), R.color.primary_teal)
                        lineWidth = 2f
                        setDrawCircles(selectedTimeRange <= TIME_1H)
                        circleRadius = 3f
                        setCircleColor(ContextCompat.getColor(requireContext(), R.color.primary_teal))
                        setDrawValues(false)
                        mode = LineDataSet.Mode.CUBIC_BEZIER
                        setDrawFilled(true)
                        fillDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.chart_gradient_fill)
                        fillAlpha = 50
                        highLightColor = Color.WHITE
                        highlightLineWidth = 1f
                        setDrawHorizontalHighlightIndicator(false)
                    }

                    binding.lineChart.data = LineData(dataSet)
                    binding.lineChart.invalidate()
                }
                override fun onCancelled(error: DatabaseError) {
                    if (_binding != null) binding.progressLoading.visibility = View.GONE
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
        override fun refreshContent(e: Entry?, highlight: Highlight?) {
            e?.let {
                val actualTimestamp = e.x.toLong() + chartReferenceTime
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(actualTimestamp))
                tvContent.text = "${String.format("%.1f", e.y)}°C\n$time"
            }
            super.refreshContent(e, highlight)
        }
        override fun getOffset() = MPPointF((-(width / 2)).toFloat(), (-height - 10).toFloat())
    }
}
