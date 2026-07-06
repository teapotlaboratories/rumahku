package com.teapotlab.rumahku.ar.gl

import android.opengl.GLES20
import android.util.Log

/**
 * Small OpenGL ES helpers: compiling shaders and surfacing GL errors.
 *
 * OpenGL reports errors out-of-band (you have to poll glGetError), so during
 * development we check aggressively and log loudly. These helpers keep that
 * boilerplate out of the renderer classes.
 */
object GlUtil {

    private const val TAG = "rumahku-gl"

    /**
     * Compiles a single shader stage.
     *
     * @param type GLES20.GL_VERTEX_SHADER or GLES20.GL_FRAGMENT_SHADER
     * @param source GLSL source code
     * @return the compiled shader handle, or 0 on failure
     */
    fun loadShader(type: Int, source: String): Int {
        var shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        // Check compile status; a failed shader silently produces garbage output.
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            shader = 0
        }
        return shader
    }

    /**
     * Links a vertex + fragment shader into a program.
     * @return the linked program handle, or 0 on failure
     */
    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertex == 0) return 0
        val fragment = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragment == 0) return 0

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)

        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e(TAG, "program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    /**
     * Logs any pending GL error against [label]. Call after a group of GL calls
     * while developing; cheap enough to leave in for now.
     */
    fun checkGlError(label: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$label: glError 0x${Integer.toHexString(error)}")
        }
    }
}
