package edu.playground.djivln.survey

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveRecaptureMissionRegionFilterTest {
    private fun mission(name: String): SurveyMission {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val file = listOf(
            File(root, "testdata/active-recapture/two-buildings/$name"),
            File(requireNotNull(root.parentFile), "testdata/active-recapture/two-buildings/$name"),
        ).first(File::isFile)
        return SurveyMissionJson.decode(file.readText())
    }

    @Test
    fun `selecting one V30 region produces a valid executable sub mission`() {
        val source = mission("openfly-active-recapture-two-buildings-v30-continuous-first.json")
        val region = requireNotNull(source.activeMapping).regions.first()

        val selected = ActiveRecaptureMissionRegionFilter.select(source, setOf(region.regionId))
        val report = ActiveRecaptureMissionValidator.validate(selected)

        assertNotEquals(source.id, selected.id)
        assertEquals(listOf(region.regionId), selected.activeMapping?.regions?.map { it.regionId })
        assertEquals(region.suggestedSurveyPhotos, report.captureCount)
        assertEquals(region.suggestedSurveyPhotos, selected.estimatedPhotoCount)
        assertTrue(selected.waypoints.size < source.waypoints.size)
        assertTrue(selected.activeMapping!!.passes.none {
            it.captureRole != "NONE" && it.regionId != region.regionId
        })
    }

    @Test
    fun `selecting scattered region preserves intervening route as non capturing transit`() {
        val source = mission("openfly-active-recapture-two-buildings-v30-continuous-first.json")
        val region = requireNotNull(source.activeMapping).regions.first {
            it.regionId == "V30_V26_RISK_SCAN_3"
        }

        val selected = ActiveRecaptureMissionRegionFilter.select(source, setOf(region.regionId))
        val report = ActiveRecaptureMissionValidator.validate(selected)

        assertEquals(region.suggestedSurveyPhotos, report.captureCount)
        assertTrue(selected.activeMapping!!.passes.any { it.role == "SELECTION_TRANSIT" })
        assertTrue(selected.surveyPasses().filter { pass ->
            selected.activeMapping!!.passes.first { it.passIndex == pass.start.passIndex }.role == "SELECTION_TRANSIT"
        }.all { it.isTransitOnly })
    }

    @Test
    fun `selecting all regions keeps original mission unchanged`() {
        val source = mission("openfly-active-recapture-two-buildings-v30-continuous-first.json")
        val regionIds = requireNotNull(source.activeMapping).regions.map { it.regionId }.toSet()

        assertSame(source, ActiveRecaptureMissionRegionFilter.select(source, regionIds))
    }

    @Test
    fun `V30 regions are exposed as four flight groups`() {
        val source = mission("openfly-active-recapture-two-buildings-v30-continuous-first.json")

        val groups = ActiveRecaptureMissionGroupCatalog.groups(source)

        assertEquals(4, groups.size)
        assertEquals(
            listOf(
                "West high-rise five-direction",
                "East large-area five-direction",
                "Small cross recapture",
                "Scattered risk recapture",
            ),
            groups.map { it.label },
        )
        assertEquals(23, groups.last().regionIds.size)
        assertEquals(166, groups.last().suggestedSurveyPhotos)
    }

    @Test
    fun `selecting risk scan group includes all scattered risk regions`() {
        val source = mission("openfly-active-recapture-two-buildings-v30-continuous-first.json")
        val riskGroup = ActiveRecaptureMissionGroupCatalog.groups(source).last()

        val selected = ActiveRecaptureMissionRegionFilter.selectGroups(source, setOf(riskGroup.groupId))
        val report = ActiveRecaptureMissionValidator.validate(selected)

        assertEquals(166, report.captureCount)
        assertEquals(riskGroup.regionIds, selected.activeMapping?.regions?.mapTo(hashSetOf()) { it.regionId })
        assertEquals("${source.name} · 1/4 groups", selected.name)
    }

    @Test
    fun `legacy active recapture keeps selected region bridge captures`() {
        val source = mission("openfly-active-recapture-two-buildings-v1.json")
        val region = requireNotNull(source.activeMapping).regions.first { it.regionId == "7" }

        val selected = ActiveRecaptureMissionRegionFilter.select(source, setOf(region.regionId))
        val report = ActiveRecaptureMissionValidator.validate(selected)

        assertTrue(report.captureCount >= region.suggestedSurveyPhotos)
        assertEquals(region.suggestedSurveyPhotos, selected.activeMapping?.surveyCaptureCount)
        assertEquals(
            report.captureCount - region.suggestedSurveyPhotos,
            selected.activeMapping?.bridgeCaptureCount,
        )
    }
}
