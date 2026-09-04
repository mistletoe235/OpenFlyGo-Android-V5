package edu.playground.djivln.survey

import mil.nga.tiff.FieldTagType
import mil.nga.tiff.FieldType
import mil.nga.tiff.FileDirectory
import mil.nga.tiff.Rasters
import mil.nga.tiff.TIFFImage
import mil.nga.tiff.TiffWriter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class TiledTerrainElevationSourceTest {
    @Test
    fun `samples adjacent static height tiles locally`() {
        val west = constantTile(121.0, 31.0, 10f, "west")
        val east = constantTile(121.002, 31.0, 25f, "east")
        val mosaic = TiledTerrainElevationSource(listOf(west, east), "height mosaic")

        assertEquals(10.0, mosaic.elevationMeters(30.999, 121.001), 1e-5)
        assertEquals(25.0, mosaic.elevationMeters(30.999, 121.003), 1e-5)
        assertEquals(121.0, mosaic.info.minimumLongitude, 1e-6)
        assertEquals(121.004, mosaic.info.maximumLongitude, 1e-6)
    }

    private fun constantTile(west: Double, north: Double, value: Float, name: String): GeoTiffTerrain {
        val rasters = Rasters(2, 2, 1, FieldType.FLOAT)
        repeat(2) { y -> repeat(2) { x -> rasters.setFirstPixelSample(x, y, value) } }
        val directory = FileDirectory(rasters)
        directory.setImageWidth(2)
        directory.setImageHeight(2)
        directory.setBitsPerSample(32)
        directory.setSamplesPerPixel(1)
        directory.setSampleFormat(3)
        directory.setPlanarConfiguration(1)
        directory.setRowsPerStrip(2)
        directory.setCompression(8)
        directory.setPhotometricInterpretation(1)
        directory.setModelPixelScale(listOf(0.001, 0.001, 0.0))
        directory.setModelTiepoint(listOf(0.0, 0.0, 0.0, west, north, 0.0))
        directory.setUnsignedIntegerListEntryValue(
            FieldTagType.GeoKeyDirectory,
            listOf(1, 1, 0, 1, 2048, 0, 1, 4326),
        )
        val bytes = TiffWriter.writeTiffToBytes(TIFFImage(directory))
        return GeoTiffTerrain.read(ByteArrayInputStream(bytes), name)
    }
}
