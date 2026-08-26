package com.mrquentinet.matrixcontroller.data.store

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mrquentinet.matrixcontroller.domain.BoardCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DataStoreBoardRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var repository: DataStoreBoardRepository

    @Before
    fun setUp() {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob()),
            produceFile = { folder.newFile("boards.preferences_pb").also { it.delete() } },
        )
        repository = DataStoreBoardRepository(dataStore, PlaintextSecretCipher(), Json)
    }

    @Test
    fun `an added board shows up in the boards flow`() = runTest {
        val created = repository.add("Matrix", "192.168.1.50", 80)!!

        val boards = repository.boards.first()
        assertEquals(1, boards.size)
        assertEquals(created.id, boards[0].id)
        assertEquals("Matrix", boards[0].name)
        assertEquals("192.168.1.50", boards[0].host)
        assertEquals(80, boards[0].port)
    }

    @Test
    fun `credentials round-trip through the cipher`() = runTest {
        val board = repository.add("Matrix", "192.168.1.50", 80)!!
        val credentials = BoardCredentials("0123456789abcdef", "ab".repeat(32))

        repository.saveCredentials(board.id, credentials)

        assertEquals(credentials, repository.credentials(board.id))
    }

    @Test
    fun `a duplicate host and port is refused without duplicating the record`() = runTest {
        repository.add("Matrix", "192.168.1.50", 80)!!

        assertNull(repository.add("Other", "192.168.1.50", 80))
        assertEquals(1, repository.boards.first().size)
    }

    @Test
    fun `the same host on a different port is a different board`() = runTest {
        repository.add("Matrix", "192.168.1.50", 80)!!

        assertTrue(repository.add("Matrix 2", "192.168.1.50", 8080) != null)
        assertEquals(2, repository.boards.first().size)
    }

    @Test
    fun `restore reinstates the board and its credentials`() = runTest {
        val board = repository.add("Matrix", "192.168.1.50", 80)!!
        val credentials = BoardCredentials("0123456789abcdef", "cd".repeat(32))
        repository.saveCredentials(board.id, credentials)

        repository.remove(board.id)
        assertEquals(emptyList<Any>(), repository.boards.first())

        repository.restore(board, credentials)

        assertEquals(listOf(board), repository.boards.first())
        assertEquals(credentials, repository.credentials(board.id))
    }

    @Test
    fun `clearCredentials leaves the board but drops the secret`() = runTest {
        val board = repository.add("Matrix", "192.168.1.50", 80)!!
        repository.saveCredentials(board.id, BoardCredentials("0123456789abcdef", "ef".repeat(32)))

        repository.clearCredentials(board.id)

        assertNull(repository.credentials(board.id))
        assertEquals(1, repository.boards.first().size)
    }

    @Test
    fun `rename keeps the id and the address`() = runTest {
        val board = repository.add("Matrix", "192.168.1.50", 80)!!

        repository.rename(board.id, "Hallway")

        assertEquals(board.copy(name = "Hallway"), repository.boards.first().single())
    }
}
