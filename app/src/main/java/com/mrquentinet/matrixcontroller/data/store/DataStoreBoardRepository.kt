package com.mrquentinet.matrixcontroller.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mrquentinet.matrixcontroller.domain.Board
import com.mrquentinet.matrixcontroller.domain.BoardCredentials
import com.mrquentinet.matrixcontroller.domain.BoardRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val BOARDS_KEY = stringPreferencesKey("boards")

/**
 * Boards live as one JSON array under a single preference key, so every mutation is a single
 * atomic `edit { }` read-modify-write.
 */
class DataStoreBoardRepository(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val json: Json,
) : BoardRepository {

    override val boards: Flow<List<Board>> =
        dataStore.data.map { preferences -> decode(preferences).map(StoredBoard::toDomain) }

    override suspend fun credentials(boardId: String): BoardCredentials? {
        val record = records().firstOrNull { it.id == boardId } ?: return null
        val clientId = record.clientId ?: return null
        val sealed = record.sealedSecret ?: return null
        val secretHex = cipher.open(sealed) ?: return null
        return BoardCredentials(clientId = clientId, secretHex = secretHex)
    }

    override suspend fun add(name: String, host: String, port: Int): Board? {
        var created: Board? = null
        dataStore.edit { preferences ->
            val existing = decode(preferences)
            val duplicate = existing.any {
                it.host.equals(host, ignoreCase = true) && it.port == port
            }
            if (duplicate) return@edit
            val record = StoredBoard(
                id = UUID.randomUUID().toString(),
                name = name,
                host = host,
                port = port,
            )
            created = record.toDomain()
            preferences.write(existing + record)
        }
        return created
    }

    override suspend fun rename(boardId: String, name: String) = mutate { records ->
        records.map { if (it.id == boardId) it.copy(name = name) else it }
    }

    override suspend fun saveCredentials(boardId: String, credentials: BoardCredentials) {
        val sealed = cipher.seal(credentials.secretHex)
        mutate { records ->
            records.map {
                if (it.id == boardId) {
                    it.copy(clientId = credentials.clientId, sealedSecret = sealed)
                } else {
                    it
                }
            }
        }
    }

    override suspend fun clearCredentials(boardId: String) = mutate { records ->
        records.map {
            if (it.id == boardId) it.copy(clientId = null, sealedSecret = null) else it
        }
    }

    override suspend fun remove(boardId: String) = mutate { records ->
        records.filterNot { it.id == boardId }
    }

    override suspend fun restore(board: Board, credentials: BoardCredentials?) {
        val sealed = credentials?.let { cipher.seal(it.secretHex) }
        val record = StoredBoard(
            id = board.id,
            name = board.name,
            host = board.host,
            port = board.port,
            clientId = credentials?.clientId,
            sealedSecret = sealed,
        )
        mutate { records ->
            if (records.any { it.id == board.id }) records else records + record
        }
    }

    private suspend fun mutate(transform: (List<StoredBoard>) -> List<StoredBoard>) {
        dataStore.edit { preferences -> preferences.write(transform(decode(preferences))) }
    }

    private suspend fun records(): List<StoredBoard> = decode(dataStore.data.first())

    private fun MutablePreferences.write(records: List<StoredBoard>) {
        this[BOARDS_KEY] = json.encodeToString(records)
    }

    private fun decode(preferences: Preferences): List<StoredBoard> {
        val raw = preferences[BOARDS_KEY] ?: return emptyList()
        return try {
            json.decodeFromString<List<StoredBoard>>(raw)
        } catch (_: SerializationException) {
            emptyList()
        }
    }
}
