package com.example.kachrafreeresident.ui.main

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Shared bits for the app's OpenStreetMap maps (osmdroid): free, with no
 * API key or account. Map tiles come from OpenStreetMap's servers and are
 * cached on the phone after the first view.
 */

/** A MapView tied to this screen: created once, released when it leaves. */
@Composable
fun rememberMapView(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().apply {
            // OpenStreetMap's tile servers ask every app to identify itself.
            userAgentValue = context.packageName
            // Keep the tile cache inside the app: no storage permission needed.
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
        }
    }
    DisposableEffect(mapView) {
        onDispose { mapView.onDetach() }
    }
    return mapView
}

/** A coloured dot marker with a title shown when tapped. */
fun dotMarker(mapView: MapView, context: Context, position: GeoPoint, title: String, color: String): Marker {
    val sizePx = (20 * context.resources.displayMetrics.density).toInt()
    return Marker(mapView).apply {
        this.position = position
        this.title = title
        icon = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(color))
            setStroke(sizePx / 8, Color.WHITE)
            setSize(sizePx, sizePx)
        }
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    }
}

/** OpenStreetMap's licence asks for this credit wherever its map is shown. */
@Composable
fun OsmCredit(modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)) {
        Text(
            "© OpenStreetMap contributors",
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/** The map's current centre as a plain GeoPoint. */
fun MapView.centerPoint(): GeoPoint = GeoPoint(mapCenter.latitude, mapCenter.longitude)
