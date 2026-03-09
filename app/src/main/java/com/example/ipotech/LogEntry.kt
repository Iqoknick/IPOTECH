package com.example.ipotech

data class LogEntry(
    val timestamp: Long = 0,
    val action: String = "",
    val details: String = ""
)