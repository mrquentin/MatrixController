package com.mrquentinet.matrixcontroller.data.api.dto

import com.mrquentinet.matrixcontroller.domain.BoardMetrics
import com.mrquentinet.matrixcontroller.domain.CpuMetrics
import com.mrquentinet.matrixcontroller.domain.RamMetrics
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CpuDto(
    @SerialName("loop_hz") val loopHz: Long,
    @SerialName("busy_permille") val busyPerMille: Long,
    val requests: Long,
    @SerialName("req_avg_us") val reqAvgUs: Long,
    @SerialName("req_max_us") val reqMaxUs: Long,
    @SerialName("auth_avg_us") val authAvgUs: Long,
    @SerialName("auth_max_us") val authMaxUs: Long,
)

@Serializable
data class RamDto(
    val total: Long,
    // `static` is a Kotlin-legal identifier but reads badly; the wire key is what matters.
    @SerialName("static") val staticBytes: Long,
    @SerialName("heap_used") val heapUsed: Long,
    @SerialName("stack_peak") val stackPeak: Long,
    @SerialName("free_now") val freeNow: Long,
    @SerialName("min_free_ever") val minFreeEver: Long,
)

@Serializable
data class MetricsDto(val cpu: CpuDto, val ram: RamDto)

fun MetricsDto.toDomain() = BoardMetrics(
    cpu = CpuMetrics(
        loopHz = cpu.loopHz,
        busyPerMille = cpu.busyPerMille,
        requests = cpu.requests,
        requestAvgMicros = cpu.reqAvgUs,
        requestMaxMicros = cpu.reqMaxUs,
        authAvgMicros = cpu.authAvgUs,
        authMaxMicros = cpu.authMaxUs,
    ),
    ram = RamMetrics(
        total = ram.total,
        staticBytes = ram.staticBytes,
        heapUsed = ram.heapUsed,
        stackPeak = ram.stackPeak,
        freeNow = ram.freeNow,
        minFreeEver = ram.minFreeEver,
    ),
)
