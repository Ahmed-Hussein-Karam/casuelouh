package com.example.casuelouh

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GoogleGenerativeAIException
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var captureButton: ImageButton
    private lateinit var geminiApiKey: String
    private var isCapturing = false

    private lateinit var outfitPrompt: String
    private lateinit var emptyOutfit: String
    private lateinit var outfitPlot: String
    private lateinit var hotPrompt: String

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Log.e("CameraPermission", "Camera permission is denied")
        }
    }

    private fun toggleCaptureButton(enabled: Boolean) {
        captureButton.isEnabled = enabled

        if (enabled) {
            captureButton.setImageResource(android.R.drawable.ic_menu_view)
        } else {
            captureButton.setImageResource(android.R.drawable.ic_popup_sync)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)

        geminiApiKey = BuildConfig.GEMINI_API_KEY
        outfitPrompt = getString(R.string.outfit_prompt)
        emptyOutfit = getString(R.string.empty_outfit)
        outfitPlot = getString(R.string.outfit_plot)
        hotPrompt = getString(R.string.hot_prompt)

        captureButton.setOnClickListener {
            toggleCaptureButton(false)
            isCapturing = true
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        captureButton.visibility = View.VISIBLE
        toggleCaptureButton(true)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(cameraExecutor, MyImageAnalyzer())
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("CameraX", "Binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private inner class MyImageAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            if (isCapturing && geminiApiKey.isEmpty()) {
                Log.e("GEMINI_API_KEY", "Could not found environment variable with name 'GEMINI_API_KEY'")
                isCapturing = false
            }
            if (! isCapturing) {
                imageProxy.close()
                return
            }

            val bitmap = toBitmap(imageProxy)
            imageProxy.close()

            lifecycleScope.launch {
                val generativeModel = GenerativeModel(
                    modelName = "gemini-1.5-flash",
                    apiKey = geminiApiKey,
                    generationConfig {  responseMimeType = "application/json"}
                )

                val inputContent = content {
                    image(bitmap)
                    text(outfitPrompt)
                }

                var response_txt = emptyOutfit
                try {
                    response_txt = generativeModel.generateContent(inputContent).text?: emptyOutfit
                } catch (e: GoogleGenerativeAIException) {
                    Log.e("GoogleGenerativeAIException: ", e.toString())
                }

                Log.d("Outfit prompt: ", outfitPrompt)
                Log.d("Gemini response: ", response_txt)

                val outfitResponse = parseOutfitJson(response_txt)

                toggleCaptureButton(true)
            }

            isCapturing = false
        }
    }

    private fun toBitmap(imageProxy: ImageProxy): Bitmap {
        @OptIn(ExperimentalGetImage::class)
        val image = imageProxy.image ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun parseOutfitJson(jsonString: String): OutfitResponse {
        var data = Gson().fromJson(emptyOutfit, OutfitResponse::class.java)
        try {
            data = Gson().fromJson(jsonString, OutfitResponse::class.java)
        } catch (e: JsonSyntaxException) {
            Log.e("JsonSyntaxException: ", jsonString)
        }

        return data
    }
}
