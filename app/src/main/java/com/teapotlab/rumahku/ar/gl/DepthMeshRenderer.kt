package com.teapotlab.rumahku.ar.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the live [com.teapotlab.rumahku.ar.TsdfVolume] surface, in one of two
 * styles selected by [wireframe]:
 *
 *  • **Solid** (the original Mobile3DRecon look): an opaque, flat-shaded surface
 *    coloured from the camera. The per-fragment normal comes from screen-space
 *    derivatives of the world position, so no normal attribute is needed.
 *  • **Wireframe**: only the triangle edges are drawn; interiors are discarded so
 *    the camera shows through (a Polycam-style coverage view). Edges are found
 *    from the per-vertex barycentric coords ((1,0),(0,1),(0,0)) that TsdfVolume
 *    emits — a fragment is on an edge when a barycentric coord is near 0. Drawn
 *    in two passes (a depth-only prime, then the lines) so edges on hidden
 *    surfaces are occluded instead of showing through.
 *
 * Both share one program (a `u_Wireframe` uniform + per-mode GL state) so the
 * vertex data and buffer plumbing stay identical.
 *
 * Triangle soup, 8 floats/vertex: position(3) + colour(3) + barycentric(2).
 */
class DepthMeshRenderer(private val wireframe: Boolean = false) {

    private var program = 0
    private var aPosition = 0
    private var aColor = 0
    private var aBary = 0
    private var uViewProj = 0
    private var uWireframe = 0

    // Mesh lives in a GPU-resident VBO. We only re-upload when the snapshot data
    // actually changes — TsdfVolume returns the SAME array instance until it
    // re-meshes, so reference identity is a cheap "did it change?" check. This
    // keeps the per-frame cost O(1) (just a draw call) instead of re-transferring
    // the whole growing mesh every frame.
    private var vbo = 0
    private var uploadedData: FloatArray? = null
    private var uploadedCount = 0
    private var scratch: FloatBuffer =
        ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var capacityFloats = 0

    fun createOnGlThread() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aColor = GLES20.glGetAttribLocation(program, "a_Color")
        aBary = GLES20.glGetAttribLocation(program, "a_Bary")
        uViewProj = GLES20.glGetUniformLocation(program, "u_ViewProj")
        uWireframe = GLES20.glGetUniformLocation(program, "u_Wireframe")
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
    }

    /**
     * @param viewProj column-major 4x4 = projection * view for this frame
     * @param verts triangle soup [x,y,z,r,g,b,bx,by,…] from TsdfVolume.snapshot
     */
    fun draw(viewProj: FloatArray, verts: FloatArray) {
        val count = verts.size / STRIDE_FLOATS
        if (count < 3) return

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        // Upload only when the mesh changed (new snapshot instance). Unchanged
        // frames skip the CPU→GPU transfer entirely and just re-draw the VBO.
        if (verts !== uploadedData) {
            ensureCapacity(verts.size)
            scratch.position(0)
            scratch.put(verts)
            scratch.position(0)
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER, verts.size * FLOAT_SIZE, scratch, GLES20.GL_DYNAMIC_DRAW)
            uploadedData = verts
            uploadedCount = count
        }

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uViewProj, 1, false, viewProj, 0)

        val strideBytes = STRIDE_FLOATS * FLOAT_SIZE
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, strideBytes, 0)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, strideBytes, 3 * FLOAT_SIZE)
        GLES20.glEnableVertexAttribArray(aBary)
        GLES20.glVertexAttribPointer(aBary, 2, GLES20.GL_FLOAT, false, strideBytes, 6 * FLOAT_SIZE)

        if (wireframe) {
            // Pass 1 — depth prime: draw the SOLID surface (no discard) into the
            // depth buffer only (colour masked off) so edges on back-facing
            // surfaces get occluded. Polygon offset pushes this fill slightly
            // away so the wire pass — the exact same geometry — still passes
            // LEQUAL instead of z-fighting.
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(true)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glColorMask(false, false, false, false)
            GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
            GLES20.glPolygonOffset(1.5f, 2.0f)
            GLES20.glUniform1i(uWireframe, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, uploadedCount)

            // Pass 2 — the wires: blend the anti-aliased edges over the camera,
            // depth-tested against the prime (so only front-surface edges show),
            // without writing depth themselves.
            GLES20.glColorMask(true, true, true, true)
            GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)
            GLES20.glUniform1i(uWireframe, 1)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, uploadedCount)
        } else {
            // Solid surface: opaque, depth-tested so near faces occlude far ones.
            // Blending must be OFF explicitly — earlier renderers (coverage dots)
            // enable it and the state is global, so a solid mesh would otherwise
            // blend over the camera and look translucent.
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(true)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glUniform1i(uWireframe, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, uploadedCount)
        }

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aColor)
        GLES20.glDisableVertexAttribArray(aBary)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        // Restore global state we touched so the next renderer starts clean.
        GLES20.glColorMask(true, true, true, true)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GlUtil.checkGlError("DepthMeshRenderer.draw")
    }

    private fun ensureCapacity(floats: Int) {
        if (floats <= capacityFloats) return
        var cap = if (capacityFloats == 0) 1024 * STRIDE_FLOATS else capacityFloats
        while (cap < floats) cap *= 2
        scratch = ByteBuffer.allocateDirect(cap * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        capacityFloats = cap
    }

    companion object {
        private const val FLOAT_SIZE = 4
        private const val STRIDE_FLOATS = 8   // x,y,z,r,g,b,bx,by

        // Feeds both modes: flat colour for the solid fill, world position for the
        // screen-space normal, and barycentric coords for the wireframe. The third
        // barycentric is implied: bz = 1 - bx - by.
        private const val VERTEX_SHADER = """#version 300 es
            uniform mat4 u_ViewProj;
            in vec4 a_Position;
            in vec3 a_Color;
            in vec2 a_Bary;
            flat out vec3 v_Color;
            out vec3 v_World;
            out vec3 v_Bary;
            void main() {
                v_Color = a_Color;
                v_World = a_Position.xyz;
                v_Bary = vec3(a_Bary, 1.0 - a_Bary.x - a_Bary.y);
                gl_Position = u_ViewProj * a_Position;
            }
        """

        // u_Wireframe picks the mode (a uniform, so the branch is coherent across
        // fragments and the derivative calls stay valid). Solid: flat camera
        // colour with a gentle face-normal shade. Wireframe: draw only near-edge
        // fragments — a fragment is on an edge when a barycentric coord is near 0;
        // fwidth() keeps the line a constant pixel width and feeds the AA falloff.
        private const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            uniform int u_Wireframe;
            flat in vec3 v_Color;
            in vec3 v_World;
            in vec3 v_Bary;
            out vec4 fragColor;
            const vec3 WIRE = vec3(0.92, 0.94, 0.97);
            const float LINE_PX = 1.3;
            void main() {
                vec3 n = normalize(cross(dFdx(v_World), dFdy(v_World)));
                vec3 l = normalize(vec3(0.35, 0.85, 0.40));
                float diff = 0.55 + 0.45 * abs(dot(n, l));
                vec3 bd = fwidth(v_Bary);
                if (u_Wireframe == 1) {
                    vec3 a = smoothstep(vec3(0.0), bd * LINE_PX, v_Bary);
                    float edge = min(min(a.x, a.y), a.z);
                    float wire = 1.0 - edge;
                    if (wire < 0.04) discard;
                    fragColor = vec4(WIRE, wire);
                } else {
                    fragColor = vec4(v_Color * diff, 1.0);
                }
            }
        """
    }
}
