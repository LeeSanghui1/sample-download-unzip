package com.mackerly.sample.downunzip

import android.Manifest
import android.content.pm.PackageManager
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.ActivityCompat
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class DownloadAndUnzipActivity : AppCompatActivity() {
    val handler: Handler = DownloadAndUnzipHandler(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_and_unzip)

        requestPermissions()

        findViewById<Button>(R.id.btn_download).setOnClickListener {
            DownloadThread().start()
        }

        val btnUnzip = findViewById<Button>(R.id.btn_unzip)
        btnUnzip.setOnClickListener {
            val filePath = findViewById<TextView>(R.id.tv_filePath).text.toString()
            UnzipThread(filePath).start()
        }
        btnUnzip.isEnabled = savedInstanceState?.getBoolean("isDownloaded") == true

        val progressBar = findViewById<ProgressBar>(R.id.pb_download)
        val percentage = savedInstanceState?.getInt("percentage") ?: 0
        progressBar.progress = percentage

        if(percentage == 100) {
            findViewById<TextView>(R.id.tv_filePath).text = savedInstanceState?.getString("fileAbsolutePath")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        val percentage: Int = findViewById<ProgressBar>(R.id.pb_download).progress
        val isDownloaded = percentage == 100
        val fileAbsolutePath = findViewById<TextView>(R.id.tv_filePath).text.toString()

        outState.putInt("percentage", percentage)
        outState.putBoolean("isDownloaded", isDownloaded)
        outState.putString("fileAbsolutePath", fileAbsolutePath)
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val wPermission: Int = ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val rPermission: Int = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)

            if (wPermission != PackageManager.PERMISSION_GRANTED || rPermission != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                    PERMISSION_REQUEST_CODE)
            }
        }
    }

    inner class DownloadThread: Thread() {

        override fun run() {
            connect()
        }

        private fun connect() {
            val urlString = "https://www.sqlite.org/2022/sqlite-tools-linux-x86-3380000.zip"
            val url = URL(urlString)
            var conn: HttpURLConnection? = null
            var urlIs: InputStream? = null
            var fos: FileOutputStream? = null

            try {
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connect()

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {

                    val disposition = conn.getHeaderField("Content-Disposition")
                    val fileName = if (disposition != null) {
                        disposition.substring(disposition.lastIndexOf("filename=") + 10, disposition.length)
                    } else {
                        urlString.substring(urlString.lastIndexOf("/") + 1, urlString.length)
                    }

                    // TODO Android 11 Scoped External Storage 반영
                    val file = File(Environment.getExternalStorageDirectory().path + "/Download/", fileName)

                    urlIs = conn.inputStream
                    fos = FileOutputStream(file)

                    val bufferSize = 2048
                    val b = ByteArray(bufferSize)
                    var len: Int = 0
                    var downloaded: Int = 0

                    while(true) {
                        len = urlIs.read(b)
                        if (len == -1) {
                            break
                        }
                        downloaded += len
                        fos.write(b, 0, len)

                        val percentage = (downloaded / conn.contentLength.toDouble() * 100).toInt()

                        val message = handler.obtainMessage()
                        message.what = DOWNLOAD_HANDLER_MESSAGE
                        val bundle = Bundle()
                        bundle.putInt("percentage", percentage)
                        bundle.putString("fileName", fileName)
                        bundle.putString("path", file.absolutePath)
                        message.data = bundle
                        handler.sendMessage(message)
                    }

                } else {
                    Log.d(TAG, "HTTP request failure, response code : ${conn.responseCode}")
                }

            } catch (e: IOException) {
                Log.d(TAG, "connection failure $e")
            } finally {
                urlIs?.close()
                fos?.close()
                conn?.disconnect()
            }
        }
    }

    inner class UnzipThread(private val fileAbsolutePath: String): Thread() {
        override fun run() {
            var zis: ZipInputStream? = null
            var fos: FileOutputStream? = null

            val message = handler.obtainMessage()
            message.what = UNZIP_HANDLER_MESSAGE
            val bundle = Bundle()

            try {
                zis = ZipInputStream(FileInputStream(File(fileAbsolutePath)))
                val targetDirectory = File(fileAbsolutePath.replace(".zip", ""))
                targetDirectory.mkdir()

                while (true) {
                    val zipEntry = zis.nextEntry ?: break
                    if (zipEntry.isDirectory) {
                        File(targetDirectory, zipEntry.name).mkdir()
                    } else {
                        fos = FileOutputStream(File(targetDirectory, zipEntry.name))
                        val b = ByteArray(2048)
                        while(true) {
                            val len = zis.read(b)
                            if(len == -1) {
                                break;
                            }
                            fos.write(b, 0, len)
                        }
                    }
                }
                bundle.putInt("result", UNZIP_SUCCESS)

            } catch (e: IOException) {
                Log.d(TAG, "unzip failure $e")
                bundle.putInt("result", UNZIP_FAILURE)
            } finally {
                zis?.close()
                fos?.close()
            }

            message.data = bundle
            handler.sendMessage(message)
        }
    }

    companion object {
        private const val TAG = "DownUnzipActivity"

        private const val PERMISSION_REQUEST_CODE = 100

        private const val DOWNLOAD_HANDLER_MESSAGE = 0
        private const val UNZIP_HANDLER_MESSAGE = 1

        private const val UNZIP_SUCCESS = 1
        private const val UNZIP_FAILURE = 2

        class DownloadAndUnzipHandler(private val activity: DownloadAndUnzipActivity) : Handler(
            Looper.getMainLooper()
        ) {
            override fun handleMessage(msg: Message) {
                super.handleMessage(msg)
    
                when (msg.what) {
                    DOWNLOAD_HANDLER_MESSAGE -> {
                        val bundle = msg.data
                        val percentage = bundle.getInt("percentage")
                        activity.findViewById<ProgressBar>(R.id.pb_download).progress = percentage
                        activity.findViewById<TextView>(R.id.tv_fileName).text = bundle.getString("fileName")
                        activity.findViewById<TextView>(R.id.tv_filePath).text = bundle.getString("path")

                        if (percentage == 100) {
                           activity.findViewById<Button>(R.id.btn_unzip).isEnabled = true
                        }
                    }
                    UNZIP_HANDLER_MESSAGE -> {
                        val bundle = msg.data
                        val result = bundle.getInt("result")
                        val resultText = if (result == 1) {
                            "압축 해제 성공"
                        } else {
                            "압축 해제 실패"
                        }
                        activity.findViewById<TextView>(R.id.tv_unzip_result).text = resultText
                    }
                    else -> {
                        Log.d(TAG, "Unknown handler message : ${msg.what}")
                    }
                }
            }
        }
    }
}