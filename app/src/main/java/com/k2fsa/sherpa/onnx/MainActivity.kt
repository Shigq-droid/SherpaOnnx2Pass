package com.k2fsa.sherpa.onnx

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.concurrent.thread
import java.io.File
import java.io.FileOutputStream

private const val TAG = "sherpa-onnx"
private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

// adb emu avd hostmicon
// to enable microphone inside the emulator
class MainActivity : AppCompatActivity() {
    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    private lateinit var onlineRecognizer: OnlineRecognizer
    private lateinit var offlineRecognizer: OfflineRecognizer
    private var audioRecord: AudioRecord? = null
    private lateinit var recordButton: Button
    private lateinit var textView: TextView
    private var recordingThread: Thread? = null

    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO

    private var samplesBuffer = arrayListOf<FloatArray>()

    // Note: We don't use AudioFormat.ENCODING_PCM_FLOAT
    // since the AudioRecord.read(float[]) needs API level >= 23
    // but we are targeting API level >= 21
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var idx: Int = 0
    private var lastText: String = ""
    private lateinit var loadingStatusText: TextView

    @Volatile
    private var isRecording: Boolean = false

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionHelper.handlePermissionsResult(requestCode, grantResults)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)

        textView = findViewById(R.id.my_text)
        textView.movementMethod = ScrollingMovementMethod()


        loadingStatusText = findViewById(R.id.loading_status)
        loadingStatusText.visibility = View.VISIBLE
        textView.visibility = View.GONE
        loadingStatusText.text = "正在加载模型，请稍候..."

        thread(start = true) {
            Log.i(TAG, "Start to initialize first-pass recognizer")
            initOnlineRecognizer()
            Log.i(TAG, "Finished initializing first-pass recognizer")

            Log.i(TAG, "Start to initialize second-pass recognizer")
            initOfflineRecognizer()
            Log.i(TAG, "Finished initializing second-pass recognizer")

            runOnUiThread {
                loadingStatusText.text = "模型加载完成"
                loadingStatusText.postDelayed({
                    loadingStatusText.visibility = View.GONE
                    textView.visibility = View.VISIBLE
                }, 300)
            }
        }

        recordButton = findViewById(R.id.record_button)
        recordButton.setOnClickListener { onclick() }

        val exportTxtButton = findViewById<Button>(R.id.export_txt_button)
        exportTxtButton.setOnClickListener {
            exportAllText()
        }

        PermissionHelper.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        ) { granted ->
            if (granted) {
                Log.i(TAG, "权限申请成功，可正常使用")
            } else {
                Toast.makeText(this, "权限未通过，无法使用功能", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun onclick() {
        if (!isRecording) {
            val ret = initMicrophone()
            if (!ret) {
                Log.e(TAG, "Failed to initialize microphone")
                return
            }
            Log.i(TAG, "state: ${audioRecord?.state}")
            audioRecord!!.startRecording()
            recordButton.setText(R.string.stop)
            isRecording = true
            samplesBuffer.clear()
            textView.text = ""
            lastText = ""
            idx = 0

            recordingThread = thread(true) {
                processSamples()
            }
            Log.i(TAG, "Started recording")
        } else {
            isRecording = false
            audioRecord!!.stop()
            audioRecord!!.release()
            audioRecord = null
            recordButton.setText(R.string.start)
            Log.i(TAG, "Stopped recording")

            // 🔥 保存录音内容为 .wav 文件
            if (!samplesBuffer.isNotEmpty()) {
                val filename = "audio_${System.currentTimeMillis()}"
                saveAudioAsWav(samplesBuffer, filename)
            }
        }
    }

    private fun processSamples() {
        Log.i(TAG, "processing samples")
        val stream = onlineRecognizer.createStream()

        val interval = 0.1 // i.e., 100 ms
        val bufferSize = (interval * sampleRateInHz).toInt() // in samples
        val buffer = ShortArray(bufferSize)

        while (isRecording) {
            val ret = audioRecord?.read(buffer, 0, buffer.size)
            if (ret != null && ret > 0) {
                val samples = FloatArray(ret) { buffer[it] / 32768.0f }
                samplesBuffer.add(samples)

                stream.acceptWaveform(samples, sampleRate = sampleRateInHz)
                while (onlineRecognizer.isReady(stream)) {
                    onlineRecognizer.decode(stream)
                }
                val isEndpoint = onlineRecognizer.isEndpoint(stream)
                var textToDisplay = lastText

                var text = onlineRecognizer.getResult(stream).text
                if (text.isNotBlank()) {
                    textToDisplay = if (lastText.isBlank()) {
                        // textView.text = "${idx}: ${text}"
                        "${idx}: $text"
                    } else {
                        "${lastText}\n${idx}: $text"
                    }
                }

                if (isEndpoint) {
                    onlineRecognizer.reset(stream)

                    if (text.isNotBlank()) {
                        text = runSecondPass()

                        lastText = "${lastText}\n${idx}: $text"
                        idx += 1
                    } else {
                        samplesBuffer.clear()
                    }
                }

                runOnUiThread {
                    textView.text = textToDisplay.lowercase()
                }
            }
        }
        stream.release()
    }

    private fun initMicrophone(): Boolean {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
            return false
        }

        val numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
        Log.i(
            TAG, "buffer size in milliseconds: ${numBytes * 1000.0f / sampleRateInHz}"
        )

        audioRecord = AudioRecord(
            audioSource,
            sampleRateInHz,
            channelConfig,
            audioFormat,
            numBytes * 2 // a sample has two bytes as we are using 16-bit PCM
        )
        return true
    }

    private fun initOnlineRecognizer() {
        // Please change getModelConfig() to add new models
        // See https://k2-fsa.github.io/sherpa/onnx/pretrained_models/index.html
        // for a list of available models
        val firstType = 9
        val firstRuleFsts: String?
        firstRuleFsts = null
        Log.i(TAG, "Select model type $firstType for the first pass")
        val config = OnlineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80),
            modelConfig = getModelConfig(type = firstType)!!,
            endpointConfig = getEndpointConfig(),
            enableEndpoint = true,
        )
        if (firstRuleFsts != null) {
            config.ruleFsts = firstRuleFsts;
        }

        onlineRecognizer = OnlineRecognizer(
            assetManager = application.assets,
            config = config,
        )
    }

    private fun initOfflineRecognizer() {
        // Please change getOfflineModelConfig() to add new models
        // See https://k2-fsa.github.io/sherpa/onnx/pretrained_models/index.html
        // for a list of available models
        val secondType = 0
        var secondRuleFsts: String?
        secondRuleFsts = null
        Log.i(TAG, "Select model type $secondType for the second pass")

        val config = OfflineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80),
            modelConfig = getOfflineModelConfig(type = secondType)!!,
        )

        if (secondRuleFsts != null) {
            config.ruleFsts = secondRuleFsts
        }

        offlineRecognizer = OfflineRecognizer(
            assetManager = application.assets,
            config = config,
        )
    }

    private fun runSecondPass(): String {
        var totalSamples = 0
        for (a in samplesBuffer) {
            totalSamples += a.size
        }
        var i = 0

        val samples = FloatArray(totalSamples)

        // todo(fangjun): Make it more efficient
        for (a in samplesBuffer) {
            for (s in a) {
                samples[i] = s
                i += 1
            }
        }


        val n = maxOf(0, samples.size - 8000)

        samplesBuffer.clear()
        samplesBuffer.add(samples.sliceArray(n until samples.size))

        val stream = offlineRecognizer.createStream()
        stream.acceptWaveform(samples.sliceArray(0..n), sampleRateInHz)
        offlineRecognizer.decode(stream)
        val result = offlineRecognizer.getResult(stream)

        stream.release()

//        saveResultToFile(result.text)  // ✅ 自动保存每段文本
        return result.text
    }

    private fun saveResultToFile(text: String) {
        val file = File(getExternalFilesDir(null), "asr_result.txt")
        FileOutputStream(file, true).bufferedWriter().use { writer ->
            writer.write("${System.currentTimeMillis()}:\n$text\n\n")
        }
    }

    private fun exportAllText() {
        val file = File(getExternalFilesDir(null), "final_asr_export.txt")
        file.writeText(textView.text.toString())
        runOnUiThread {
            Toast.makeText(this, "已导出到 ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveAudioAsWav(samples: List<FloatArray>, filename: String) {
        val wavFile = File(getExternalFilesDir(null), "$filename.wav")
        val out = FileOutputStream(wavFile)

        val sampleRate = sampleRateInHz
        val bitsPerSample = 16
        val numChannels = 1
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val rawData = samples.flatMap { floatArray ->
            floatArray.flatMap { sample ->
                val s = (sample * 32767).toInt().coerceIn(-32768, 32767)
                listOf((s and 0xff).toByte(), ((s shr 8) and 0xff).toByte())
            }
        }.toByteArray()


        val header = ByteArray(44)
        val totalDataLen = rawData.size + 36

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        writeIntLE(header, 4, totalDataLen)
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        writeIntLE(header, 16, 16)
        writeShortLE(header, 20, 1)
        writeShortLE(header, 22, numChannels.toShort())
        writeIntLE(header, 24, sampleRate)
        writeIntLE(header, 28, byteRate)
        writeShortLE(header, 32, (numChannels * bitsPerSample / 8).toShort())
        writeShortLE(header, 34, bitsPerSample.toShort())
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        writeIntLE(header, 40, rawData.size)

        out.write(header)
        out.write(rawData)
        out.flush()
        out.close()

        Toast.makeText(this,"音频已保存: ${wavFile.absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun writeIntLE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value shr 8).toByte()
        buffer[offset + 2] = (value shr 16).toByte()
        buffer[offset + 3] = (value shr 24).toByte()
    }

    private fun writeShortLE(buffer: ByteArray, offset: Int, value: Short) {
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value.toInt() shr 8).toByte()
    }

}
