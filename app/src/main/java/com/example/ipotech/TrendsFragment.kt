package com.example.ipotech

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.ipotech.databinding.FragmentTrendsBinding
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class TrendsFragment : Fragment() {

    private var _binding: FragmentTrendsBinding? = null
    private val binding get() = _binding!!

    private lateinit var database: DatabaseReference
    private var temperatureListener: ValueEventListener? = null
    private var historyListener: ValueEventListener? = null

    // Time ranges in milliseconds
    private val TIME_1H = 1 * 60 * 60 * 1000L
    private val TIME_6H = 6 * 60 * 60 * 1000L
    private val TIME_24H = 24 * 60 * 60 * 1000L
    private val TIME_7D = 7 * 24 * 60 * 60 * 1000L

    private var selectedTimeRange = TIME_6H

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

        setupChart()
        setupTimeRangeChips()
        setupCurrentTempListener()
        loadTemperatureHistory()
    }

    private fun setupChart() {
        binding.lineChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            
            // Legend
            legend.textColor = ContextCompat.getColor(requireContext(), R.color.text_primary)
            legend.isEnabled = true

            // X-Axis (Time)
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
                setDrawGridLines(true)
                gridColor = ContextCompat.getColor(requireContext(), R.color.divider)
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                    override fun getFormattedValue(value: Float): String {
                        return dateFormat.format(Date(value.toLong()))
                    }
                }
            }

            // Y-Axis (Temperature)
            axisLeft.apply {
                textColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
                setDrawGridLines(true)
                gridColor = ContextCompat.getColor(requireContext(), R.color.divider)
                axisMinimum = 0f
                axisMaximum = 200f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return "${value.toInt()}°C"
                    }
                }
            }

            axisRight.isEnabled = false

            // No data text
            setNoDataText("")
        }
    }

    private fun setupTimeRangeChips() {
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
                val temp = when (val value = snapshot.value) {
                    is Double -> value
                    is Long -> value.toDouble()
                    else -> value?.toString()?.toDoubleOrNull()
                }
                if (temp != null && _binding != null) {
                    binding.tvCurrentTemp.text = "Current: ${String.format("%.1f", temp)}°C"
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun loadTemperatureHistory() {
        _binding?.progressLoading?.visibility = View.VISIBLE
        _binding?.tvNoData?.visibility = View.GONE

        // Remove previous listener if exists
        historyListener?.let {
            database.child("temperature_history").removeEventListener(it)
        }

        val cutoffTime = System.currentTimeMillis() - selectedTimeRange

        historyListener = database.child("temperature_history")
            .orderByChild("timestamp")
            .startAt(cutoffTime.toDouble())
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (_binding == null) return

                    binding.progressLoading.visibility = View.GONE

                    val entries = mutableListOf<Entry>()
                    var minTemp = Float.MAX_VALUE
                    var maxTemp = Float.MIN_VALUE
                    var totalTemp = 0f

                    for (child in snapshot.children) {
                        val timestamp = child.child("timestamp").getValue(Long::class.java) ?: continue
                        val temperature = child.child("value").getValue(Double::class.java)?.toFloat() ?: continue

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

                    binding.tvNoData.visibility = View.GONE

                    // Sort entries by timestamp
                    entries.sortBy { it.x }

                    // Update statistics
                    val avgTemp = totalTemp / entries.size
                    binding.tvMinTemp.text = "${String.format("%.1f", minTemp)}°C"
                    binding.tvAvgTemp.text = "${String.format("%.1f", avgTemp)}°C"
                    binding.tvMaxTemp.text = "${String.format("%.1f", maxTemp)}°C"

                    // Create dataset
                    val dataSet = LineDataSet(entries, "Temperature").apply {
                        color = ContextCompat.getColor(requireContext(), R.color.primary)
                        lineWidth = 2f
                        setDrawCircles(entries.size < 50) // Only show circles if few data points
                        circleRadius = 3f
                        setCircleColor(ContextCompat.getColor(requireContext(), R.color.primary))
                        setDrawValues(false)
                        mode = LineDataSet.Mode.CUBIC_BEZIER
                        setDrawFilled(true)
                        fillColor = ContextCompat.getColor(requireContext(), R.color.primary)
                        fillAlpha = 30
                        
                        // Highlight
                        highLightColor = ContextCompat.getColor(requireContext(), R.color.accent)
                        setDrawHighlightIndicators(true)
                    }

                    // Add limit lines for target range (if you want to show target zone)
                    binding.lineChart.axisLeft.removeAllLimitLines()

                    binding.lineChart.data = LineData(dataSet)
                    binding.lineChart.invalidate()
                    binding.lineChart.animateX(500)
                }

                override fun onCancelled(error: DatabaseError) {
                    if (_binding == null) return
                    binding.progressLoading.visibility = View.GONE
                    binding.tvNoData.visibility = View.VISIBLE
                    binding.tvNoData.text = "Error loading data"
                }
            })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        temperatureListener?.let {
            database.child("temperature/current").removeEventListener(it)
        }
        historyListener?.let {
            database.child("temperature_history").removeEventListener(it)
        }
        _binding = null
    }
}
