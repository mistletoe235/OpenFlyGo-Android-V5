package edu.playground.djivln.reconstruction

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class V86ContractTest {
    @Test fun `session explicitly requests continuous schema without changing default`() {
        val stopped = V86SessionConfig("test", 69.73, 25.0, "DJI").toJson()
        assertEquals("[13,14]", stopped.getJSONArray("supported_mission_schemas").toString())
        assertEquals("STOP_AND_CAPTURE", stopped.getString("recapture_flight_mode"))
        val continuous = V86SessionConfig("test", 69.73, 25.0, "DJI",
            recaptureFlightMode = edu.playground.djivln.survey.RecaptureFlightMode.CONTINUOUS_EXPERIMENTAL).toJson()
        assertEquals("CONTINUOUS_EXPERIMENTAL", continuous.getString("recapture_flight_mode"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `relative height test cannot request continuous flight`() {
        V86SessionConfig("test", 69.73, null, "DJI", relativeHeightTest = true,
            recaptureFlightMode = edu.playground.djivln.survey.RecaptureFlightMode.CONTINUOUS_EXPERIMENTAL).toJson()
    }

    @Test
    fun `relative height test keeps asl null and disables mission`() {
        val config = V86SessionConfig("test", 69.73, null, "DJI", relativeHeightTest = true).toJson()
        assertEquals("relative_height_test", config.getString("altitude_mode"))
        org.junit.Assert.assertTrue(config.isNull("takeoff_absolute_altitude_m"))
        org.junit.Assert.assertTrue(config.getBoolean("acknowledge_no_flight"))
        val result = V86Result.decode(JSONObject("""{"session_id":"s-test","test_only":true,
            "openfly_v5_mission":{"url":"unsafe","safe_to_execute":true}}"""))
        assertNull(result.missionUrl)
        assertFalse(result.safeToExecute)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ordinary session still requires asl`() {
        V86SessionConfig("test", 69.73, null, "DJI").toJson()
    }

    @Test
    fun `capture view catalog keys match saved log mission prefixes`() {
        assertEquals("2a18da56-5248-49e9-8a40-cfbc6cb3:13", V86ReplayCaptureViewCatalog.keyForName(
            "20260830_173931_357_2a18da56-5248-49e9-8a40-cfbc6cb3_p13_wp-1_00494.jpg"))
        assertNull(V86ReplayCaptureViewCatalog.keyForName("random.jpg"))
    }
    @Test
    fun `session state exposes incremental fast sfm progress`() {
        val decoded = V86SessionState.decode(JSONObject("""
            {
              "id":"s-test",
              "phase":"fast_sfm",
              "progress":0.1,
              "message":"Fast SfM 80/121 张",
              "image_count":121,
              "fast_sfm_completed_images":80,
              "fast_sfm_target_images":121,
              "fast_sfm_resume_from":40
              ,"sfm_lane_phase":"complete"
              ,"scal3r_lane_phase":"scal3r_geometry"
              ,"scal3r_lane_snapshot_images":80
              ,"scal3r_lane_target_windows":12
              ,"scal3r_lane_completed_windows":7
              ,"scal3r_lane_cache_hits":5
            }
        """))
        assertEquals(80, decoded.fastSfmCompletedImages)
        assertEquals(121, decoded.fastSfmTargetImages)
        assertEquals(40, decoded.fastSfmResumeFrom)
        assertEquals("complete", decoded.sfmLanePhase)
        assertEquals("scal3r_geometry", decoded.scal3rLanePhase)
        assertEquals(12, decoded.scal3rLaneTargetWindows)
        assertEquals(7, decoded.scal3rLaneCompletedWindows)
        assertEquals(5, decoded.scal3rLaneCacheHits)
    }

    @Test
    fun `result keeps fail closed mission and detector semantics`() {
        val decoded = V86Result.decode(JSONObject("""
            {
              "session_id":"s20260828-044730-1d4658",
              "phase":"complete",
              "completed":true,
              "message":"done",
              "geometry_kind":"sfm_sparse_plus_scal3r_ply",
              "point_cloud":{"url":"/api/sessions/s/artifacts/point_cloud.ply"},
              "candidates":{"url":"/api/sessions/s/artifacts/viewer.json","count":49},
              "detectors":{
                "v50":{"tier_a_geometry_gaps":2,"tier_b_review":1,"route_error":null},
                "v78":{"rgb_risk_candidates":49,"selected_after_union":6}
              },
              "openfly_v5_mission":{"url":"/api/sessions/s/artifacts/openfly-survey-mission-schema13.json","schema_version":13,"safe_to_execute":false},
              "error":null,
              "mission_error":null
            }
        """))
        assertEquals("complete", decoded.phase)
        assertFalse(decoded.safeToExecute)
        assertEquals(2, decoded.detectorCounts.v50TierA)
        assertEquals(1, decoded.detectorCounts.v50TierB)
        assertEquals(49, decoded.detectorCounts.v78)
        assertEquals(6, decoded.detectorCounts.v78Selected)
        assertNull(decoded.error)
    }

    @Test
    fun `viewer includes candidates and suggested camera positions`() {
        val markers = decodeV86Candidates("""
            {
              "candidates":[{"detector":"V50","tier":"A","score":0.9,"center_xyz_m":[1,2,3]}],
              "tasks":[{"max_online_risk_score":0.8,"camera_xyz_m":[[4,5,6],[7,8,9]]}]
            }
        """.trimIndent())
        assertEquals(3, markers.size)
        assertEquals("V50", markers[0].detector)
        assertEquals("CAMERA", markers[1].detector)
        assertEquals(7f, markers[2].x)
    }

    @Test
    fun `binary little endian xyz rgb ply decodes and downsamples`() {
        val header = """
            ply
            format binary_little_endian 1.0
            element vertex 4
            property float x
            property float y
            property float z
            property uchar red
            property uchar green
            property uchar blue
            end_header
        """.trimIndent() + "\n"
        val bytes = ByteArrayOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            val body = ByteBuffer.allocate(4 * 15).order(ByteOrder.LITTLE_ENDIAN)
            repeat(4) { index ->
                body.putFloat(index.toFloat())
                body.putFloat((index + 1).toFloat())
                body.putFloat((index + 2).toFloat())
                body.put((10 + index).toByte())
                body.put((20 + index).toByte())
                body.put((30 + index).toByte())
            }
            write(body.array())
        }.toByteArray()
        val cloud = V86PlyDecoder.decode(bytes, maxPoints = 2)
        assertEquals(2, cloud.size)
        assertEquals(0f, cloud.xyz[0])
        assertEquals(2f, cloud.xyz[3])
        assertEquals(0xff0a141e.toInt(), cloud.colors[0])
    }

    @Test
    fun `file backed ply decoder samples the complete cloud without loading the source bytes`() {
        val header = """
            ply
            format binary_little_endian 1.0
            element vertex 5
            property float x
            property float y
            property float z
            property uchar red
            property uchar green
            property uchar blue
            end_header
        """.trimIndent() + "\n"
        val bytes = ByteArrayOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            val body = ByteBuffer.allocate(5 * 15).order(ByteOrder.LITTLE_ENDIAN)
            repeat(5) { index ->
                body.putFloat(index.toFloat())
                body.putFloat((index * 2).toFloat())
                body.putFloat((index * 3).toFloat())
                body.put((40 + index).toByte())
                body.put((50 + index).toByte())
                body.put((60 + index).toByte())
            }
            write(body.array())
        }.toByteArray()
        val file = Files.createTempFile("v86-point-cloud", ".ply").toFile()
        try {
            file.writeBytes(bytes)
            val cloud = V86PlyDecoder.decode(file, maxPoints = 3)
            assertEquals(3, cloud.size)
            assertEquals(0f, cloud.xyz[0])
            assertEquals(2f, cloud.xyz[3])
            assertEquals(4f, cloud.xyz[6])
            assertEquals(0xff2c3640.toInt(), cloud.colors[2])
        } finally {
            file.delete()
        }
    }
}
