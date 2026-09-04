package edu.playground.djivln.domain.camera

import edu.playground.djivln.adapter.fake.FakeCameraFrameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSourceRouterTest {
    @Test
    fun forwardsOnlyFramesFromSelectedSource() {
        val dji = FakeCameraFrameSource("dji")
        val ue = FakeCameraFrameSource("ue")
        val router = CameraSourceRouter(listOf(dji, ue))
        val received = mutableListOf<CameraFrame>()
        router.start { received += it }

        router.select("dji")
        ue.emitRgba(1, 1)
        dji.emitRgba(2, 2)

        assertEquals(listOf("dji"), received.map { it.sourceId })
        assertEquals(2, router.latestFrame()?.width)
    }

    @Test
    fun switchingSourcesClearsStaleFrame() {
        val dji = FakeCameraFrameSource("dji")
        val ue = FakeCameraFrameSource("ue")
        val router = CameraSourceRouter(listOf(dji, ue))
        router.start { }
        router.select("dji")
        dji.emitRgba(2, 2)

        router.select("ue")

        assertNull(router.latestFrame())
        ue.emitRgba(3, 3)
        assertEquals("ue", router.latestFrame()?.sourceId)
    }

    @Test
    fun latestFrameReturnsDefensiveSnapshot() {
        val source = FakeCameraFrameSource("fake")
        val router = CameraSourceRouter(listOf(source))
        router.start { }
        router.select("fake")
        source.emitRgba(1, 1, byteArrayOf(1, 2, 3, 4))

        val first = router.latestFrame()!!
        first.bytes[0] = 99

        assertTrue(router.latestFrame()!!.bytes.contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun forwardsSourceFrameWithoutAnotherFullFrameCopy() {
        val source = RecordingFrameSource()
        val router = CameraSourceRouter(listOf(source))
        var received: CameraFrame? = null
        router.start { received = it }
        router.select(source.descriptor.id)
        val emitted = CameraFrame(
            sourceId = source.descriptor.id,
            encoding = FrameEncoding.RGBA_8888,
            bytes = byteArrayOf(1, 2, 3, 4),
            width = 1,
            height = 1,
            capturedAtNanos = 1L,
        )

        source.emit(emitted)

        assertSame(emitted, received)
        assertNotSame(emitted, router.latestFrame())
    }

    @Test
    fun onDemandSourceDoesNotProduceUntilExplicitlyRequested() {
        val source = RecordingOnDemandFrameSource()
        val router = CameraSourceRouter(listOf(source))
        val received = mutableListOf<CameraFrame>()
        router.select(source.descriptor.id)
        router.start { received += it }

        assertTrue(router.canRequestFrame())
        assertTrue(received.isEmpty())

        router.requestFrame().getOrThrow()

        assertEquals(1, source.requestCount)
        assertEquals(1, received.size)
        assertEquals("on-demand", router.latestFrame()?.sourceId)
    }

    private class RecordingFrameSource : CameraFrameSource {
        override val descriptor = CameraSourceDescriptor("recording", "recording", CameraSourceKind.FAKE)
        private var listener: CameraFrameListener? = null

        override fun start(listener: CameraFrameListener) {
            this.listener = listener
        }

        override fun stop() {
            listener = null
        }

        override fun latestFrame(): CameraFrame? = null

        fun emit(frame: CameraFrame) {
            listener?.onFrame(frame)
        }
    }

    private class RecordingOnDemandFrameSource : OnDemandCameraFrameSource {
        override val descriptor = CameraSourceDescriptor("on-demand", "on-demand", CameraSourceKind.DJI)
        private var listener: CameraFrameListener? = null
        var requestCount = 0

        override fun start(listener: CameraFrameListener) {
            this.listener = listener
        }

        override fun stop() {
            listener = null
        }

        override fun latestFrame(): CameraFrame? = null

        override fun requestFrame(): Result<Unit> = runCatching {
            requestCount += 1
            listener?.onFrame(
                CameraFrame(
                    sourceId = descriptor.id,
                    encoding = FrameEncoding.RGBA_8888,
                    bytes = byteArrayOf(1, 2, 3, 4),
                    width = 1,
                    height = 1,
                    capturedAtNanos = 1L,
                ),
            ) ?: error("source not started")
        }

        override fun cancelFrameRequest() = Unit
    }
}
