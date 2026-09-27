package com.example.kachrafreeresident.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.kachrafreeresident.R
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
 *
 * Map markers are drawn from the app's vector icons (res/drawable) onto
 * small bitmaps, since osmdroid markers take a picture, not a Compose icon.
 */

// Marker colours.
const val TRUCK_COLOR = 0xFFF76707.toInt()     // orange
const val STOP_COLOR = 0xFF495057.toInt()      // dark grey
const val MY_STOP_COLOR = 0xFF2F9E44.toInt()   // green: the resident's own stop
const val ROUTE_COLOR = 0xFF1C7ED6.toInt()     // blue
const val HOME_COLOR = 0xFFE03131.toInt()      // red

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

// ------------------------------------------------------------ markers --

/** A marker with a picture, a title shown when tapped, and its anchor point
 *  (0..1 across and down the picture: which point sits on the location). */
fun iconMarker(
    map: MapView,
    position: GeoPoint,
    title: String,
    icon: BitmapDrawable,
    anchorX: Float,
    anchorY: Float
): Marker {
    val marker = Marker(map)
    marker.position = position
    marker.title = title
    marker.icon = icon
    marker.setAnchor(anchorX, anchorY)
    return marker
}

private fun dp(context: Context, value: Float): Float {
    return value * context.resources.displayMetrics.density
}

/** Draws one of the app's vector icons, in a colour, into a square on the canvas. */
private fun drawVector(context: Context, canvas: Canvas, iconRes: Int, color: Int,
                       centerX: Float, centerY: Float, size: Float) {
    val icon = ContextCompat.getDrawable(context, iconRes)!!.mutate()
    icon.setTint(color)
    val half = size / 2
    icon.setBounds((centerX - half).toInt(), (centerY - half).toInt(), (centerX + half).toInt(), (centerY + half).toInt())
    icon.draw(canvas)
}

/** A filled circle with a white border and a soft shadow ring. */
private fun drawBadgeCircle(context: Context, canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float, color: Int) {
    paint.color = 0x33000000                     // shadow
    canvas.drawCircle(cx, cy + dp(context, 1f), radius + dp(context, 1f), paint)
    paint.color = Color.WHITE                    // border
    canvas.drawCircle(cx, cy, radius, paint)
    paint.color = color                          // fill
    canvas.drawCircle(cx, cy, radius - dp(context, 2f), paint)
}

/** The truck: a white truck icon on an orange circle. Anchor: its centre. */
fun truckIcon(context: Context): BitmapDrawable {
    val size = dp(context, 40f)
    val bitmap = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val middle = size / 2
    drawBadgeCircle(context, canvas, paint, middle, middle, middle - dp(context, 2f), TRUCK_COLOR)
    drawVector(context, canvas, R.drawable.ic_truck, Color.WHITE, middle, middle, dp(context, 22f))
    return BitmapDrawable(context.resources, bitmap)
}

/**
 * A stop: a white dustbin on a circle, with the stop's number in a small
 * bubble at its top-right, and today's status in a bubble at its top-left:
 * a green tick when collected, a yellow "!" when skipped. The resident's
 * own stop is green and a bit bigger. Anchor: the centre of the big
 * circle, STOP_ANCHOR_X / Y.
 */
fun stopIcon(context: Context, number: Int, isMine: Boolean, status: String): BitmapDrawable {
    val scale = if (isMine) 1.25f else 1f
    val width = dp(context, 46f * scale)
    val height = dp(context, 36f * scale)
    val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // The big circle with the bin.
    val cx = width * STOP_ANCHOR_X
    val cy = height * STOP_ANCHOR_Y
    val radius = dp(context, 14f * scale)
    val color = if (isMine) MY_STOP_COLOR else STOP_COLOR
    drawBadgeCircle(context, canvas, paint, cx, cy, radius, color)
    drawVector(context, canvas, R.drawable.ic_bin, Color.WHITE, cx, cy, dp(context, 16f * scale))

    // The number bubble, top-right.
    val bubbleRadius = dp(context, 7.5f * scale)
    val top = dp(context, 8f * scale)
    val right = width - dp(context, 8f * scale)
    val left = dp(context, 8f * scale)
    drawBubble(context, canvas, paint, right, top, bubbleRadius, Color.WHITE, color)
    val textSize = if (number < 10) 10f * scale else 8f * scale
    drawCentredText(context, canvas, paint, number.toString(), right, top, textSize, color)

    // The status bubble, top-left.
    if (status == "collected") {
        drawBubble(context, canvas, paint, left, top, bubbleRadius, DONE_COLOR, Color.WHITE)
        drawTick(context, canvas, left, top, bubbleRadius)
    } else if (status == "skipped") {
        drawBubble(context, canvas, paint, left, top, bubbleRadius, SKIPPED_COLOR, Color.WHITE)
        drawCentredText(context, canvas, paint, "!", left, top, 10f * scale, Color.BLACK)
    }

    return BitmapDrawable(context.resources, bitmap)
}

// Where a stop icon's big circle is, as a fraction of the picture.
const val STOP_ANCHOR_X = 0.5f
const val STOP_ANCHOR_Y = 20f / 36f

const val DONE_COLOR = 0xFF2F9E44.toInt()     // green tick bubble: collected
const val SKIPPED_COLOR = 0xFFFCC419.toInt()  // yellow "!" bubble: skipped

/** A small filled circle with a thin border in another colour. */
private fun drawBubble(context: Context, canvas: Canvas, paint: Paint,
                       x: Float, y: Float, radius: Float, fill: Int, border: Int) {
    paint.style = Paint.Style.FILL
    paint.color = border
    canvas.drawCircle(x, y, radius, paint)
    paint.color = fill
    canvas.drawCircle(x, y, radius - dp(context, 1.5f), paint)
}

private fun drawCentredText(context: Context, canvas: Canvas, paint: Paint,
                            text: String, x: Float, y: Float, sizeDp: Float, color: Int) {
    paint.style = Paint.Style.FILL
    paint.color = color
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textSize = dp(context, sizeDp)
    // drawText's y is the text's baseline: move it down by half the text height.
    val baseline = y - (paint.descent() + paint.ascent()) / 2
    canvas.drawText(text, x, baseline, paint)
}

/** A white tick mark centred on (x, y). */
private fun drawTick(context: Context, canvas: Canvas, x: Float, y: Float, radius: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.style = Paint.Style.STROKE
    paint.color = Color.WHITE
    paint.strokeWidth = dp(context, 1.8f)
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    val size = radius * 0.5f
    val tick = android.graphics.Path()
    tick.moveTo(x - size, y)
    tick.lineTo(x - size * 0.2f, y + size * 0.7f)
    tick.lineTo(x + size, y - size * 0.6f)
    canvas.drawPath(tick, paint)
}

/** The house: a red map pin. Anchor: its tip, HOME_ANCHOR_X / Y. */
fun homeIcon(context: Context): BitmapDrawable {
    val size = dp(context, 44f)
    val bitmap = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    // White behind the pin's round hole (at 12, 9 in the 24-unit vector),
    // so the map doesn't show through it.
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.WHITE
    canvas.drawCircle(size * 12f / 24f, size * 9f / 24f, size * 3.5f / 24f, paint)
    drawVector(context, canvas, R.drawable.ic_pin, HOME_COLOR, size / 2, size / 2, size)
    return BitmapDrawable(context.resources, bitmap)
}

// The pin's tip is at the bottom middle (y = 22 of 24 in the vector).
const val HOME_ANCHOR_X = 0.5f
const val HOME_ANCHOR_Y = 22f / 24f
