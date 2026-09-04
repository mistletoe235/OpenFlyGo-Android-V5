package edu.playground.djivln.survey

import mil.nga.tiff.FieldTagType
import mil.nga.tiff.FieldType
import mil.nga.tiff.FileDirectory
import mil.nga.tiff.Rasters
import mil.nga.tiff.TIFFImage
import mil.nga.tiff.TiffWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class GeoTiffTerrainTest {
    @Test
    fun `reads WGS84 GeoTIFF and bilinearly samples elevation`() {
        val rasters = Rasters(2, 2, 1, FieldType.FLOAT)
        rasters.setFirstPixelSample(0, 0, 100f)
        rasters.setFirstPixelSample(1, 0, 110f)
        rasters.setFirstPixelSample(0, 1, 120f)
        rasters.setFirstPixelSample(1, 1, 130f)
        val directory = FileDirectory(rasters)
        directory.setImageWidth(2)
        directory.setImageHeight(2)
        directory.setBitsPerSample(32)
        directory.setSamplesPerPixel(1)
        directory.setSampleFormat(3)
        directory.setPlanarConfiguration(1)
        directory.setRowsPerStrip(2)
        directory.setCompression(8) // Deflate, common in DSM exports.
        directory.setPhotometricInterpretation(1)
        directory.setModelPixelScale(listOf(0.001, 0.001, 0.0))
        directory.setModelTiepoint(listOf(0.0, 0.0, 0.0, 121.0, 31.0, 0.0))
        // Header + one GeographicTypeGeoKey = EPSG:4326.
        directory.setUnsignedIntegerListEntryValue(
            FieldTagType.GeoKeyDirectory,
            listOf(1, 1, 0, 1, 2048, 0, 1, 4326),
        )
        val bytes = TiffWriter.writeTiffToBytes(TIFFImage(directory))

        val terrain = GeoTiffTerrain.read(ByteArrayInputStream(bytes), "buildings-dsm.tif")

        assertEquals(4326, terrain.info.epsg)
        assertEquals(100.0, terrain.elevationMeters(30.9995, 121.0005), 1e-4)
        assertEquals(115.0, terrain.elevationMeters(30.999, 121.001), 1e-4)
        assertTrue(terrain.previewGrid(4, 3).all { it.isFinite() })
    }

    @Test
    fun `reads public Environment Agency building DSM`() {
        val sample = File("../testdata/terrain/ea-london-dsm-1m.tif")
        assertTrue("Missing real DSM regression fixture: ${sample.absolutePath}", sample.isFile)

        val terrain = sample.inputStream().use {
            GeoTiffTerrain.read(it, sample.name)
        }
        val elevations = terrain.previewGrid(96, 96).filter(Double::isFinite)

        assertEquals(4326, terrain.info.epsg)
        assertEquals(200, terrain.info.width)
        assertEquals(200, terrain.info.height)
        assertTrue(terrain.info.minimumLatitude in 51.50..51.51)
        assertTrue(terrain.info.maximumLatitude in 51.50..51.51)
        assertTrue(terrain.info.minimumLongitude in -0.15..-0.10)
        assertTrue(terrain.info.maximumLongitude in -0.15..-0.10)
        // Public composites may retain small NoData holes; they must remain visible
        // to the safety gate instead of being silently filled by the reader.
        assertTrue(elevations.size >= 96 * 96 * 0.90)
        // The central-London surface tile contains roofs and street-level surfaces.
        assertTrue(elevations.max() - elevations.min() > 10.0)
    }
}
