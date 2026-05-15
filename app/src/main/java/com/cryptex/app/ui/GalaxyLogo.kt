package com.cryptex.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.random.Random

@Composable
fun GalaxyLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(400.dp)
            .clip(RoundedCornerShape(80.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFBA28B1),
                        Color(0xFFF75B3D)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        GalaxySContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(40.dp)
        )
    }
}

@Composable
private fun GalaxySContent(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.aspectRatio(1f)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        // Subtle background stars
        drawBackgroundStars(w, h)

        // Bottom-right darker swoosh (Shadow/Depth layer)
        val bottomSwoosh = Path().apply {
            moveTo(w * 0.15f, h * 0.6f)
            cubicTo(
                w * 0.25f, h * 0.95f,
                w * 0.75f, h * 0.95f,
                w * 0.85f, h * 0.65f
            )
            cubicTo(
                w * 0.95f, h * 0.35f,
                w * 0.65f, h * 0.25f,
                cx, cy
            )
            quadraticBezierTo(
                w * 0.45f, h * 0.65f,
                w * 0.15f, h * 0.6f
            )
        }

        drawPath(
            path = bottomSwoosh,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF512DA8).copy(alpha = 0.8f),
                    Color(0xFFFF5722).copy(alpha = 0.9f)
                ),
                start = Offset(cx, cy),
                end = Offset(w * 0.8f, h * 0.8f)
            )
        )

        // Top-left white swoosh (Main body)
        val topSwoosh = Path().apply {
            moveTo(w * 0.85f, h * 0.4f)
            cubicTo(
                w * 0.75f, h * 0.05f,
                w * 0.25f, h * 0.05f,
                w * 0.15f, h * 0.35f
            )
            cubicTo(
                w * 0.05f, h * 0.65f,
                w * 0.35f, h * 0.75f,
                cx, cy
            )
            quadraticBezierTo(
                w * 0.55f, h * 0.35f,
                w * 0.85f, h * 0.4f
            )
        }

        drawPath(
            path = topSwoosh,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.95f),
                    Color(0xFFE1BEE7).copy(alpha = 0.5f)
                ),
                start = Offset(w * 0.2f, h * 0.2f),
                end = Offset(cx, cy)
            )
        )

        // Inner Glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFFEB3B).copy(alpha = 0.5f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = w * 0.25f
            )
        )

        // Central Star
        drawCentralStar(cx, cy, w * 0.15f)
    }
}

private fun DrawScope.drawBackgroundStars(w: Float, h: Float) {
    val random = Random(42)
    repeat(30) {
        val x = random.nextFloat() * w
        val y = random.nextFloat() * h
        val radius = random.nextFloat() * 2f + 1f
        val alpha = random.nextFloat() * 0.6f + 0.2f
        drawCircle(
            color = Color.White.copy(alpha = alpha),
            radius = radius,
            center = Offset(x, y)
        )
    }
}

private fun DrawScope.drawCentralStar(cx: Float, cy: Float, size: Float) {
    // 4-pointed star core
    val starPath = Path().apply {
        moveTo(cx, cy - size)
        quadraticBezierTo(cx + size * 0.1f, cy - size * 0.1f, cx + size, cy)
        quadraticBezierTo(cx + size * 0.1f, cy + size * 0.1f, cx, cy + size)
        quadraticBezierTo(cx - size * 0.1f, cy + size * 0.1f, cx - size, cy)
        quadraticBezierTo(cx - size * 0.1f, cy - size * 0.1f, cx, cy - size)
    }

    drawPath(path = starPath, color = Color.White)

    // Glowing lines
    rotate(45f, Offset(cx, cy)) {
        val lineLength = size * 3f
        val lineThickness = 2.dp.toPx()
        
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.Transparent),
                center = Offset(cx, cy),
                radius = lineLength / 2f
            ),
            topLeft = Offset(cx - lineLength / 2, cy - lineThickness / 2),
            size = Size(lineLength, lineThickness)
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.Transparent),
                center = Offset(cx, cy),
                radius = lineLength / 2f
            ),
            topLeft = Offset(cx - lineThickness / 2, cy - lineLength / 2),
            size = Size(lineThickness, lineLength)
        )
    }

    // Small bright core
    drawCircle(
        color = Color(0xFFFFEB3B),
        radius = size * 0.25f,
        center = Offset(cx, cy)
    )
}

@Preview(showBackground = true)
@Composable
fun GalaxyLogoPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        GalaxyLogo()
    }
}
