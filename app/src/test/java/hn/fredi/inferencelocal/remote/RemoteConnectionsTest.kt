package hn.fredi.inferencelocal.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteConnectionsTest {
    @Test fun createsAndTracksWithoutExposingToken() {
        val registry = RemoteConnectionRegistry(ttlMs = 1000)
        val (token, connection) = registry.create("192.168.9.3", "InferenceLocalClient/1", 100)
        assertNotEquals(token, connection.id)
        assertEquals("192.168.9.3", registry.authenticate(token, 200)?.remoteHost)
        assertEquals(1, registry.list(200).size)
    }

    @Test fun revokesAndPurgesSessions() {
        val registry = RemoteConnectionRegistry()
        val first = registry.create("one", "client", 100).second
        registry.create("two", "client", 100)
        assertTrue(registry.revoke(first.id))
        assertEquals(1, registry.list(100).size)
        assertEquals(1, registry.purgeAll())
        assertTrue(registry.list(100).isEmpty())
    }

    @Test fun expiresSessions() {
        val registry = RemoteConnectionRegistry(ttlMs = 10)
        registry.create("one", "client", 100)
        assertTrue(registry.list(111).isEmpty())
    }
}
