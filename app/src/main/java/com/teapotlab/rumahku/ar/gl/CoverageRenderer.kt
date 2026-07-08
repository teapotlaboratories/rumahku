package com.teapotlab.rumahku.ar.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the coverage dots — one per captured keyframe — as world-anchored
 * points on top of the camera feed.
 *
 * Each point is a 3D world position. We transform it by the camera's
 * view-projection matrix (supplied per frame) so the dots stay locked onto the
 * scene as the phone moves, and shrink with distance. A round, semi-transparent
 * splat is drawn in the fragment shader via gl_PointCoord.
 */
class CoverageRenderer {

    private var program = 0
    private var aPosition = 0
    private var uViewProj = 0
    private var uPointSize = 0
    private var uColor = 0

    private var vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var capacityFloats = 0

    fun createOnGlThread() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        uViewProj = GLES20.glGetUniformLocation(program, "u_ViewProj")
        uPointSize = GLES20.glGetUniformLocation(program, "u_PointSize")
        uColor = GLES20.glGetUniformLocation(program, "u_Color")
        ensureCapacity(256 * 3)
    }

    /**
     * @param viewProj column-major 4x4 = projection * view for this frame
     * @param points flat [x,y,z,…] world positions (from CoverageBuffer.snapshot)
     */
    fun draw(viewProj: FloatArray, points: FloatArray) {
        val count = points.size / 3
        if (count == 0) return

        ensureCapacity(points.size)
        vertexBuffer.position(0)
        vertexBuffer.put(points)
        vertexBuffer.position(0)

        // Dots blend over the camera image; no depth interaction needed.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uViewProj, 1, false, viewProj, 0)
        GLES20.glUniform1f(uPointSize, POINT_SIZE)
        // Teal, translucent — a "processed surface" wash over scanned areas.
        GLES20.glUniform4f(uColor, 0.16f, 0.84f, 0.66f, 0.45f)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
        GLES20.glDisableVertexAttribArray(aPosition)

        GLES20.glDisable(GLES20.GL_BLEND)
        GlUtil.checkGlError("CoverageRenderer.draw")
    }

    /** Grows the vertex buffer to hold at least [floats] floats. */
    private fun ensureCapacity(floats: Int) {
        if (floats <= capacityFloats) return
        var cap = if (capacityFloats == 0) 256 * 3 else capacityFloats
        while (cap < floats) cap *= 2
        vertexBuffer = ByteBuffer.allocateDirect(cap * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        capacityFloats = cap
    }

    companion object {
        private const val FLOAT_SIZE = 4
        private const val POINT_SIZE = 60f  // pixel size at 1m; attenuates with depth

        // Projects world points and scales point size inversely with view depth.
        private const val VERTEX_SHADER = """
            uniform mat4 u_ViewProj;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_ViewProj * a_Position;
                gl_PointSize = clamp(u_PointSize / gl_Position.w, 4.0, 34.0);
            }
        """

        // Round, soft-edged splat via the point's local coordinates.
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                vec2 c = gl_PointCoord - vec2(0.5);
                float d = length(c);
                if (d > 0.5) discard;
                float a = smoothstep(0.5, 0.3, d);
                gl_FragColor = vec4(u_Color.rgb, u_Color.a * a);
            }
        """
    }
}
