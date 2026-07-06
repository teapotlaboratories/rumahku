package com.teapotlab.rumahku.ar.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the live camera image that ARCore produces, as a full-screen quad.
 *
 * How ARCore delivers the camera feed:
 *   • We create one special OpenGL texture of type GL_TEXTURE_EXTERNAL_OES and
 *     hand its id to the Session via setCameraTextureName().
 *   • Every frame, ARCore writes the latest camera image into that texture.
 *   • We draw a screen-filling rectangle sampling from it.
 *
 * The only subtlety is orientation: the camera sensor, the display rotation and
 * OpenGL's coordinate system don't agree, so ARCore gives us a helper —
 * frame.transformCoordinates2d() — that maps our quad's corners to the correct
 * texture coordinates. We only recompute that mapping when the display geometry
 * changes (rotation, first frame), which ARCore signals via
 * frame.hasDisplayGeometryChanged().
 */
class BackgroundRenderer {

    /** The external-OES texture id ARCore renders the camera image into. */
    var textureId: Int = -1
        private set

    private var program = 0
    private var positionAttrib = 0
    private var texCoordAttrib = 0
    private var textureUniform = 0

    // Quad corner positions in Normalized Device Coordinates (fills the screen)
    // and their matching texture coordinates (filled in by ARCore each rotation).
    private lateinit var quadCoords: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer

    /** Must be called on the GL thread (e.g. from onSurfaceCreated). */
    fun createOnGlThread() {
        // 1. Create the external texture and set sane sampling parameters.
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        // 2. Allocate the two coordinate buffers (native byte order for OpenGL).
        quadCoords = allocateFloatBuffer(QUAD_COORDS)
        quadTexCoords = allocateFloatBuffer(FloatArray(QUAD_COORDS.size))

        // 3. Compile the shader program and cache its attribute/uniform handles.
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
        GlUtil.checkGlError("BackgroundRenderer.createOnGlThread")
    }

    /** Draws the current camera image. Call once per frame after session.update(). */
    fun draw(frame: Frame) {
        // Recompute texture coordinates only when the display geometry changed.
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords,
            )
        }

        // timestamp == 0 means ARCore has no camera image yet; skip drawing.
        if (frame.timestamp == 0L) return

        quadCoords.position(0)
        quadTexCoords.position(0)

        // The camera image is a solid background: no depth testing, no depth write.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)

        GLES20.glVertexAttribPointer(
            positionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadCoords,
        )
        GLES20.glVertexAttribPointer(
            texCoordAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadTexCoords,
        )
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)

        // Restore depth state for any 3D content drawn later in the frame.
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GlUtil.checkGlError("BackgroundRenderer.draw")
    }

    private fun allocateFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }

    companion object {
        private const val COORDS_PER_VERTEX = 2
        private const val FLOAT_SIZE = 4

        // Two triangles as a strip, covering the whole screen in NDC space.
        private val QUAD_COORDS = floatArrayOf(
            -1f, -1f,
            -1f, +1f,
            +1f, -1f,
            +1f, +1f,
        )

        // Pass-through vertex shader: positions are already in NDC.
        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        // Samples the external camera texture. The OES extension + samplerExternalOES
        // are required for GL_TEXTURE_EXTERNAL_OES textures.
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """
    }
}
