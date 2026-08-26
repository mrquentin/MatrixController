package com.mrquentinet.matrixcontroller

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.mrquentinet.matrixcontroller.data.api.OkHttpMatrixApi
import com.mrquentinet.matrixcontroller.data.api.RequestSigner
import com.mrquentinet.matrixcontroller.data.store.AndroidKeystoreSecretCipher
import com.mrquentinet.matrixcontroller.data.store.DataStoreBoardRepository
import com.mrquentinet.matrixcontroller.domain.BoardRepository
import com.mrquentinet.matrixcontroller.domain.MatrixApi
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/** Manual DI graph: one HTTP client, one Json, one repository. */
class AppContainer(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        // Not a nicety: the board's loop() serves one TCP connection at a time, and a second
        // concurrent request to the same host is simply dropped.
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 1 })
        .connectTimeout(3, TimeUnit.SECONDS)
        // The firmware's own per-read timeout is 4 s.
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    val matrixApi: MatrixApi = OkHttpMatrixApi(httpClient, json, RequestSigner())

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { File(context.filesDir, "boards.preferences_pb") },
    )

    val boardRepository: BoardRepository =
        DataStoreBoardRepository(dataStore, AndroidKeystoreSecretCipher(), json)
}
