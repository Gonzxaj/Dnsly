package app.dnsly.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.dnsly.core.EngineDriver
import app.dnsly.geo.LocationAccess
import java.util.Locale
import kotlin.math.roundToInt

private val radii = listOf(50, 100, 250, 500, 1000)

private fun radiusLabel(m: Int) = if (m >= 1000) "${m / 1000} km" else "$m m"

private fun coordsLabel(lat: Double, lng: Double) =
    String.format(Locale.US, "%.5f, %.5f", lat, lng)

/** No map (ARCHITECTURE §9): current location + radius, drawn as a schematic. */
@Composable
fun LocationScreen() {
    val ctx = LocalContext.current
    val geo by EngineDriver.geo.collectAsState()
    val radiusIdx = radii.indexOf(geo.radiusM).coerceAtLeast(0)
    var sliderPos by remember { mutableFloatStateOf(radiusIdx.toFloat()) }
    LaunchedEffect(geo.radiusM) { sliderPos = radiusIdx.toFloat() }
    var locating by remember { mutableStateOf(false) }
    var locateFailed by remember { mutableStateOf(false) }

    // "Usar mi ubicación actual": asks FINE/COARSE if needed, then takes a one-shot fix.
    val fetchLocation = {
        locating = true
        locateFailed = false
        LocationAccess.fetchCurrent(ctx) { loc ->
            locating = false
            if (loc != null) EngineDriver.setHomeLocation(ctx, loc.latitude, loc.longitude)
            else locateFailed = true
        }
    }
    val finePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        EngineDriver.syncGeo(ctx)
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) fetchLocation()
    }
    val locate = {
        if (LocationAccess.hasFine(ctx)) fetchLocation()
        else finePermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    // Android 10 grants background location via dialog; 11+ only through app settings.
    val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        EngineDriver.syncGeo(ctx)
    }

    GlassCard {
        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().height(200.dp)) {
                val step = 28.dp.toPx()
                var x = 0f
                while (x < size.width) { drawLine(Palette.outline, Offset(x, 0f), Offset(x, size.height)); x += step }
                var y = 0f
                while (y < size.height) { drawLine(Palette.outline, Offset(0f, y), Offset(size.width, y)); y += step }
                // Circle grows with the radius index so the slider has visible feedback.
                val r = size.height * (0.22f + 0.06f * sliderPos)
                drawCircle(Palette.green.copy(alpha = 0.12f), r)
                drawCircle(Palette.green, r, style = Stroke(2.dp.toPx()))
            }
            IconTile(Ic.Home, Palette.green, 44.dp, circle = true)
        }
        Caption(
            if (geo.hasLocation) "${coordsLabel(geo.lat, geo.lng)} · radio ${radiusLabel(geo.radiusM)}"
            else "Todavía sin ubicación guardada",
        )
        OutlinedButton(onClick = locate, enabled = !locating, modifier = Modifier.fillMaxWidth()) {
            Icon(Ic.Locate, Palette.green, 18.dp)
            Text("  ${if (locating) "Obteniendo ubicación…" else "Usar mi ubicación actual"}", color = Palette.green)
        }
        if (locateFailed) Caption("No se pudo obtener la ubicación. Probá de nuevo con mejor señal.", Palette.orange)
    }

    if (geo.geofenceEnabled && !geo.fineGranted) {
        GlassCard(tint = Palette.orange) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Ic.Alert, Palette.orange, 40.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Permiso de ubicación", style = MaterialTheme.typography.titleMedium, color = Palette.orange)
                    Caption("Sin él el geofencing no funciona y Dnsly te toma como fuera del área.")
                }
            }
            OutlinedButton(onClick = {
                finePermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }, modifier = Modifier.fillMaxWidth()) { Text("Conceder permiso", color = Palette.green) }
        }
    }

    if (geo.geofenceEnabled && geo.fineGranted && !geo.backgroundGranted) {
        GlassCard(tint = Palette.orange) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Ic.Alert, Palette.orange, 40.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Ubicación en segundo plano", style = MaterialTheme.typography.titleMedium, color = Palette.orange)
                    Caption("Para que el geofencing funcione con la app cerrada, Android pide " +
                        "\"Permitir siempre\". Se habilita en Ajustes › Ubicación › Permisos de la app.")
                }
            }
            OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${ctx.packageName}"),
                        ),
                    )
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Abrir ajustes", color = Palette.green) }
        }
    }

    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Radio del área", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Palette.text)
            Text(radiusLabel(geo.radiusM), style = MaterialTheme.typography.titleMedium, color = Palette.text)
        }
        // Persist only when the drag ends: one geofence re-registration per change.
        Slider(
            value = sliderPos,
            onValueChange = { sliderPos = it },
            onValueChangeFinished = { EngineDriver.setRadius(ctx, radii[sliderPos.roundToInt()]) },
            valueRange = 0f..(radii.size - 1).toFloat(),
            steps = radii.size - 2,
            colors = SliderDefaults.colors(
                thumbColor = Palette.green,
                activeTrackColor = Palette.green,
                inactiveTrackColor = Palette.outline,
                activeTickColor = Palette.background,
                inactiveTickColor = Palette.muted,
            ),
            modifier = Modifier.height(0.dp),
        )
        Row {
            radii.forEach { Caption(radiusLabel(it)); if (it != radii.last()) Box(Modifier.weight(1f)) }
        }
        if (geo.radiusM < 100) Caption("Con menos de 100 m la entrada y salida puede tardar o fallar en interiores.", Palette.orange)
    }

    GlassCard {
        Label("Estado")
        Value(if (geo.inside) "Dentro del área" else "Fuera del área", if (geo.inside) Palette.green else Palette.gray)
        Caption(
            when {
                geo.inside && geo.insideAtMs > 0 ->
                    "Última transición: ${java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(geo.insideAtMs))}"
                geo.inside -> "Sin transiciones registradas todavía"
                geo.geofenceEnabled && !geo.backgroundGranted ->
                    "Android necesita \"ubicación en segundo plano\" (Permitir siempre) para el geofencing."
                geo.geofenceEnabled && !geo.fineGranted ->
                    "Falta el permiso de ubicación."
                else -> "Entrá al área delimitada para activar el DNS."
            },
        )
    }

    SwitchCard("Geofencing", "Activa el control por ubicación", geo.geofenceEnabled) {
        EngineDriver.setGeofenceEnabled(ctx, it)
    }
}
