package com.fakelocation.app

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.MapTileIndex

/** Tile server that uses a URL template with {z} {x} {y} placeholders. */
private class TemplateTileSource(
    name: String,
    maxZoom: Int,
    private val template: String,
    private val servers: List<String> = listOf(""),
    ext: String = ".png",
    copyright: String
) : OnlineTileSourceBase(name, 0, maxZoom, 256, ext, arrayOf(template), copyright) {
    private var n = 0
    override fun getTileURLString(index: Long): String {
        val s = servers[(n++ and 0x7fffffff) % servers.size]
        return template
            .replace("{s}", s)
            .replace("{z}", MapTileIndex.getZoom(index).toString())
            .replace("{x}", MapTileIndex.getX(index).toString())
            .replace("{y}", MapTileIndex.getY(index).toString())
    }
}

private const val ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services"

enum class MapStyle(val label: String) {
    DETAILED("🏙️ Detailed (most place names)"),
    STANDARD("🗺️ Standard (shops & landmarks)"),
    SATELLITE("🛰️ Satellite"),
    HYBRID("🌍 Hybrid (satellite + names)"),
    CLEAN("✨ Clean");

    /** Base map tiles. */
    fun base(): ITileSource = when (this) {
        DETAILED -> TemplateTileSource(
            "EsriStreet", 19, "$ESRI/World_Street_Map/MapServer/tile/{z}/{y}/{x}",
            copyright = "© Esri, OpenStreetMap contributors"
        )
        STANDARD -> TileSourceFactory.MAPNIK
        SATELLITE, HYBRID -> TemplateTileSource(
            "EsriImagery", 19, "$ESRI/World_Imagery/MapServer/tile/{z}/{y}/{x}",
            ext = ".jpg", copyright = "© Esri, Maxar, Earthstar Geographics"
        )
        CLEAN -> TemplateTileSource(
            "CartoVoyager", 20, "https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}@2x.png",
            servers = listOf("a", "b", "c", "d"), copyright = "© OpenStreetMap contributors © CARTO"
        )
    }

    /** Extra label layers drawn on top of the base map (roads & place names for hybrid). */
    fun labels(): List<ITileSource> = when (this) {
        HYBRID -> listOf(
            TemplateTileSource(
                "EsriTransport", 19, "$ESRI/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}",
                copyright = "© Esri"
            ),
            TemplateTileSource(
                "EsriPlaces", 19, "$ESRI/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}",
                copyright = "© Esri"
            )
        )
        else -> emptyList()
    }

    val attribution: String
        get() = when (this) {
            STANDARD -> "© OpenStreetMap contributors"
            CLEAN -> "© OpenStreetMap © CARTO"
            else -> "© Esri · OpenStreetMap"
        }
}
