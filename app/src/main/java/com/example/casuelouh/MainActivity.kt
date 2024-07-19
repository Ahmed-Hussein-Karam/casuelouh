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
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import com.example.casuelouh.Garment

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var modelExecutor: ExecutorService
    private lateinit var fashionInterpreter: Interpreter
    private lateinit var patternInterpreter: Interpreter
    private lateinit var fashionLabels: Map<Int, List<String>>
    private lateinit var patternLabels: Map<Int, List<String>>

    private var fashionImageHeight = 0
    private var fashionImageWidth = 0
    private var patternImageHeight = 0
    private var patternImageWidth = 0
    private var fashionNumClassesList: List<Int> = emptyList()
    private var patternNumClassesList: List<Int> = emptyList()
    private lateinit var captureButton: ImageButton
    private var isCapturing = false
    private val fashionPredictions = mutableListOf<List<String>>()
    private val patternPredictions = mutableListOf<String>()

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

        toggleCaptureButton(false)
        captureButton.setOnClickListener {
            toggleCaptureButton(false)
            isCapturing = true
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        modelExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        initFashionLabels()
        initPatternLabels()
        initInterpretersAsync()
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
            if (!::fashionInterpreter.isInitialized || !::patternInterpreter.isInitialized || !isCapturing) {
                imageProxy.close()
                return
            }

            val bitmap = toBitmap(imageProxy)
            imageProxy.close()

            modelExecutor.execute {
                val fashionPrediction = classifyFashionImage(bitmap)
                val patternPrediction = classifyPatternImage(bitmap)

                fashionPredictions.add(fashionPrediction)
                patternPredictions.add(patternPrediction)

                val capturedGarment = getCapturedGarment()

                if (capturedGarment != null) {
                    Log.d("Captured Garment", capturedGarment.toString())

                    fashionPredictions.clear()
                    patternPredictions.clear()

                    runOnUiThread {
                        toggleCaptureButton(true)
                        isCapturing = false
                    }
                }
            }
        }
    }

    private fun classifyFashionImage(bitmap: Bitmap): List<String> {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, fashionImageWidth, fashionImageHeight, true)
        val fashionInputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, fashionImageHeight, fashionImageWidth, 3), org.tensorflow.lite.DataType.FLOAT32)
        val fashionOutputBuffers = fashionNumClassesList.map { numClasses ->
            TensorBuffer.createFixedSize(intArrayOf(1, numClasses), org.tensorflow.lite.DataType.FLOAT32)
        }

        val fashionByteBuffer = convertBitmapToByteBuffer(resizedBitmap)
        fashionInputBuffer.loadBuffer(fashionByteBuffer)

        val fashionOutputs = mutableMapOf<Int, Any>()
        fashionOutputBuffers.forEachIndexed { index, buffer ->
            fashionOutputs[index] = buffer.buffer.rewind()
        }

        fashionInterpreter.runForMultipleInputsOutputs(arrayOf(fashionInputBuffer.buffer), fashionOutputs)

        val fashionPredictions = fashionOutputBuffers.withIndex().map { (index, outputBuffer) ->
            getPrediction(index, outputBuffer.floatArray, fashionLabels)
        }

        return fashionPredictions
    }

    private fun classifyPatternImage(bitmap: Bitmap): String {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, patternImageWidth, patternImageHeight, true)
        val patternInputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, patternImageHeight, patternImageWidth, 3), org.tensorflow.lite.DataType.FLOAT32)
        val patternOutputBuffers = patternNumClassesList.map { numClasses ->
            TensorBuffer.createFixedSize(intArrayOf(1, numClasses), org.tensorflow.lite.DataType.FLOAT32)
        }

        val patternByteBuffer = convertBitmapToByteBuffer(resizedBitmap)
        patternInputBuffer.loadBuffer(patternByteBuffer)

        val patternOutputs = mutableMapOf<Int, Any>()
        patternOutputBuffers.forEachIndexed { index, buffer ->
            patternOutputs[index] = buffer.buffer.rewind()
        }

        patternInterpreter.runForMultipleInputsOutputs(arrayOf(patternInputBuffer.buffer), patternOutputs)

        val patternPredictions = patternOutputBuffers.withIndex().map { (index, outputBuffer) ->
            getPrediction(index, outputBuffer.floatArray, patternLabels)
        }

        return patternPredictions.joinToString(", ")
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

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * bitmap.height * bitmap.width * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(bitmap.height * bitmap.width)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var pixel = 0
        for (i in 0 until bitmap.height) {
            for (j in 0 until bitmap.width) {
                val value = intValues[pixel++]
                byteBuffer.putFloat(((value shr 16 and 0xFF) - 127.5f) / 127.5f)
                byteBuffer.putFloat(((value shr 8 and 0xFF) - 127.5f) / 127.5f)
                byteBuffer.putFloat(((value and 0xFF) - 127.5f) / 127.5f)
            }
        }
        return byteBuffer
    }

    private fun initInterpretersAsync() {
        modelExecutor.execute {
            initFashionInterpreter()
            initPatternInterpreter()
            runOnUiThread {
                captureButton.visibility = View.VISIBLE
                toggleCaptureButton(true)
            }
        }
    }

    private fun initFashionInterpreter() {
        val tfliteModel = loadModelFile("fashion_model.tflite")
        fashionInterpreter = Interpreter(tfliteModel)

        val fashionInputTensor = fashionInterpreter.getInputTensor(0)
        fashionImageHeight = fashionInputTensor.shape()[1]
        fashionImageWidth = fashionInputTensor.shape()[2]

        fashionNumClassesList = (0 until fashionInterpreter.outputTensorCount).map { index ->
            fashionInterpreter.getOutputTensor(index).shape()[1]
        }
    }

    private fun initPatternInterpreter() {
        val tfliteModel = loadModelFile("pattern_model.tflite")
        patternInterpreter = Interpreter(tfliteModel)

        val patternInputTensor = patternInterpreter.getInputTensor(0)
        patternImageHeight = patternInputTensor.shape()[1]
        patternImageWidth = patternInputTensor.shape()[2]

        patternNumClassesList = (0 until patternInterpreter.outputTensorCount).map { index ->
            patternInterpreter.getOutputTensor(index).shape()[1]
        }
    }

    private fun initFashionLabels() {
        val labelsInputStream = assets.open("fashion_labels.json")
        val reader = InputStreamReader(labelsInputStream)
        val type = object : TypeToken<Map<Int, List<String>>>() {}.type
        val labelsMap: Map<Int, List<String>> = Gson().fromJson(reader, type)

        fashionLabels = labelsMap.mapValues { it.value }
    }

    private fun initPatternLabels() {
        val labelsInputStream = assets.open("pattern_labels.json")
        val reader = InputStreamReader(labelsInputStream)
        val type = object : TypeToken<Map<Int, List<String>>>() {}.type
        val labelsMap: Map<Int, List<String>> = Gson().fromJson(reader, type)

        patternLabels = labelsMap.mapValues { it.value }
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    private fun getPrediction(featureIndex: Int, outputArray: FloatArray, labels: Map<Int, List<String>>): String {
        val UNKNOWN = "Unknown"
        val maxIndex = outputArray.indices.maxByOrNull { outputArray[it] } ?: -1
        if (outputArray[maxIndex] < 0.65) {
            return UNKNOWN
        }
        return labels[featureIndex]?.get(maxIndex) ?: UNKNOWN
    }

    private fun getCapturedGarment(): Garment? {
        if (fashionPredictions.size < 3 || patternPredictions.size < 3) {
            return null
        }

        val recentFashionPredictions = fashionPredictions.takeLast(3)
        val recentPatternPredictions = patternPredictions.takeLast(3)

        val fashionMatch = recentFashionPredictions.all { it == recentFashionPredictions[0] }
        val patternMatch = recentPatternPredictions.all { it == recentPatternPredictions[0] }

        if (fashionMatch && patternMatch) {
            val fashionPrediction = recentFashionPredictions[0]
            val patternPrediction = recentPatternPredictions[0]

            return Garment(
                type = fashionPrediction[0],
                baseColor = fashionPrediction[1],
                usage = fashionPrediction[2],
                gender = fashionPrediction[3],
                pattern = patternPrediction
            )
        }
        return null
    }
}
