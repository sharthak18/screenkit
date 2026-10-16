package com.screenkit.screenrecorder.services

import android.graphics.Bitmap
import android.graphics.RectF
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders frames from an external source (e.g. MediaProjection VirtualDisplay via SurfaceTexture)
 * into a target encoder input Surface (or offscreen EGL Pbuffer for hardware GL screenshot readback),
 * applying sub-region crop if requested.
 */
class CropSurfaceRenderer(
    private val inputSurface: Surface?,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val cropRect: RectF? = null,
) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var programHandle = 0
    private var uMVPMatrixLoc = -1
    private var uTexMatrixLoc = -1
    private var aPositionLoc = -1
    private var aTextureCoordLoc = -1

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    private val mvpMatrix = FloatArray(16)
    private val adjustedTexMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)

    companion object {
        private const val SIZEOF_FLOAT = 4
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private val FULL_RECTANGLE_COORDS = floatArrayOf(
            -1.0f, -1.0f, // bottom left
            1.0f, -1.0f,  // bottom right
            -1.0f, 1.0f,  // top left
            1.0f, 1.0f,   // top right
        )

        private val FULL_RECTANGLE_TEX_COORDS = floatArrayOf(
            0.0f, 0.0f, // bottom left
            1.0f, 0.0f, // bottom right
            0.0f, 1.0f, // top left
            1.0f, 1.0f, // top right
        )

        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uTexMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }

    init {
        vertexBuffer = ByteBuffer.allocateDirect(FULL_RECTANGLE_COORDS.size * SIZEOF_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(FULL_RECTANGLE_COORDS)
                position(0)
            }

        texCoordBuffer = ByteBuffer.allocateDirect(FULL_RECTANGLE_TEX_COORDS.size * SIZEOF_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(FULL_RECTANGLE_TEX_COORDS)
                position(0)
            }

        Matrix.setIdentityM(mvpMatrix, 0)
        if (inputSurface == null) {
            // Flip Y for offscreen Pbuffer so glReadPixels matches top-down Android Bitmap layout
            Matrix.scaleM(mvpMatrix, 0, 1f, -1f, 1f)
        }
        setupEgl()
        setupGl()
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("unable to initialize EGL14")
        }

        val surfaceTypeBit = if (inputSurface != null) EGL14.EGL_WINDOW_BIT else EGL14.EGL_PBUFFER_BIT
        val attribList = if (inputSurface != null) {
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, surfaceTypeBit,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
        } else {
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, surfaceTypeBit,
                EGL14.EGL_NONE,
            )
        }

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0) || numConfigs[0] == 0) {
            val fallbackAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(eglDisplay, fallbackAttribs, 0, configs, 0, configs.size, numConfigs, 0) || numConfigs[0] == 0) {
                throw RuntimeException("unable to find suitable EGL config")
            }
        }
        val config = configs[0]!!

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE,
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("failed to create EGL context")
        }

        eglSurface = if (inputSurface != null) {
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            EGL14.eglCreateWindowSurface(eglDisplay, config, inputSurface, surfaceAttribs, 0)
        } else {
            val pbufferAttribs = intArrayOf(
                EGL14.EGL_WIDTH, outputWidth,
                EGL14.EGL_HEIGHT, outputHeight,
                EGL14.EGL_NONE,
            )
            EGL14.eglCreatePbufferSurface(eglDisplay, config, pbufferAttribs, 0)
        }
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("failed to create EGL surface")
        }

        makeCurrent()
    }

    private fun setupGl() {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        programHandle = GLES20.glCreateProgram()
        GLES20.glAttachShader(programHandle, vShader)
        GLES20.glAttachShader(programHandle, fShader)
        GLES20.glLinkProgram(programHandle)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programHandle, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetProgramInfoLog(programHandle)
            GLES20.glDeleteProgram(programHandle)
            throw RuntimeException("Failed to link GL program: $error")
        }

        aPositionLoc = GLES20.glGetAttribLocation(programHandle, "aPosition")
        aTextureCoordLoc = GLES20.glGetAttribLocation(programHandle, "aTextureCoord")
        uMVPMatrixLoc = GLES20.glGetUniformLocation(programHandle, "uMVPMatrix")
        uTexMatrixLoc = GLES20.glGetUniformLocation(programHandle, "uTexMatrix")
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compilation failed: $error")
        }
        return shader
    }

    fun createOesTexture(): Int {
        makeCurrent()
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return texId
    }

    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    /**
     * Draws the frame from [textureId] into the target surface, mapping [cropRect] to full viewport.
     */
    fun drawFrame(textureId: Int, surfaceTextureTransform: FloatArray, timestampNanos: Long) {
        makeCurrent()
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(programHandle)

        System.arraycopy(surfaceTextureTransform, 0, stMatrix, 0, 16)

        if (cropRect != null) {
            // Apply crop sub-rectangle in UV space (screen top=0..bottom=1 -> GL bottom=1-bottom)
            val cropMatrix = FloatArray(16)
            Matrix.setIdentityM(cropMatrix, 0)
            val glBottom = (1f - cropRect.bottom).coerceIn(0f, 1f)
            Matrix.translateM(cropMatrix, 0, cropRect.left, glBottom, 0f)
            Matrix.scaleM(cropMatrix, 0, cropRect.width(), cropRect.height(), 1f)
            Matrix.multiplyMM(adjustedTexMatrix, 0, stMatrix, 0, cropMatrix, 0)
        } else {
            System.arraycopy(stMatrix, 0, adjustedTexMatrix, 0, 16)
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, adjustedTexMatrix, 0)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 2 * SIZEOF_FLOAT, vertexBuffer)

        GLES20.glEnableVertexAttribArray(aTextureCoordLoc)
        GLES20.glVertexAttribPointer(aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 2 * SIZEOF_FLOAT, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTextureCoordLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glUseProgram(0)

        if (inputSurface != null) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNanos)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } else {
            GLES20.glFinish()
        }
    }

    /**
     * Reads the currently rendered GL viewport into an ARGB_8888 Bitmap.
     */
    fun readPixelsBitmap(): Bitmap {
        makeCurrent()
        val buf = ByteBuffer.allocateDirect(outputWidth * outputHeight * 4)
            .order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, outputWidth, outputHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buf)
        return bitmap
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        if (programHandle != 0) {
            GLES20.glDeleteProgram(programHandle)
            programHandle = 0
        }
    }
}
