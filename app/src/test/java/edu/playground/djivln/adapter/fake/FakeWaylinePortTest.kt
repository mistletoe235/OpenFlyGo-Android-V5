package edu.playground.djivln.adapter.fake

import edu.playground.djivln.domain.wayline.WaylinePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FakeWaylinePortTest {
    @Test
    fun uploadExecutePauseResumeShareOneProgressState() {
        val file = File.createTempFile("mission", ".kmz")
        val port = FakeWaylinePort()
        port.start { }

        port.upload(file.absolutePath) { assertTrue(it.isSuccess) }
        port.execute(file.name, listOf(3)) { assertTrue(it.isSuccess) }
        port.advance(4)
        port.pause { assertTrue(it.isSuccess) }
        assertEquals(WaylinePhase.PAUSED, port.state().phase)
        assertEquals(4, port.state().waypointIndex)
        port.resume { assertTrue(it.isSuccess) }
        assertEquals(WaylinePhase.EXECUTING, port.state().phase)
    }
}
