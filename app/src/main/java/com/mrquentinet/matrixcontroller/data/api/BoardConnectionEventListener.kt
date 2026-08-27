package com.mrquentinet.matrixcontroller.data.api

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

internal const val BOARD_HTTP_LOG_TAG = "MatrixBoardHttp"

/**
 * Logs every phase of a board HTTP call through OkHttp's own instrumentation hook, with
 * elapsed-since-call-start timing: `adb logcat -s $BOARD_HTTP_LOG_TAG`.
 *
 * The board serves one request per TCP connection with a 4 s per-read timeout, and its WiFiNINA
 * radio (or the network between it and the phone) can silently drop a SYN with no RST. A bare
 * `BoardError.Unreachable` cannot show *which* phase stalled — DNS, TCP connect, sending the
 * request, or waiting for a response. This can, and it logs the full exception (type, message,
 * stack trace) at every failure point instead of collapsing everything into one error.
 *
 * One instance is created per call (see [FACTORY]), so the elapsed-time state here is never
 * shared across concurrent calls — relevant once probes to different boards run in parallel.
 */
class BoardConnectionEventListener : EventListener() {

    private var startNanos = 0L
    private var label = "?"

    private fun elapsed() = "+${(System.nanoTime() - startNanos) / 1_000_000}ms"

    override fun callStart(call: Call) {
        startNanos = System.nanoTime()
        label = "${call.request().method} ${call.request().url.encodedPath}"
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] call start -> ${call.request().url}")
    }

    override fun dispatcherQueueStart(call: Call, dispatcher: Dispatcher) {
        // maxRequestsPerHost = 1: a call queues here only when another call to the same board is
        // already in flight. Without this line that queueing time is invisible and looks
        // identical to the board itself being slow.
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} queued behind another call to this board")
    }

    override fun dispatcherQueueEnd(call: Call, dispatcher: Dispatcher) {
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} dequeued, starting")
    }

    override fun dnsStart(call: Call, domainName: String) {
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} DNS lookup start: $domainName")
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} DNS lookup end: $domainName -> $inetAddressList")
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} TCP connect start -> $inetSocketAddress")
    }

    override fun secureConnectStart(call: Call) {
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} TLS handshake start")
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} TLS handshake end: $handshake")
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} TCP connect end (protocol=$protocol)")
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        Log.e(
            BOARD_HTTP_LOG_TAG,
            "[$label] ${elapsed()} TCP connect FAILED to $inetSocketAddress: " +
                "${ioe::class.java.name}: ${ioe.message}",
            ioe,
        )
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        Log.d(
            BOARD_HTTP_LOG_TAG,
            "[$label] ${elapsed()} connection acquired, " +
                "local=${connection.socket().localSocketAddress}",
        )
    }

    override fun connectionReleased(call: Call, connection: Connection) {
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} connection released")
    }

    override fun requestHeadersStart(call: Call) {
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} sending request headers")
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        Log.d(
            BOARD_HTTP_LOG_TAG,
            "[$label] ${elapsed()} request headers sent " +
                "(authenticated=${request.header("Authorization") != null})",
        )
    }

    override fun requestBodyStart(call: Call) {
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} sending request body")
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} request body sent ($byteCount bytes)")
    }

    override fun requestFailed(call: Call, ioe: IOException) {
        Log.e(
            BOARD_HTTP_LOG_TAG,
            "[$label] ${elapsed()} sending the request FAILED: ${ioe::class.java.name}: ${ioe.message}",
            ioe,
        )
    }

    override fun responseHeadersStart(call: Call) {
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} waiting for response headers")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} response headers received: HTTP ${response.code}")
    }

    override fun responseBodyStart(call: Call) {
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} reading response body")
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} response body read ($byteCount bytes)")
    }

    override fun responseFailed(call: Call, ioe: IOException) {
        Log.e(
            BOARD_HTTP_LOG_TAG,
            "[$label] ${elapsed()} reading the response FAILED: ${ioe::class.java.name}: ${ioe.message}",
            ioe,
        )
    }

    override fun canceled(call: Call) {
        Log.w(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} call cancelled")
    }

    override fun retryDecision(call: Call, exception: IOException, retry: Boolean) {
        Log.w(
            BOARD_HTTP_LOG_TAG,
            "[$label] ${elapsed()} retry decision after ${exception::class.java.simpleName}: " +
                "retry=$retry (retryOnConnectionFailure is off, so this should stay false)",
        )
    }

    override fun callEnd(call: Call) {
        Log.d(BOARD_HTTP_LOG_TAG, "[$label] ${elapsed()} call end (success)")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        Log.e(
            BOARD_HTTP_LOG_TAG,
            "[$label] ${elapsed()} call FAILED: ${ioe::class.java.name}: ${ioe.message}",
            ioe,
        )
    }

    companion object {
        /**
         * A fresh listener per call — required, since each instance tracks that one call's start
         * time and label as mutable state.
         */
        val FACTORY: Factory = object : Factory {
            override fun create(call: Call): EventListener = BoardConnectionEventListener()
        }
    }
}
