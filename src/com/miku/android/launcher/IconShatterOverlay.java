/*
 * Copyright (C) 2026 Miku UI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.miku.android.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import com.android.launcher3.Launcher;
import com.android.launcher3.dragndrop.DragLayer;
import com.android.launcher3.dragndrop.DragView;
import com.android.launcher3.dragndrop.DraggableView;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.views.BaseDragLayer;

import java.util.ArrayList;
import java.util.Random;

/**
 * Overlay that shatters a workspace icon into flying tiles when it is removed.
 */
final class IconShatterOverlay extends View {

    private static final Object OVERLAY_TAG = new Object();
    private static final int DURATION_MS = 580;
    private static final float DURATION_SEC = DURATION_MS / 1000f;
    private static final float GRAVITY_DP = 2400f;
    private static final float EXPLODE_MIN_DP = 280f;
    private static final float EXPLODE_RANGE_DP = 520f;
    private static final float JITTER_DP = 90f;
    private static final float SPIN_MAX_DEG = 540f;
    private static final int MIN_GRID = 8;
    private static final int MAX_GRID = 12;
    private static final int MIN_SIZE_PX = 16;

    private final ArrayList<Burst> mBursts = new ArrayList<>(2);
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF mDest = new RectF();
    private final float mDensity;
    private final float mGravityPx;

    private IconShatterOverlay(Launcher launcher) {
        super(launcher);
        mDensity = getResources().getDisplayMetrics().density;
        mGravityPx = GRAVITY_DP * mDensity;
        setTag(OVERLAY_TAG);
        setWillNotDraw(false);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    static void shatterItem(Launcher launcher, View view) {
        if (!canShatterItem(launcher, view)) {
            return;
        }
        Snapshot snapshot = snapshot(launcher, view);
        if (snapshot == null) {
            return;
        }
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        play(launcher, snapshot, null);
    }

    static boolean shatterDragView(Launcher launcher, DragView dragView, Runnable endCallback) {
        if (!ValueAnimator.areAnimatorsEnabled() || dragView == null || launcher == null) {
            return false;
        }
        Snapshot snapshot = snapshot(launcher, dragView);
        if (snapshot == null) {
            return false;
        }
        dragView.setAlpha(0f);
        dragView.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        play(launcher, snapshot, endCallback);
        return true;
    }

    private static boolean canShatterItem(Launcher launcher, View view) {
        if (!ValueAnimator.areAnimatorsEnabled() || launcher == null || view == null) {
            return false;
        }
        if (!view.isAttachedToWindow() || view.getWidth() < MIN_SIZE_PX
                || view.getHeight() < MIN_SIZE_PX) {
            return false;
        }
        if (view instanceof DraggableView dv
                && dv.getViewType() == DraggableView.DRAGGABLE_WIDGET) {
            return false;
        }
        // Removing the last item deletes the folder. Its preview draws hardware
        // bitmaps, so snapshotting it crashes. Let the folder disappear.
        if (view instanceof FolderIcon) {
            return false;
        }
        if (launcher.getDragController() != null && launcher.getDragController().isDragging()) {
            return false;
        }
        DragLayer dragLayer = launcher.getDragLayer();
        if (view.getVisibility() != VISIBLE && dragLayer != null
                && dragLayer.getAnimatedView() != null) {
            return false;
        }
        return true;
    }

    private static Snapshot snapshot(Launcher launcher, View view) {
        int width = view.getWidth();
        int height = view.getHeight();
        if (width < MIN_SIZE_PX || height < MIN_SIZE_PX) {
            return null;
        }
        Bitmap full;
        try {
            full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return null;
        }
        Canvas canvas = new Canvas(full);
        view.draw(canvas);

        Rect local = new Rect();
        if (view instanceof DraggableView dv
                && dv.getViewType() == DraggableView.DRAGGABLE_ICON) {
            dv.getWorkspaceVisualDragBounds(local);
        }
        if (local.width() < MIN_SIZE_PX || local.height() < MIN_SIZE_PX) {
            local.set(0, 0, width, height);
        }
        int left = Math.max(0, Math.min(local.left, full.getWidth() - 1));
        int top = Math.max(0, Math.min(local.top, full.getHeight() - 1));
        int cropW = Math.max(1, Math.min(local.width(), full.getWidth() - left));
        int cropH = Math.max(1, Math.min(local.height(), full.getHeight() - top));
        Bitmap icon = Bitmap.createBitmap(full, left, top, cropW, cropH);
        if (icon != full) {
            full.recycle();
        }

        DragLayer dragLayer = launcher.getDragLayer();
        if (dragLayer == null) {
            icon.recycle();
            return null;
        }
        int[] loc = new int[] {left, top};
        float scale = dragLayer.getDescendantCoordRelativeToSelf(view, loc);
        RectF bounds = new RectF(loc[0], loc[1],
                loc[0] + cropW * scale, loc[1] + cropH * scale);
        if (bounds.width() < MIN_SIZE_PX || bounds.height() < MIN_SIZE_PX) {
            icon.recycle();
            return null;
        }
        return new Snapshot(icon, bounds);
    }

    private static void play(Launcher launcher, Snapshot snapshot, Runnable endCallback) {
        DragLayer dragLayer = launcher.getDragLayer();
        View existing = dragLayer.findViewWithTag(OVERLAY_TAG);
        IconShatterOverlay overlay;
        if (existing instanceof IconShatterOverlay) {
            overlay = (IconShatterOverlay) existing;
        } else {
            overlay = new IconShatterOverlay(launcher);
            BaseDragLayer.LayoutParams lp = new BaseDragLayer.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.ignoreInsets = true;
            dragLayer.addView(overlay, lp);
        }
        overlay.addBurst(snapshot, endCallback);
    }

    private void addBurst(Snapshot snapshot, Runnable endCallback) {
        Burst burst = Burst.create(snapshot, mDensity);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            burst.progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mBursts.remove(burst);
                if (!burst.bitmap.isRecycled()) {
                    burst.bitmap.recycle();
                }
                if (endCallback != null) {
                    endCallback.run();
                }
                if (mBursts.isEmpty() && isAttachedToWindow()) {
                    ViewGroup parent = (ViewGroup) getParent();
                    if (parent != null) {
                        parent.removeView(IconShatterOverlay.this);
                    }
                }
            }
        });
        burst.animator = animator;
        mBursts.add(burst);
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ArrayList<Burst> running = new ArrayList<>(mBursts);
        for (int i = 0, n = running.size(); i < n; i++) {
            ValueAnimator animator = running.get(i).animator;
            if (animator != null && animator.isRunning()) {
                animator.cancel();
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (int i = 0, n = mBursts.size(); i < n; i++) {
            mBursts.get(i).draw(canvas, mPaint, mDest, mGravityPx);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return false;
    }

    private static final class Snapshot {
        final Bitmap bitmap;
        final RectF bounds;

        Snapshot(Bitmap bitmap, RectF bounds) {
            this.bitmap = bitmap;
            this.bounds = bounds;
        }
    }

    private static final class Particle {
        final Rect src = new Rect();
        float x0;
        float y0;
        float w;
        float h;
        float vx;
        float vy;
        float spin;
        float delay;
    }

    private static final class Burst {
        final Bitmap bitmap;
        final Particle[] particles;
        float progress;
        ValueAnimator animator;

        Burst(Bitmap bitmap, Particle[] particles) {
            this.bitmap = bitmap;
            this.particles = particles;
        }

        static Burst create(Snapshot snapshot, float density) {
            Bitmap bmp = snapshot.bitmap;
            RectF bounds = snapshot.bounds;
            int cols = gridSize(bmp.getWidth());
            int rows = gridSize(bmp.getHeight());
            int srcW = bmp.getWidth();
            int srcH = bmp.getHeight();
            float tileW = bounds.width() / cols;
            float tileH = bounds.height() / rows;
            float cx = bounds.centerX();
            float cy = bounds.centerY();
            Random random = new Random();
            Particle[] particles = new Particle[cols * rows];
            int srcTileW = srcW / cols;
            int srcTileH = srcH / rows;
            int index = 0;
            for (int row = 0; row < rows; row++) {
                int srcTop = row * srcTileH;
                int srcBottom = row == rows - 1 ? srcH : srcTop + srcTileH;
                for (int col = 0; col < cols; col++) {
                    int srcLeft = col * srcTileW;
                    int srcRight = col == cols - 1 ? srcW : srcLeft + srcTileW;
                    Particle p = new Particle();
                    p.src.set(srcLeft, srcTop, srcRight, srcBottom);
                    p.x0 = bounds.left + col * tileW;
                    p.y0 = bounds.top + row * tileH;
                    p.w = tileW + 1f;
                    p.h = tileH + 1f;
                    float px = p.x0 + tileW * 0.5f;
                    float py = p.y0 + tileH * 0.5f;
                    float dx = px - cx;
                    float dy = py - cy;
                    float dist = (float) Math.hypot(dx, dy);
                    if (dist < 0.5f) {
                        dist = 0.5f;
                    }
                    float explode = (EXPLODE_MIN_DP + random.nextFloat() * EXPLODE_RANGE_DP)
                            * density;
                    p.vx = dx / dist * explode
                            + (random.nextFloat() - 0.5f) * JITTER_DP * density;
                    p.vy = dy / dist * explode
                            + (random.nextFloat() - 0.5f) * JITTER_DP * density
                            - 70f * density;
                    p.spin = (random.nextFloat() - 0.5f) * 2f * SPIN_MAX_DEG;
                    p.delay = random.nextFloat() * 0.08f;
                    particles[index++] = p;
                }
            }
            return new Burst(bmp, particles);
        }

        void draw(Canvas canvas, Paint paint, RectF dest, float gravityPx) {
            float t = progress;
            for (Particle p : particles) {
                float local = p.delay >= 1f ? 1f : Math.max(0f, (t - p.delay) / (1f - p.delay));
                if (local <= 0f) {
                    dest.set(p.x0, p.y0, p.x0 + p.w, p.y0 + p.h);
                    paint.setAlpha(255);
                    canvas.drawBitmap(bitmap, p.src, dest, paint);
                    continue;
                }
                float time = local * DURATION_SEC;
                float fade = local < 0.32f
                        ? 1f
                        : (float) Math.pow(1f - (local - 0.32f) / 0.68f, 1.35);
                int alpha = Math.max(0, Math.min(255, Math.round(255f * fade)));
                if (alpha <= 0) {
                    continue;
                }
                float scale = 1f - 0.22f * local;
                float x = p.x0 + p.vx * time;
                float y = p.y0 + p.vy * time + 0.5f * gravityPx * time * time;
                float dw = p.w * scale;
                float dh = p.h * scale;
                float cx = x + p.w * 0.5f;
                float cy = y + p.h * 0.5f;
                dest.set(cx - dw * 0.5f, cy - dh * 0.5f, cx + dw * 0.5f, cy + dh * 0.5f);
                paint.setAlpha(alpha);
                canvas.save();
                canvas.rotate(p.spin * time, cx, cy);
                canvas.drawBitmap(bitmap, p.src, dest, paint);
                canvas.restore();
            }
        }

        private static int gridSize(int px) {
            int size = Math.round(px / 12f);
            return Math.max(MIN_GRID, Math.min(MAX_GRID, size));
        }
    }
}
