package hn.fredi.inferencelocal.remote

import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.util.UUID

class RemoteJobsTest {
    @Before @After fun idle() { RemoteJobs.state.value?.let { RemoteJobs.finish(it.id,"succeeded","Fin") } }
    private fun id() = UUID.randomUUID().toString()
    @Test fun retryDoesNotExecuteCurrentTransactionTwice() {
        var calls = 0
        val request = id()
        val first = RemoteJobs.submit("intent","estado",request,false) { calls++ }
        RemoteJobs.started(first.id)
        assertEquals(first.id, RemoteJobs.submit("intent","estado",request,true) { calls++ }.id)
        RemoteJobs.finish(first.id,"succeeded","Listo","Resultado")
        assertEquals("Resultado",RemoteJobs.submit("intent","estado",request,false) { calls++ }.result)
        assertEquals(1,calls)
        assertThrows(IllegalArgumentException::class.java) { RemoteJobs.submit("intent","otro",request,false) {} }
    }
    @Test fun rejectsCompetingRemoteAndLocalTasks() {
        assertThrows(RemoteBusy::class.java) { RemoteJobs.submit("load","",id(),true) {} }
        val job = RemoteJobs.submit("load","",id(),false) {}
        assertThrows(RemoteBusy::class.java) { RemoteJobs.submit("unload","",id(),false) {} }
        RemoteJobs.started(job.id)
        assertThrows(RemoteBusy::class.java) { RemoteJobs.submit("unload","",id(),false) {} }
        RemoteJobs.finish("unrelated","succeeded","Wrong")
        assertEquals("running",RemoteJobs.state.value?.status)
        RemoteJobs.finish(job.id,"cancelled","Cancelada")
        assertEquals("cancelled",RemoteJobs.state.value?.status)
    }
    @Test fun validatesUtf8LimitAndRecoversFailedDispatch() {
        assertThrows(IllegalArgumentException::class.java) { RemoteJobs.submit("intent","á".repeat(257),id(),false) {} }
        assertThrows(IllegalArgumentException::class.java) { RemoteJobs.submit("intent"," ",id(),false) {} }
        assertThrows(IllegalArgumentException::class.java) { RemoteJobs.submit("load","","bad",false) {} }
        assertThrows(IllegalStateException::class.java) { RemoteJobs.submit("load","",id(),false) { error("Dispatch failed") } }
        assertEquals("failed",RemoteJobs.state.value?.status)
        val next = RemoteJobs.submit("load","",id(),false) {}
        assertEquals("queued",next.status)
    }
}
