package com.mrquentinet.matrixcontroller.domain

data class CpuMetrics(
    val loopHz: Long,
    val busyPerMille: Long,
    val requests: Long,
    val requestAvgMicros: Long,
    val requestMaxMicros: Long,
    val authAvgMicros: Long,
    val authMaxMicros: Long,
)

data class RamMetrics(
    val total: Long,
    val staticBytes: Long,
    val heapUsed: Long,
    val stackPeak: Long,
    val freeNow: Long,
    val minFreeEver: Long,
)

data class BoardMetrics(val cpu: CpuMetrics, val ram: RamMetrics)
