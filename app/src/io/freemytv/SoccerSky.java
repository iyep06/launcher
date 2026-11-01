package io.freemytv;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

/**
 * Procedural vector football (soccer) stadium wallpaper:
 * A grand stadium at night with emerald turf, atmospheric floodlights,
 * perspective goalpost (gawang), and a classic stitched vector soccer ball.
 */
public final class SoccerSky {
    private int width, height;
    private float k = 1f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rectF = new RectF();

    // Reusable particle system for floodlight dust motes
    private static final int MOTE_COUNT = 36;
    private final float[] moteX = new float[MOTE_COUNT];
    private final float[] moteY = new float[MOTE_COUNT];
    private final float[] moteSpeed = new float[MOTE_COUNT];
    private final float[] motePhase = new float[MOTE_COUNT];
    private final float[] moteSize = new float[MOTE_COUNT];

    public SoccerSky() {
        strokePaint.setStyle(Paint.Style.STROKE);
        java.util.Random rnd = new java.util.Random(1927);
        for (int i = 0; i < MOTE_COUNT; i++) {
            moteX[i] = rnd.nextFloat();
            moteY[i] = rnd.nextFloat();
            moteSpeed[i] = 0.04f + rnd.nextFloat() * 0.08f;
            motePhase[i] = rnd.nextFloat() * 6.28f;
            moteSize[i] = 1.2f + rnd.nextFloat() * 2.2f;
        }
    }

    public void setSize(int w, int h) {
        this.width = w;
        this.height = h;
        this.k = w / 1920f;
    }

    public void release() {
        // Nothing heavy to deallocate, purely procedural canvas drawing
    }

    public void draw(Canvas c, int w, int h, long uptimeMs, float parallax, boolean motion) {
        if (w != width || h != height) setSize(w, h);
        float time = motion ? (uptimeMs % 360_000L) / 1000f : 0f;

        // 1. Sky & Stadium upper rim gradient
        drawSkyAndStadiumRim(c, parallax);

        // 2. Stadium Floodlight Beams
        drawFloodlights(c, parallax, time, motion);

        // 3. Grass Turf with Mower Stripes & Perspective Lines
        drawPitch(c, parallax);

        // 4. Perspective Soccer Goal (Gawang)
        drawGoal(c, parallax);

        // 5. Atmospheric floating light motes / dew
        if (motion) {
            drawAtmosphereParticles(c, time, parallax);
        }

        // 6. Detailed 3D Vector Soccer Ball (Bola Sepak)
        drawSoccerBall(c, parallax);
    }

    private void drawSkyAndStadiumRim(Canvas c, float parallax) {
        // Dark midnight stadium sky with emerald haze near horizon
        LinearGradient skyGrad = new LinearGradient(
            0, 0, 0, height * 0.55f,
            0xFF050B14, 0xFF0A1F18,
            Shader.TileMode.CLAMP
        );
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(skyGrad);
        c.drawRect(0, 0, width, height * 0.55f, paint);
        paint.setShader(null);

        // Distant stadium canopy / grandstand truss silhouette (slight parallax)
        float farOffset = -parallax * 18f * k;
        paint.setColor(0xFF081412);
        path.reset();
        path.moveTo(-40 * k + farOffset, height * 0.45f);
        path.quadTo(width * 0.5f + farOffset, height * 0.32f, width + 40 * k + farOffset, height * 0.45f);
        path.lineTo(width + 40 * k + farOffset, height * 0.52f);
        path.lineTo(-40 * k + farOffset, height * 0.52f);
        path.close();
        c.drawPath(path, paint);

        // Truss structural beams silhouette
        strokePaint.setColor(0x331E3D30);
        strokePaint.setStrokeWidth(2f * k);
        for (int i = 0; i < 18; i++) {
            float tx = (i * 115 - 50) * k + farOffset;
            float ty = height * 0.34f + (float) Math.abs(i - 9) * 8 * k;
            c.drawLine(tx, ty, tx + 40 * k, height * 0.48f, strokePaint);
            c.drawLine(tx + 40 * k, ty, tx, height * 0.48f, strokePaint);
        }
    }

    private void drawFloodlights(Canvas c, float parallax, float time, boolean motion) {
        float pOffset = -parallax * 25f * k;
        float pulse = motion ? (float) (0.88f + 0.12f * Math.sin(time * 1.5f)) : 1f;

        // Top-left floodlight tower wash
        LinearGradient leftBeam = new LinearGradient(
            180 * k + pOffset, -20 * k,
            650 * k + pOffset, height * 0.8f,
            new int[] {Ui.alpha(0x70E6F9F0, pulse), Ui.alpha(0x1870DFB0, pulse * 0.7f), 0x00000000},
            new float[] {0f, 0.45f, 1f},
            Shader.TileMode.CLAMP
        );
        paint.setShader(leftBeam);
        path.reset();
        path.moveTo(140 * k + pOffset, 0);
        path.lineTo(240 * k + pOffset, 0);
        path.lineTo(980 * k + pOffset, height);
        path.lineTo(380 * k + pOffset, height);
        path.close();
        c.drawPath(path, paint);

        // Top-right floodlight tower wash
        LinearGradient rightBeam = new LinearGradient(
            (1920 - 180) * k + pOffset, -20 * k,
            (1920 - 650) * k + pOffset, height * 0.8f,
            new int[] {Ui.alpha(0x70E6F9F0, pulse), Ui.alpha(0x1870DFB0, pulse * 0.7f), 0x00000000},
            new float[] {0f, 0.45f, 1f},
            Shader.TileMode.CLAMP
        );
        paint.setShader(rightBeam);
        path.reset();
        path.moveTo((1920 - 240) * k + pOffset, 0);
        path.lineTo((1920 - 140) * k + pOffset, 0);
        path.lineTo((1920 - 380) * k + pOffset, height);
        path.lineTo((1920 - 980) * k + pOffset, height);
        path.close();
        c.drawPath(path, paint);
        paint.setShader(null);

        // Light bulb glare clusters at towers
        paint.setColor(0xE6FFFFFF);
        c.drawCircle(190 * k + pOffset, 25 * k, 12 * k, paint);
        c.drawCircle((1920 - 190) * k + pOffset, 25 * k, 12 * k, paint);
    }

    private void drawPitch(Canvas c, float parallax) {
        float horizonY = height * 0.44f;
        float pitchH = height - horizonY;
        float midOffset = -parallax * 35f * k;

        // Base field gradient: darker near horizon, rich emerald in foreground
        LinearGradient turfGrad = new LinearGradient(
            0, horizonY, 0, height,
            0xFF0D251A, 0xFF143B25,
            Shader.TileMode.CLAMP
        );
        paint.setShader(turfGrad);
        c.drawRect(0, horizonY, width, height, paint);
        paint.setShader(null);

        // Alternating lawnmower stripes radiating with perspective
        paint.setColor(0x14000000); // Shadow stripe
        for (int i = 0; i < 9; i += 2) {
            float topX1 = (width * 0.5f - 480 * k) + i * (960 * k / 9f) + midOffset * 0.5f;
            float topX2 = topX1 + (960 * k / 9f);
            float botX1 = (width * 0.5f - 1100 * k) + i * (2200 * k / 9f) + midOffset;
            float botX2 = botX1 + (2200 * k / 9f);

            path.reset();
            path.moveTo(topX1, horizonY);
            path.lineTo(topX2, horizonY);
            path.lineTo(botX2, height);
            path.lineTo(botX1, height);
            path.close();
            c.drawPath(path, paint);
        }

        // Crisp white pitch lines (with perspective)
        strokePaint.setColor(0x73FFFFFF);
        strokePaint.setStrokeWidth(3.5f * k);

        // Goal line
        float goalLineY = horizonY + pitchH * 0.18f;
        c.drawLine(width * 0.12f + midOffset, goalLineY, width * 0.88f + midOffset, goalLineY, strokePaint);

        // Penalty box outer boundary
        float pBoxTopY = goalLineY;
        float pBoxBottomY = goalLineY + pitchH * 0.38f;
        float pBoxLeftTop = width * 0.28f + midOffset;
        float pBoxRightTop = width * 0.72f + midOffset;
        float pBoxLeftBot = width * 0.20f + midOffset;
        float pBoxRightBot = width * 0.80f + midOffset;

        path.reset();
        path.moveTo(pBoxLeftTop, pBoxTopY);
        path.lineTo(pBoxLeftBot, pBoxBottomY);
        path.lineTo(pBoxRightBot, pBoxBottomY);
        path.lineTo(pBoxRightTop, pBoxTopY);
        c.drawPath(path, strokePaint);

        // Goal area (kotak gawang kecil)
        float gAreaBotY = goalLineY + pitchH * 0.15f;
        float gAreaLeftTop = width * 0.38f + midOffset;
        float gAreaRightTop = width * 0.62f + midOffset;
        float gAreaLeftBot = width * 0.35f + midOffset;
        float gAreaRightBot = width * 0.65f + midOffset;

        path.reset();
        path.moveTo(gAreaLeftTop, pBoxTopY);
        path.lineTo(gAreaLeftBot, gAreaBotY);
        path.lineTo(gAreaRightBot, gAreaBotY);
        path.lineTo(gAreaRightTop, pBoxTopY);
        c.drawPath(path, strokePaint);

        // Penalty spot (titik putih)
        float penSpotY = goalLineY + pitchH * 0.24f;
        paint.setColor(0xCCFFFFFF);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(width * 0.5f + midOffset, penSpotY, 4.5f * k, paint);

        // Penalty arc (busur penalti)
        rectF.set(
            width * 0.42f + midOffset, pBoxBottomY - 45 * k,
            width * 0.58f + midOffset, pBoxBottomY + 45 * k
        );
        c.drawArc(rectF, 0, 180, false, strokePaint);
    }

    private void drawGoal(Canvas c, float parallax) {
        float pOffset = -parallax * 30f * k;
        float cx = width * 0.5f + pOffset;
        float baseY = height * 0.44f + (height - height * 0.44f) * 0.18f; // On goal line

        float goalW = 460 * k;
        float goalH = 175 * k;
        float depthD = 65 * k; // Net depth slanting backward

        float leftX = cx - goalW * 0.5f;
        float rightX = cx + goalW * 0.5f;
        float topY = baseY - goalH;

        float backLeftX = leftX + 45 * k;
        float backRightX = rightX - 45 * k;
        float backTopY = topY + 30 * k;
        float backBaseY = baseY - depthD;

        // 1. Goal Net Mesh (jaring gawang bersudut perspektif)
        strokePaint.setColor(0x35E0F2E9);
        strokePaint.setStrokeWidth(1.2f * k);

        // Vertical net lines (back wall)
        int netCols = 22;
        for (int i = 0; i <= netCols; i++) {
            float frac = (float) i / netCols;
            float topNetX = backLeftX + frac * (backRightX - backLeftX);
            float botNetX = backLeftX + frac * (backRightX - backLeftX);
            c.drawLine(topNetX, backTopY, botNetX, backBaseY, strokePaint);
        }
        // Horizontal net lines (back wall)
        int netRows = 10;
        for (int j = 0; j <= netRows; j++) {
            float frac = (float) j / netRows;
            float netY = backTopY + frac * (backBaseY - backTopY);
            c.drawLine(backLeftX, netY, backRightX, netY, strokePaint);
        }
        // Top net roof lines
        for (int i = 0; i <= netCols; i += 2) {
            float frac = (float) i / netCols;
            float fX = leftX + frac * (rightX - leftX);
            float bX = backLeftX + frac * (backRightX - backLeftX);
            c.drawLine(fX, topY, bX, backTopY, strokePaint);
        }
        // Side net mesh
        for (int j = 0; j <= netRows; j += 2) {
            float frac = (float) j / netRows;
            c.drawLine(leftX, topY + frac * goalH, backLeftX, backTopY + frac * (backBaseY - backTopY), strokePaint);
            c.drawLine(rightX, topY + frac * goalH, backRightX, backTopY + frac * (backBaseY - backTopY), strokePaint);
        }

        // 2. Goal Support Stanchions (tiang penopang belakang)
        strokePaint.setColor(0x80B0C4BE);
        strokePaint.setStrokeWidth(3.5f * k);
        c.drawLine(leftX, topY, backLeftX, backTopY, strokePaint);
        c.drawLine(rightX, topY, backRightX, backTopY, strokePaint);
        c.drawLine(backLeftX, backTopY, backLeftX, backBaseY, strokePaint);
        c.drawLine(backRightX, backTopY, backRightX, backBaseY, strokePaint);
        c.drawLine(backLeftX, backBaseY, backRightX, backBaseY, strokePaint);

        // 3. Goal Frame Posts (Tiang gawang utama warna putih solid)
        strokePaint.setColor(0xFFF7FAF8);
        strokePaint.setStrokeWidth(7.5f * k);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);

        // Left post, Crossbar, Right post
        path.reset();
        path.moveTo(leftX, baseY);
        path.lineTo(leftX, topY);
        path.lineTo(rightX, topY);
        path.lineTo(rightX, baseY);
        c.drawPath(path, strokePaint);
        strokePaint.setStrokeCap(Paint.Cap.BUTT);

        // Post highlights / 3D specular shine
        strokePaint.setColor(0xAAFFFFFF);
        strokePaint.setStrokeWidth(2.5f * k);
        c.drawLine(leftX - 1.5f * k, baseY, leftX - 1.5f * k, topY, strokePaint);
        c.drawLine(leftX, topY - 1.5f * k, rightX, topY - 1.5f * k, strokePaint);
        c.drawLine(rightX - 1.5f * k, baseY, rightX - 1.5f * k, topY, strokePaint);
    }

    private void drawAtmosphereParticles(Canvas c, float time, float parallax) {
        float pOffset = -parallax * 20f * k;
        paint.setStyle(Paint.Style.FILL);

        for (int i = 0; i < MOTE_COUNT; i++) {
            float yNorm = (moteY[i] + time * moteSpeed[i]) % 1f;
            float px = (moteX[i] * width + (float) Math.sin(time * 0.8f + motePhase[i]) * 40f * k) + pOffset;
            float py = yNorm * height * 0.85f;
            float alpha = (float) (0.25f + 0.35f * Math.sin(time * 2f + motePhase[i]));

            paint.setColor(Ui.alpha(0x99D5F5E3, Math.max(0f, Math.min(1f, alpha))));
            c.drawCircle(px, py, moteSize[i] * k, paint);
        }
    }

    private void drawSoccerBall(Canvas c, float parallax) {
        // Positioned gracefully in the mid-right foreground
        float nearOffset = -parallax * 85f * k;
        float bx = width * 0.76f + nearOffset;
        float by = height * 0.72f;
        float radius = 95f * k;

        // 1. Soft turf contact shadow underneath
        RadialGradient shadowGrad = new RadialGradient(
            bx, by + radius * 0.82f,
            radius * 1.35f,
            new int[] {0xBB000000, 0x55000000, 0x00000000},
            new float[] {0f, 0.5f, 1f},
            Shader.TileMode.CLAMP
        );
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(shadowGrad);
        rectF.set(bx - radius * 1.3f, by + radius * 0.55f, bx + radius * 1.3f, by + radius * 1.15f);
        c.drawOval(rectF, paint);
        paint.setShader(null);

        // 2. Ball spherical base with 3D ambient shading
        RadialGradient sphereGrad = new RadialGradient(
            bx - radius * 0.35f, by - radius * 0.35f,
            radius * 1.3f,
            new int[] {0xFFFFFFFF, 0xFFEBF1EE, 0xFFCAD4CE, 0xFF5D6B64},
            new float[] {0f, 0.45f, 0.78f, 1f},
            Shader.TileMode.CLAMP
        );
        paint.setShader(sphereGrad);
        c.drawCircle(bx, by, radius, paint);
        paint.setShader(null);

        // 3. Iconic pentagon leather panels (32-panel classic Telstar pattern)
        drawPentagon(c, bx - radius * 0.05f, by - radius * 0.05f, radius * 0.34f, -18f);

        // Surrounding 5 pentagons angled around the center
        float dist = radius * 0.65f;
        for (int i = 0; i < 5; i++) {
            double angle = Math.toRadians(-18 + i * 72);
            float px = bx + (float) Math.cos(angle) * dist;
            float py = by + (float) Math.sin(angle) * dist;
            drawPentagon(c, px, py, radius * 0.28f, (float) Math.toDegrees(angle) + 180);
        }

        // 4. White panel seam stitching lines
        strokePaint.setColor(0x442C3530);
        strokePaint.setStrokeWidth(2.2f * k);
        for (int i = 0; i < 5; i++) {
            double a1 = Math.toRadians(-18 + i * 72);
            double a2 = Math.toRadians(-18 + ((i + 1) % 5) * 72);
            float x1 = bx + (float) Math.cos(a1) * (radius * 0.34f);
            float y1 = by + (float) Math.sin(a1) * (radius * 0.34f);
            float x2 = bx + (float) Math.cos(a1) * dist;
            float y2 = by + (float) Math.sin(a1) * dist;
            c.drawLine(x1, y1, x2, y2, strokePaint);

            // Connect neighboring outer vertices
            float x3 = bx + (float) Math.cos(a2) * dist;
            float y3 = by + (float) Math.sin(a2) * dist;
            c.drawLine(x2, y2, x3, y3, strokePaint);
        }

        // 5. Specular highlight (floodlight reflection)
        paint.setColor(0x88FFFFFF);
        c.drawCircle(bx - radius * 0.38f, by - radius * 0.38f, radius * 0.22f, paint);
        paint.setColor(0xDFFFFFFF);
        c.drawCircle(bx - radius * 0.40f, by - radius * 0.40f, radius * 0.09f, paint);

        // 6. Grass blades overlapping foreground ball base
        strokePaint.setColor(0xFF1B4D31);
        strokePaint.setStrokeWidth(3f * k);
        for (int i = -6; i <= 6; i++) {
            float gx = bx + i * 9 * k;
            float gy = by + radius * 0.94f;
            float gh = (16 + (Math.abs(i) % 3) * 6) * k;
            c.drawLine(gx, gy, gx + (i * 1.5f) * k, gy - gh, strokePaint);
        }
    }

    private void drawPentagon(Canvas c, float cx, float cy, float r, float rotationDeg) {
        paint.setColor(0xFF181C1E); // Rich charcoal black leather
        paint.setStyle(Paint.Style.FILL);
        path.reset();
        for (int i = 0; i < 5; i++) {
            double rad = Math.toRadians(rotationDeg + i * 72);
            float vx = cx + (float) Math.cos(rad) * r;
            float vy = cy + (float) Math.sin(rad) * r;
            if (i == 0) path.moveTo(vx, vy);
            else path.lineTo(vx, vy);
        }
        path.close();
        c.drawPath(path, paint);

        // Subtle specular on pentagon
        strokePaint.setColor(0x33FFFFFF);
        strokePaint.setStrokeWidth(1.5f * k);
        c.drawPath(path, strokePaint);
    }
}

