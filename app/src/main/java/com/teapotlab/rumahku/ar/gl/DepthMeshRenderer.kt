package com.teapotlab.rumahku.ar.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the live [com.teapotlab.rumahku.ar.TsdfVolume] surface — the rough mesh
 * reconstructed from the depth stream via TSDF fusion + marching cubes, with a
 * wireframe overlay (the Polycam/Scaniverse "reconstructing as you scan" look).
 *
 * Triangle soup, 8 floats/vertex: position(3) + colour(3) + barycentric(2). The
 * wireframe is computed per-fragment from the barycentric coords (edge = the
 * smallest of the three), so no separate line pass is needed. Depth-tested so
 * near surfaces occlude far ones; semi-transparent fill so the camera shows
 * through.
 */
class DepthMeshRenderer {

    private var program = 0
    private var aPosition = 0
    private var aColor = 0
    private var aBary = 0
    private var uViewProj = 0

    private var vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var capacityFloats = 0

    fun createOnGlThread() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aColor = GLES20.glGetAttribLocation(program, "a_Color")
        aBary = GLES20.glGetAttribLocation(program, "a_Bary")
        uViewProj = GLES20.glGetUniformLocation(program, "u_ViewProj")
        ensureCapacity(1024 * STRIDE_FLOATS)
    }

    /**
     * @param viewProj column-major 4x4 = projection * view for this frame
     * @param verts triangle soup [x,y,z,r,g,b,bx,by,…] from DepthMesh.snapshot
     */
    fun draw(viewProj: FloatArray, verts: FloatArray) {
        val count = verts.size / STRIDE_FLOATS
        if (count < 3) return

        ensureCapacity(verts.size)
        vertexBuffer.position(0)
        vertexBuffer.put(verts)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // Depth-test the mesh against itself so near surfaces occlude far ones.
        // (The caller clears the depth buffer after the camera background.)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uViewProj, 1, false, viewProj, 0)

        val strideBytes = STRIDE_FLOATS * FLOAT_SIZE
        GLES20.glEnableVertexAttribArray(aPosition)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aColor)
        vertexBuffer.position(3)
        GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aBary)
        vertexBuffer.position(6)
        GLES20.glVertexAttribPointer(aBary, 2, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aColor)
        GLES20.glDisableVertexAttribArray(aBary)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GlUtil.checkGlError("DepthMeshRenderer.draw")
    }

    private fun ensureCapacity(floats: Int) {
        if (floats <= capacityFloats) return
        var cap = if (capacityFloats == 0) 1024 * STRIDE_FLOATS else capacityFloats
        while (cap < floats) cap *= 2
        vertexBuffer = ByteBuffer.allocateDirect(cap * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        capacityFloats = cap
    }

    companion object {
        private const val FLOAT_SIZE = 4
        private const val STRIDE_FLOATS = 8   // x,y,z,r,g,b,bx,by

        private const val VERTEX_SHADER = """
            uniform mat4 u_ViewProj;
            attribute vec4 a_Position;
            attribute vec3 a_Color;
            attribute vec2 a_Bary;
            varying vec3 v_Color;
            varying vec2 v_Bary;
            void main() {
                v_Color = a_Color;
                v_Bary = a_Bary;
                gl_Position = u_ViewProj * a_Position;
            }
        """

        // Semi-transparent coloured fill + a white wireframe from the barycentric
        // coords (edge = smallest of the three; the third = 1 - x - y).
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 v_Color;
            varying vec2 v_Bary;
            void main() {
                vec3 bary = vec3(v_Bary, 1.0 - v_Bary.x - v_Bary.y);
                float edge = min(min(bary.x, bary.y), bary.z);
                float wire = 1.0 - smoothstep(0.0, 0.02, edge);
                vec3 col = mix(v_Color, vec3(1.0), wire * 0.7);
                float alpha = mix(0.35, 0.9, wire);
                gl_FragColor = vec4(col, alpha);
            }
        """
    }
}
