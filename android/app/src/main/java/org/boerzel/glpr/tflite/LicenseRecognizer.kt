package org.boerzel.glpr.tflite

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import org.boerzel.glpr.utils.Logger
import org.opencv.android.Utils
import org.opencv.core.Core.BORDER_CONSTANT
import org.opencv.core.Core.copyMakeBorder
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel


/**
 * Extracts the license from an image of a car license plate as plain text
 */
class LicenseRecognizer @Throws(IOException::class)
constructor(context: Context) {

    // TensorFlow Lite interpreter for running inference with the TFLite model
    private lateinit var interpreter: Interpreter

    private var outputProbabilityBuffer: TensorBuffer

    // Initialize TFLite interpreter
    init {

        // Load TFLite model
        val assetManager = context.assets
        val model = loadModelFile(assetManager)

        // Configure TFLite Interpreter options
        val options = Interpreter.Options()
        options.setNumThreads(NUM_THREADS)

        //val gpuDelegate = GpuDelegate()
        //options.addDelegate(gpuDelegate)
        //options.setAllowBufferHandleOutput(true)
        //options.setAllowFp16PrecisionForFp32(true)
        //options.setUseNNAPI(true)

        // Create & initialize TFLite interpreter
        try {
            interpreter = Interpreter(model, options)
        }
        catch (e: Exception)
        {
            LOGGER.e("Creating interpreter failed: %s", e.message)
        }

        // Reads type and shape of input and output tensors, respectively.
        val imageTensorIndex = 0
        val imageShape = interpreter.getInputTensor(imageTensorIndex).shape() // {DIM_BATCH_SIZE, DIM_INPUT_WIDTH, DIM_INPUT_HEIGHT, DIM_INPUT_DEPTH}
        val imageDataType = interpreter.getInputTensor(imageTensorIndex).dataType()

        // Creates the input tensor.
        //val inputImageBuffer = TensorBuffer.createFixedSize(imageShape, imageDataType)

        val outputTensorIndex = 0
        val outputShape = interpreter.getOutputTensor(outputTensorIndex).shape()
        val outputDataType = interpreter.getOutputTensor(outputTensorIndex).dataType()

        // Creates the output tensor and its processor.
        outputProbabilityBuffer = TensorBuffer.createFixedSize(intArrayOf(DIM_BATCH_SIZE, TEXT_LENGTH, ALPHABET_LENGTH), outputDataType)
    }

    // Memory-map the model file in Assets
    @Throws(IOException::class)
    private fun loadModelFile(assetManager: AssetManager): MappedByteBuffer {
        val fileDescriptor = assetManager.openFd(MODEL_PATH)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * To classify an image, follow these steps:
     * 1. pre-process the input image
     * 2. run inference with the license recognition model
     * 3. post-process the output result for displaying in UI
     *
     * @param bitmap
     * @return car license as plain text
     */
    fun recognize(bitmap: Bitmap): String {

        // 1. Pre-processing
        val inputByteBuffer = preprocess(bitmap)

        // 2. Run inference
        interpreter.run(inputByteBuffer, outputProbabilityBuffer.buffer.rewind())

        // 3. Post-processing
        return postprocess(outputProbabilityBuffer)
    }

    /** Closes the interpreter */
    fun close() {
        interpreter.close()
    }

    /**
     * Preprocess the bitmap:
     * 1. resize image to the input size of the plate detection model
     * 2. convert image to grayscale
     * 3. convert image to ByteBuffer
     *
     * @param bitmap
     * @return preprocessed image as ByteBuffer
     */
    private fun preprocess(bitmap: Bitmap): ByteBuffer {

        val image = Mat()
        Utils.bitmapToMat(bitmap, image)

        // resize image to the desired width, while keeping the aspect ratio
        var resized = Mat()
        val ratio = DIM_INPUT_WIDTH.toDouble() / image.width()
        val newSize = Size(DIM_INPUT_WIDTH.toDouble(), (image.height() * ratio))
        Imgproc.resize(image, resized, newSize, 0.0, 0.0, Imgproc.INTER_AREA)

        // resize image to the desired height, keeping the desired width and the aspect ratio
        if (resized.height() > DIM_INPUT_HEIGHT)
        {
            // cut an edge at the top and bottom to obtain the desired height
            val deltaH = resized.height() - DIM_INPUT_HEIGHT.toDouble()
            val top = (deltaH / 2).toInt()
            val roi = Rect(0, top, resized.width(), DIM_INPUT_HEIGHT)
            resized = Mat(resized, roi)
        }
        else
        {
            // create a border at the top and bottom to get the desired height
            val deltaH = DIM_INPUT_HEIGHT.toDouble() - resized.height()
            val top = (deltaH / 2).toInt()
            val bottom = DIM_INPUT_HEIGHT - resized.height() - top
            copyMakeBorder(resized, resized, top, bottom, 0, 0, BORDER_CONSTANT)
        }

        // now the resized image has the expected input size of out license recognition model
        // convert the image to grayscale
        val gray = Mat()
        Imgproc.cvtColor(resized, gray, Imgproc.COLOR_BGR2GRAY)

        // only for debugging...
        //val resultBitmap = Bitmap.createBitmap(gray.rows(), gray.cols(), Bitmap.Config.ARGB_8888)
        //Utils.matToBitmap(gray.t(), resultBitmap)

        // return the resized image as ByteBuffer
        return convertMatToTfLiteInput(gray.t())
    }

    /**
     * Convert image into byte buffer
     *
     * @image image
     * @result image as ByteBuffer
     * */
    private fun convertMatToTfLiteInput(image: Mat): ByteBuffer {
        val imgData = ByteBuffer.allocateDirect(DIM_BATCH_SIZE * DIM_INPUT_WIDTH * DIM_INPUT_HEIGHT * DIM_INPUT_DEPTH * FLOAT_TYPE_SIZE)
        imgData.order(ByteOrder.nativeOrder())

        for (i in 0 until DIM_INPUT_WIDTH) {
            for (j in 0 until DIM_INPUT_HEIGHT) {
                val pixel = image[i, j][0].toFloat() / 255.0f // normalize pixel value between 0.0 and 1.0
                imgData.putFloat(pixel)
            }
        }

        return imgData
    }

    /**
     * Decode license text from output
     *
     * @outputArray output of the license detection model
     * @return license text
     */
    private fun postprocess(outputArray: TensorBuffer): String {
        val shape = outputArray.shape
        val floatArray = outputArray.floatArray

        val bestChar = IntArray(shape[1])
        for (i in 0 until shape[1]) {
            var maxIdx = -1
            var maxVal = -1.0f
            for (j in 0 until shape[2]) {
                val index = i * shape[2] + j
                val f = floatArray[index]
                if (f > maxVal) {
                    maxVal = f
                    maxIdx = j
                }
            }

            bestChar[i] = maxIdx
        }

        val res = mutableListOf<Int>()

        for (c in bestChar) {
            if (res.isEmpty())
                res.add(c)
            else if (res.last() != c)
                res.add(c)
        }

        var result = ""
        for (c in res) {
            if (c < ALPHABET.length && c >= 0) {
                result += ALPHABET[c]
            }
        }

        return result
    }

    companion object {

        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÜ0123456789- "

        // Name of the model file (under /assets folder)
        private const val MODEL_PATH = "glpr-model.tflite"

        // Number of threads in the java app
        private const val NUM_THREADS = 4

        // Input size
        private const val DIM_BATCH_SIZE = 1      // batch size
        private const val DIM_INPUT_WIDTH = 128   // input image width
        private const val DIM_INPUT_HEIGHT = 64   // input image height
        private const val DIM_INPUT_DEPTH = 1     // 1 for gray scale & 3 for color images
        private const val FLOAT_TYPE_SIZE = 4

        /* Output*/
        private const val TEXT_LENGTH = 32
        private const val ALPHABET_LENGTH = 42

        private val LOGGER = Logger()
    }
}
