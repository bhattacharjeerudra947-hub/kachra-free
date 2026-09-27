package com.example.kachrafreeresident.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.kachrafreeresident.R
import com.example.kachrafreeresident.ServerApi
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/**
 * The main page once registered (CLAUDE.md section 5): a full-screen map
 * with the truck, its numbered stops (dustbins), the road it's expected to
 * take and the resident's house (pin). Over it: the server status and a
 * settings button at the top, the ETA card at the bottom.
 *
 * status is the last status that arrived, so the map stays filled in while
 * the server is briefly unreachable (the pill at the top says so).
 */
@Composable
fun HomeScreen(
    status: ServerApi.TruckStatus?,
    serverReachable: Boolean?,
    houseLatLng: GeoPoint,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val mapView = rememberMapView()
    // Frame everything once, when the first status arrives. Not on every
    // update: that would yank the map away from wherever the user moved it.
    var framed by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.apply {
                    controller.setZoom(16.0)
                    controller.setCenter(houseLatLng)
                }
            },
            // Runs again whenever new status arrives: redraw everything.
            update = { map ->
                drawEverything(map, context, status, houseLatLng)
                if (!framed && status != null && status.stops.isNotEmpty()) {
                    framed = true
                    map.post { frameAll(map, status, houseLatLng) }
                }
            }
        )

        // ---- Top: server status + settings ----
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ServerIndicator(serverReachable)
            Spacer(modifier = Modifier.weight(1f))
            Surface(shape = CircleShape, shadowElevation = 4.dp) {
                FilledTonalIconButton(onClick = onOpenSettings) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                }
            }
        }

        // ---- Bottom: map credit + "show everything" button, then the ETA card ----
        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OsmCredit()
                Spacer(modifier = Modifier.weight(1f))
                SmallFloatingActionButton(onClick = { frameAll(mapView, status, houseLatLng) }) {
                    Icon(painterResource(R.drawable.ic_my_location), contentDescription = "Show everything")
                }
            }
            EtaCard(status, serverReachable)
        }
    }
}

// ------------------------------------------------------------- the map --

/** Clears the map and draws the route, stops, house and truck, in that
 *  order (later ones on top). */
private fun drawEverything(map: MapView, context: android.content.Context,
                           status: ServerApi.TruckStatus?, houseLatLng: GeoPoint) {
    map.overlays.clear()

    if (status != null) {
        if (status.path.size >= 2) {
            val road = Polyline(map)
            road.setPoints(status.path)
            road.outlinePaint.color = ROUTE_COLOR
            road.outlinePaint.strokeWidth = 12f
            road.outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            map.overlays.add(road)
        }

        for (i in status.stops.indices) {
            val stop = status.stops[i]
            val isMine = i == status.stopIndex
            var title = "${i + 1}. ${stop.name}"
            if (isMine) title += " (your collection point)"
            if (stop.status == "collected") title += " - collected today"
            if (stop.status == "skipped") title += " - skipped today"
            val icon = stopIcon(context, i + 1, isMine, stop.status)
            map.overlays.add(iconMarker(map, stop.position, title, icon, STOP_ANCHOR_X, STOP_ANCHOR_Y))
        }
    }

    map.overlays.add(iconMarker(map, houseLatLng, "Your home", homeIcon(context), HOME_ANCHOR_X, HOME_ANCHOR_Y))

    val truck = status?.truckPosition
    if (truck != null) {
        map.overlays.add(iconMarker(map, truck, "Garbage truck", truckIcon(context), 0.5f, 0.5f))
    }

    map.invalidate()
}

/** Zooms the map to show the house, the truck, the resident's stop and the route. */
private fun frameAll(map: MapView, status: ServerApi.TruckStatus?, houseLatLng: GeoPoint) {
    val points = mutableListOf(houseLatLng)
    if (status != null) {
        val truck = status.truckPosition
        if (truck != null) points.add(truck)
        val mine = status.stopIndex
        if (mine != null) points.add(status.stops[mine].position)
        points.addAll(status.path)
    }
    if (points.size == 1) {
        map.controller.animateTo(houseLatLng, 16.0, 600L)
        return
    }
    map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(points), true, 160)
}

// ------------------------------------------------------------ ETA card --

/** What the card shows for one status. */
private class CardText(
    val big: String,       // e.g. "8"
    val unit: String,      // e.g. "min"
    val label: String,     // the coloured pill, e.g. "On the way"
    val labelColor: Color,
    val line: String       // e.g. "2 stops before yours"
)

private val BLUE = Color(0xFF1C7ED6)
private val GREEN = Color(0xFF2F9E44)
private val GREY = Color(0xFF868E96)
private val RED = Color(0xFFE03131)
private val AMBER = Color(0xFFE67700)

private fun cardText(status: ServerApi.TruckStatus?): CardText {
    if (status == null) {
        return CardText("--", "", "Waiting", GREY, "Waiting for the first update from the server…")
    }
    if (status.notRegistered) {
        return CardText("--", "", "Registering", GREY, "Sending your registration to the server…")
    }

    when (status.status) {
        "on_the_way" -> {
            var big = status.etaMinutes.toString()
            if (status.etaIsRough) big = "~$big"
            val stopsAway = status.stopsAway ?: 0
            var line = if (stopsAway == 0) {
                "Your stop is next"
            } else if (stopsAway == 1) {
                "1 stop before yours"
            } else {
                "$stopsAway stops before yours"
            }
            if (status.etaIsRough) line += " · rough estimate"
            return CardText(big, "min", "On the way", BLUE, line)
        }
        "at_stop" -> return CardText("Now", "", "At your stop", GREEN, "Bring your garbage out!")
        "collected" -> return CardText("Done", "", "Collected", GREEN, "Garbage was collected at your stop today")
        "skipped" -> return CardText("Missed", "", "Skipped", AMBER, "The truck skipped your stop today")
        "truck_offline" -> {
            var line = "The truck isn't sharing its location right now"
            if (status.usualTime != null) line = "Usually at your stop around ${status.usualTime}"
            return CardText("--", "", "Not on the road", GREY, line)
        }
        else -> return CardText("--", "", "Needs attention", RED, status.message ?: "")
    }
}

@Composable
private fun EtaCard(status: ServerApi.TruckStatus?, serverReachable: Boolean?) {
    val text = cardText(status)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 16.dp
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {

            // A small handle, the usual "this is a sheet" hint.
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
            )

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text.big,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold
                )
                if (text.unit.isNotEmpty()) {
                    Text(
                        " ${text.unit}",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    modifier = Modifier.padding(bottom = 10.dp),
                    shape = RoundedCornerShape(50),
                    color = text.labelColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text.label,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = text.labelColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Text(text.line, style = MaterialTheme.typography.titleMedium)

            // Where to bring the garbage.
            if (status?.stopName != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_bin),
                        contentDescription = null,
                        tint = Color(MY_STOP_COLOR),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    var where = status.stopName
                    // Name it by its number too, unless the name already is "Stop N".
                    val number = "Stop ${(status.stopIndex ?: 0) + 1}"
                    if (where != number) where = "$number · $where"
                    if (status.stopDistanceMeters != null) where += " · ${status.stopDistanceMeters} m from home"
                    Text(
                        where,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (serverReachable == false && status != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Server offline · showing the last update",
                    style = MaterialTheme.typography.bodySmall,
                    color = RED
                )
            }
        }
    }
}
