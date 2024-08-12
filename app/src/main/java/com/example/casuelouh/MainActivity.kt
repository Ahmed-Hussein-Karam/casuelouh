package com.example.casuelouh

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
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
import com.example.casuelouh.ui.theme.GoogleImagenApiResponse
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GoogleGenerativeAIException
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.auth.oauth2.GoogleCredentials
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var captureButton: ImageButton
    private lateinit var outfitOverlayImageView: ImageView
    private lateinit var promptStackOverlay: LinearLayout
    private lateinit var promptButtons: MutableList<Button>
    private lateinit var geminiApiKey: String
    private var isCapturing = false

    private lateinit var outfitPrompt: String
    private lateinit var emptyOutfit: String

    private lateinit var googleCredentials: GoogleCredentials

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

    private suspend fun getGoogleCredentials() {
        withContext(Dispatchers.IO) {
            try {
                val inputStream: InputStream =
                    resources.openRawResource(R.raw.casuelouh_service_account)
                googleCredentials = GoogleCredentials.fromStream(inputStream)
                    .createScoped(listOf("https://www.googleapis.com/auth/cloud-platform"))

                googleCredentials.refreshIfExpired()

                assert(googleCredentials.accessToken!!.tokenValue.isNotEmpty())
                Log.d("[DEBUG]: Access token: ", googleCredentials.accessToken!!.tokenValue)
            } catch (e: Exception) {
                Log.e("[ERROR] Unable to get access token, error: ", e.message.toString())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)
        outfitOverlayImageView = findViewById(R.id.outfitImageView)
        promptStackOverlay = findViewById(R.id.promptStackOverlay)
        promptButtons = mutableListOf<Button>()
        for (i in 0 until promptStackOverlay.childCount) {
            val view = promptStackOverlay.getChildAt(i)
            if (view is Button) {
                view.setOnClickListener {
                   applyPrompt(view.text.toString())
                }
                promptButtons.add(view)
            }
        }

        geminiApiKey = BuildConfig.GEMINI_API_KEY
        outfitPrompt = getString(R.string.outfit_prompt)
        emptyOutfit = getString(R.string.empty_outfit)

        lifecycleScope.launch {
            getGoogleCredentials()
        }

        captureButton.setOnClickListener {
            promptStackOverlay.visibility = View.GONE
            outfitOverlayImageView.visibility = View.GONE
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

                if (outfitResponse.outfit.isNotEmpty()) {
                    val promptCount = min(outfitResponse.hotPrompts.size, promptButtons.size)

                    if (promptCount > 0) {
                        for (i in 0 until promptCount) {
                            promptButtons[i].text = outfitResponse.hotPrompts[i]
                            promptButtons[i].visibility = View.VISIBLE
                        }
                    }

                    for (i in promptCount until promptButtons.size) {
                        promptButtons[i].visibility = View.GONE
                    }

                    lifecycleScope.launch {
                        generateOutfitImage(getString(R.string.outfit_plot, outfitResponse.outfit.toString()))

                        // Show hot prompts
                        promptStackOverlay.visibility = View.VISIBLE

                        toggleCaptureButton(true)
                    }
                }
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

    private fun base64ToBitmap(base64String: String): Bitmap? {
        return try {
            val decodedString = Base64.decode(base64String, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
        } catch (e: IllegalArgumentException) {
            Log.e("[ERROR] ", "could not parse base64 image")
            null
        }
    }

    // Function to display the image in ImageView
    private fun displayImageInOverlay(base64String: String) {
        val bitmap = base64ToBitmap(base64String)
        bitmap?.let {
            outfitOverlayImageView.setImageBitmap(it)
            outfitOverlayImageView.visibility = ImageView.VISIBLE
        }
    }

    private suspend fun generateOutfitImage(prompt: String) {
        val client = OkHttpClient()
        val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val jsonBody = "{\n" +
                "  \"instances\": [\n" +
                "    {\n" +
                "      \"prompt\": \"${prompt}\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"parameters\": {\n" +
                "    \"sampleCount\": 1\n" +
                "  }\n" +
                "}"

        val body = jsonBody.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .addHeader("Authorization", "Bearer ${googleCredentials.accessToken!!.tokenValue}")
            .url("https://europe-west2-aiplatform.googleapis.com/v1/projects/casuelouh/locations/europe-west2/publishers/google/models/imagegeneration@006:predict")
            .post(body)
            .build()

        // Execute the request
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i("[INFO]", "successful Imagen API response")
                    response.body?.let { responseBody ->
                        val apiResponseType = object : TypeToken<GoogleImagenApiResponse>() {}.type
                        val apiResponse = Gson().fromJson<GoogleImagenApiResponse>(responseBody.string(), apiResponseType)

                        val bytesBase64Encoded = apiResponse.predictions.firstOrNull()?.bytesBase64Encoded
                        Log.i("[INFO] Base64 image", bytesBase64Encoded.toString())
                        runOnUiThread {
                            if (bytesBase64Encoded != null) {
                                displayImageInOverlay(bytesBase64Encoded)
                            }
                        }
                    } ?: Log.w("[WARN]: ", "Google Imagen API response is null")
                }
                else {
                    Log.e("[ERROR] Google Imagen API error ", "Response: ${response.body?.string()}")
                }
            }
        }
    }
    
    private fun applyPrompt(prompt: String) {
        Toast.makeText(this, prompt, Toast.LENGTH_SHORT).show()
    }
}
