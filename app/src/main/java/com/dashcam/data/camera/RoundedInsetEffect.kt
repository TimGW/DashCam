package com.dashcam.data.camera

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.impl.CameraInternal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * Rounds the corners of the front camera inset in the composited dual-camera mode, in both the
 * preview and the video. CameraX runs this effect on each camera's stream before compositing; it
 * copies the back camera unchanged and makes the corners of the front camera's frame
 * transparent, which the compositor blends over the back camera.
 *
 * Telling the two streams apart relies on CameraX-internal APIs (SurfaceRequest.getCamera and
 * SurfaceOutputImpl.getCamera via reflection), so check this still works when upgrading CameraX
 * past 1.5; if it breaks, the inset just keeps square corners.
 */
class RoundedInsetEffect private constructor(processor: RoundedCornerProcessor) :
    CameraEffect(PREVIEW or VIDEO_CAPTURE, processor.executor, processor, { Log.e(TAG, "Effect failed", it) }) {

    constructor() : this(RoundedCornerProcessor())

    private companion object {
        const val TAG = "RoundedInsetEffect"
    }
}

/** Passes frames through on a GL thread; frames from the front camera get transparent corners. */
private class RoundedCornerProcessor : SurfaceProcessor {

    private val thread = HandlerThread("RoundedInsetGL").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private lateinit var config: EGLConfig
    private var idleSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var positionLoc = 0
    private var texCoordLoc = 0
    private var texMatrixLoc = 0
    private var sizeLoc = 0
    private var radiusLoc = 0

    /** Camera inputs and processor outputs, keyed by "is front camera". */
    private val inputs = mutableMapOf<Boolean, Input>()
    private val outputs = mutableMapOf<Boolean, Output>()
    private val textureMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)
    private val drawMatrix = FloatArray(16)
    /** Sensor orientation of each camera, keyed by "is front camera". */
    private val sensorRotations = mutableMapOf<Boolean, Int>()

    private class Input(val textureId: Int, val surfaceTexture: SurfaceTexture, val surface: Surface)
    private class Output(val surfaceOutput: SurfaceOutput, val eglSurface: EGLSurface)

    override fun onInputSurface(request: SurfaceRequest) {
        ensureGl()
        val isFront = request.isFrontCamera()
        sensorRotations[isFront] = request.sensorRotationDegrees()
        val textureId = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val surfaceTexture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(request.resolution.width, request.resolution.height)
        }
        val input = Input(textureId, surfaceTexture, Surface(surfaceTexture))
        inputs[isFront] = input
        surfaceTexture.setOnFrameAvailableListener({ render(isFront, input) }, handler)
        request.provideSurface(input.surface, executor) {
            surfaceTexture.setOnFrameAvailableListener(null)
            surfaceTexture.release()
            input.surface.release()
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            if (inputs[isFront] === input) inputs.remove(isFront)
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        ensureGl()
        val isFront = surfaceOutput.isFrontCamera()
        val surface = surfaceOutput.getSurface(executor) { event ->
            if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) closeOutput(isFront, surfaceOutput)
        }
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        outputs.put(isFront, Output(surfaceOutput, eglSurface))?.let { previous ->
            EGL14.eglDestroySurface(display, previous.eglSurface)
            previous.surfaceOutput.close()
        }
    }

    private fun closeOutput(isFront: Boolean, surfaceOutput: SurfaceOutput) {
        val output = outputs[isFront]?.takeIf { it.surfaceOutput === surfaceOutput }
        if (output != null) {
            outputs.remove(isFront)
            EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)
            EGL14.eglDestroySurface(display, output.eglSurface)
        }
        surfaceOutput.close()
    }

    private fun render(isFront: Boolean, input: Input) {
        val output = outputs[isFront]
        EGL14.eglMakeCurrent(display, output?.eglSurface ?: idleSurface, output?.eglSurface ?: idleSurface, context)
        // Always latch the frame so the camera can keep producing, even without an output yet.
        input.surfaceTexture.updateTexImage()
        if (output == null) return
        input.surfaceTexture.getTransformMatrix(textureMatrix)
        output.surfaceOutput.updateTransformMatrix(outputMatrix, textureMatrix)
        frontCorrection(isFront)?.let { correction ->
            Matrix.multiplyMM(drawMatrix, 0, outputMatrix, 0, correction, 0)
            drawMatrix.copyInto(outputMatrix)
        }
        val size = output.surfaceOutput.size

        GLES20.glViewport(0, 0, size.width, size.height)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input.textureId)
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, outputMatrix, 0)
        GLES20.glUniform2f(sizeLoc, size.width.toFloat(), size.height.toFloat())
        val radius = if (isFront) CORNER_RADIUS_FRACTION * minOf(size.width, size.height) else 0f
        GLES20.glUniform1f(radiusLoc, radius)
        GLES20.glEnableVertexAttribArray(positionLoc)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, Positions)
        GLES20.glEnableVertexAttribArray(texCoordLoc)
        GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 0, TexCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Keep the camera timestamps so audio and video stay in sync downstream.
        EGLExt.eglPresentationTimeANDROID(display, output.eglSurface, input.surfaceTexture.timestamp)
        EGL14.eglSwapBuffers(display, output.eglSurface)
    }

    /**
     * CameraX's compositor doesn't support effects yet and orients the front stream we output as
     * if it came from the back sensor. When the two sensors are mounted 180° apart (the usual
     * case), flip the front frame along the sensor's long axis so the inset ends up upright and
     * unmirrored (verified on a Pixel 10).
     */
    private fun frontCorrection(isFront: Boolean): FloatArray? {
        if (!isFront) return null
        val front = sensorRotations[true] ?: return null
        val back = sensorRotations[false] ?: return null
        if ((front - back + 360) % 360 != 180) return null
        return FloatArray(16).also {
            Matrix.setIdentityM(it, 0)
            Matrix.translateM(it, 0, 0.5f, 0.5f, 0f)
            Matrix.scaleM(it, 0, -1f, 1f, 1f)
            Matrix.translateM(it, 0, -0.5f, -0.5f, 0f)
        }
    }

    private fun ensureGl() {
        if (display != EGL14.EGL_NO_DISPLAY) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        // Alpha is required: the compositor blends the front camera using it.
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No RGBA8888 EGL config"
        }
        config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        idleSurface = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)
        program = createProgram()
        positionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        sizeLoc = GLES20.glGetUniformLocation(program, "uSize")
        radiusLoc = GLES20.glGetUniformLocation(program, "uRadius")
    }

    private fun createProgram(): Int {
        fun compile(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        }
        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
            GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        }
    }

    @SuppressLint("RestrictedApi")
    private fun SurfaceRequest.isFrontCamera() =
        camera.cameraInfo.lensFacing == CameraSelector.LENS_FACING_FRONT

    @SuppressLint("RestrictedApi")
    private fun SurfaceRequest.sensorRotationDegrees() = camera.cameraInfo.sensorRotationDegrees

    /** SurfaceOutputImpl is package-private in CameraX, so its getCamera() is reached reflectively. */
    @SuppressLint("RestrictedApi")
    private fun SurfaceOutput.isFrontCamera(): Boolean {
        val camera = runCatching {
            javaClass.getMethod("getCamera").apply { isAccessible = true }.invoke(this) as? CameraInternal
        }.getOrNull()
        return camera?.cameraInfo?.lensFacing == CameraSelector.LENS_FACING_FRONT
    }

    private companion object {
        /** Corner radius relative to the front frame's shorter side; the inset is shown at 30%. */
        const val CORNER_RADIUS_FRACTION = 0.08f

        val Positions = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val TexCoords = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        // Rounded-rectangle signed distance in pixels, with one pixel of anti-aliasing.
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES sTexture;
            uniform vec2 uSize;
            uniform float uRadius;
            varying vec2 vTexCoord;
            void main() {
                vec4 color = texture2D(sTexture, vTexCoord);
                float alpha = 1.0;
                if (uRadius > 0.0) {
                    vec2 halfSize = uSize * 0.5;
                    vec2 q = abs(gl_FragCoord.xy - halfSize) - (halfSize - vec2(uRadius));
                    float distance = length(max(q, 0.0)) - uRadius;
                    alpha = clamp(0.5 - distance, 0.0, 1.0);
                }
                gl_FragColor = vec4(color.rgb, alpha);
            }
        """

        fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(values).position(0) }
    }
}
