package com.arongranberg.chocolate

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Color
import com.arongranberg.chocolate.R.*
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log

import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley

import org.json.JSONException
import org.json.JSONObject

import android.os.Handler
import android.widget.*
import com.android.volley.*
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineDataSet
import org.joda.time.*
import org.joda.time.format.ISODateTimeFormat
import java.util.*
import kotlin.concurrent.timerTask
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.utils.ColorTemplate


class MainActivity : AppCompatActivity() {
    val handler = Handler()
    var timer : Timer = Timer()
    var label: TextView? = null
    var startButton: Button? = null
    var progress: ProgressBar? = null

    var dirtyingTime : DateTime = DateTime.now()
    internal var lastSyncFailed = Observable(false)
    internal var lastSyncedVersion = -1
    internal var dirtyVersion = 0
    internal var hasPerformedGetSync = Observable(false)

    var started = Observable(false)
    var heat = Observable(Heat.Auto)
    var fan = Observable(Heat.Auto)
    val synced = Observable(SyncState.NotSyncing)
    var active = Observable(false)
    val syncTemperature = Observable(SyncState.NotSyncing)
    var requestQueue : RequestQueue? = null

    var chart : LineChart? = null
    var temperature : ArrayList<Entry> = ArrayList()

    companion object {
        private val TAG = "Chocolate"
        internal var lastSyncTime = DateTime.now()
    }

    enum class Heat {
        Off,
        On,
        Auto
    }
    enum class SyncState {
        NotSyncing,
        Syncing
    }

    interface IObservable {
        fun listen (listener : (() -> Unit))
    }

    class Observable<T>(initial : T) : IObservable {
        private var mValue = initial
        private val listeners = ArrayList<((T, T) -> Unit)>()
        private var mLastModifiedTime : DateTime = DateTime.now()

        public val lastModifiedTime : DateTime get() = mLastModifiedTime

        public var value : T
            get() = mValue
            set(value) {
                if (value != mValue) {
                    mLastModifiedTime = DateTime.now()
                    val prev = mValue
                    mValue = value
                    onChanged(prev, value)
                }
            }

        public override fun listen (listener : (() -> Unit)) {
            listen { a, b -> listener() }
        }

        public fun listen (listener : ((T,T) -> Unit)) {
            listeners.add(listener)
        }

        private fun onChanged(prev : T, current : T) {
            for (listener in listeners) {
                listener(prev, current)
            }
        }

        public fun init() {
            onChanged(value, value)
        }
    }

    fun <T>react (listener : ((T,T) -> Unit), observable : Observable<T>) {
        observable.listen(listener)
    }

    fun react(listener : (() -> Unit), vararg observables : IObservable) {
        for(observable in observables) {
            observable.listen(listener)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout.activity_main)

        requestQueue = Volley.newRequestQueue(this)

        label = findViewById(id.label) as TextView;
        progress = findViewById(id.progressBar) as ProgressBar;

        startButton = findViewById(id.start) as Button
        startButton!!.setOnClickListener({ started.value = !started.value })

        val fastenButton = findViewById(id.fasten) as Button
        fastenButton.setOnClickListener({ fasten() })

        val unfastenButton = findViewById(id.unfasten) as Button
        unfastenButton.setOnClickListener({ unfasten() })

        val chart = findViewById(R.id.chart) as LineChart
        this.chart = chart

        chart.setDrawBorders(false)
        chart.setDrawGridBackground(false)
        chart.setTouchEnabled(false)
        chart.legend.isEnabled = false
        chart.xAxis.setDrawGridLines(false)
        chart.axisLeft.setDrawGridLines(false)
        chart.axisRight.setDrawGridLines(false)
        chart.setDrawMarkers(false)
        chart.axisLeft.setDrawAxisLine(false)
        chart.axisRight.setDrawAxisLine(false)
        chart.xAxis.setDrawAxisLine(false)
        chart.description.isEnabled = false
        chart.axisRight.setDrawLabels(true)
        chart.axisRight.setDrawGridLines(true)
        chart.axisRight.textColor = Color.rgb(255, 255, 255)
        chart.xAxis.setDrawLabels(true)
        chart.xAxis.textColor = Color.rgb(255, 255, 255)
        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM

        react({ refreshLabel() }, started, lastSyncFailed)

        var currentAnimationSet = AnimatorSet()
        react({ previous, current ->
            handler.post {
                currentAnimationSet.cancel()
                currentAnimationSet = AnimatorSet()

                startButton!!.text = if (current) "Stop Tempering" else "Start Tempering"

                val enabledColor = resources.getColor(color.startedButtonColor, theme)
                val disabledColor = resources.getColor(color.notStartedButtonColor, theme)
                val startColor = if (previous) enabledColor else disabledColor
                val endColor = if (current) enabledColor else disabledColor

                /*val fadeIn = if(current) alarmTimeLabel else picker!!
                val fadeOut = if(current) picker!! else alarmTimeLabel

                val anim1 = ObjectAnimator.ofFloat(fadeOut, "alpha", fadeOut.alpha, 0f).setDuration(300)
                val anim2 = ObjectAnimator.ofFloat(fadeIn, "alpha", fadeIn.alpha, 1f).setDuration(300)*/
                val anim3 = ObjectAnimator.ofArgb(startButton!!.background, "tint", startColor, endColor).setDuration(400)

                //currentAnimationSet.playSequentially(anim1, anim2)
                currentAnimationSet.play(anim3)
                currentAnimationSet.start()
            }
        }, started)

        react({ previous, current ->
            val targetAlpha = if (current == SyncState.Syncing) 1.0f else 0.0f
            progress!!.animate().alpha(targetAlpha)
        }, synced)

        react({ dirty(); sync() }, started, heat, fan)

        react({ previous, current ->
            handler.post {
                if (current == SyncState.NotSyncing) {
                    // Plotting library bugs if the size is 0
                    if (temperature.size == 0) temperature.add(Entry(0f, 0f))

                    val minTime = (temperature.minBy { it.x })!!.x
                    val secondsInMinute = 60
                    val temp = temperature.map { Entry((it.x - minTime)/secondsInMinute, it.y) }.toMutableList()

                    // Sync just completed, update the graph
                    val dataSet = LineDataSet(temp, "") // add entries to dataset
                    dataSet.color = ColorTemplate.rgb("#edefae")
                    dataSet.lineWidth = 1.5f
                    dataSet.valueTextColor = 0xFFFFFF
                    dataSet.setDrawCircles(false)
                    dataSet.axisDependency = YAxis.AxisDependency.LEFT

                    val lineData = LineData(dataSet)
                    chart.data = lineData
                    //val lineData = LineData(dataSet)
                    //chart.data = lineData
                    chart.notifyDataSetChanged()
                    chart.invalidate()
                }
            }
        }, syncTemperature)
        Log.v(TAG, "CREATE")

        (findViewById(id.heat_off) as Button).setOnClickListener { heat.value = Heat.Off }
        (findViewById(id.heat_on) as Button).setOnClickListener { heat.value = Heat.On }
        (findViewById(id.heat_auto) as Button).setOnClickListener { heat.value = Heat.Auto }

        (findViewById(id.fan_off) as Button).setOnClickListener { fan.value = Heat.Off }
        (findViewById(id.fan_on) as Button).setOnClickListener { fan.value = Heat.On }
        (findViewById(id.fan_auto) as Button).setOnClickListener { fan.value = Heat.Auto }
    }

    fun fasten () {
        sendCommand("fasten")
    }

    fun unfasten () {
        sendCommand("unfasten")
    }

    fun refreshLabel () {
        if (lastSyncFailed.value) {
            label!!.text = "Cannot connect to device!"
        } else {
            label!!.text = ""
        }
    }

    override fun onResume() {
        super.onResume()
        Log.v(TAG, "RESUME")
        hasPerformedGetSync.value = false
        synced.value = SyncState.NotSyncing

        lastSyncFailed.init()
        hasPerformedGetSync.init()
        started.init()
        synced.init()
        syncTemperature.init()
        heat.init()
        fan.init()

        timer.cancel()
        timer = Timer()
        timer.schedule(timerTask { handler.post({ refresh() }) }, 0L, 500L)

        active.value = true
        refresh()
    }

    override fun onPause() {
        super.onPause()
        active.value = false
    }

    override fun onStop() {
        super.onStop()
        timer.cancel()
    }

    internal fun refresh() {
        refreshLabel()

        if (active.value && syncTemperature.value == SyncState.NotSyncing && Duration(syncTemperature.lastModifiedTime, DateTime.now()).millis > 1000) syncTemperature()

        if ((lastSyncedVersion < dirtyVersion || !hasPerformedGetSync.value) && synced.value != SyncState.Syncing && Duration(dirtyingTime, DateTime.now()).millis > 800) {
            sync()
        }
    }

    internal fun dirty(syncImmediately : Boolean = false) {
        Log.v(TAG, "Dirtying...")
        dirtyVersion++
        if (syncImmediately) {
            dirtyingTime = DateTime.now().minusDays(1000)
        } else {
            dirtyingTime = DateTime.now()
        }
        refresh()
    }

    fun sync() {
        if (synced.value != SyncState.Syncing) {
            sync(hasPerformedGetSync.value)
        }
    }

    val secret = 235174146;

    internal fun sendCommand(command : String) {
        val url = "http://home.arongranberg.com:6001/command/" + command

        val jsonRequest = JSONObject()

        try {
            jsonRequest.put("secret", secret)
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to serialize")
            return
        }

        // Request a string response from the provided URL.
        val request = JsonObjectRequest(Request.Method.POST, url, jsonRequest,
                Response.Listener<org.json.JSONObject> {
                    response -> handler.post({ refresh() })
                },
                Response.ErrorListener {
                    error ->
                    Log.v(TAG, "Error " + error.message)
                    lastSyncFailed.value = true
                })

        // Add the request to the RequestQueue.
        requestQueue!!.add(request)
    }

    internal fun syncTemperature() {
        val url = "http://home.arongranberg.com:6001/temperature"

        val jsonRequest = JSONObject()

        try {
            jsonRequest.put("secret", secret)
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to serialize")
            return
        }

        syncTemperature.value = SyncState.Syncing
        // Request a string response from the provided URL.
        val request = JsonObjectRequest(Request.Method.POST, url, jsonRequest,
                Response.Listener<org.json.JSONObject> {
                    response ->
                    syncTemperature.value = SyncState.NotSyncing
                    val arr = response.getJSONArray("temperature")
                    val temp = ArrayList<Entry>()
                    for (i in 0..arr.length()-1) {
                        val item = arr.getJSONArray(i)
                        temp.add(Entry(item.getDouble(0).toFloat(), item.getDouble(1).toFloat()))
                    }

                    temperature = temp
                    handler.post({ refresh() })
                },
                Response.ErrorListener {
                    error ->
                    syncTemperature.value = SyncState.NotSyncing
                    Log.v(TAG, "Error " + error.message)
                    lastSyncFailed.value = true
                })

        // Add the request to the RequestQueue.
        requestQueue!!.add(request)
    }

    internal fun sync(upload : Boolean) {
        val url = "http://home.arongranberg.com:6001/" + (if (upload) "store" else "get")

        val jsonRequest = JSONObject()

        try {
            if (upload) {
                jsonRequest.put("tempering", started.value)
                jsonRequest.put("heat", heat.value.toString())
                jsonRequest.put("fan", fan.value.toString())
            }
            jsonRequest.put("secret", secret)
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to serialize")
            return
        }

        // Will be reset if the sync fails
        synced.value = SyncState.Syncing
        val version = dirtyVersion
        Log.v(TAG, "Starting " + version + " " + url)
        // Request a string response from the provided URL.
        val request = JsonObjectRequest(Request.Method.POST, url, jsonRequest,
                Response.Listener<org.json.JSONObject> {
                    response ->
                        Log.v(TAG, "Response")
                        if (upload) {
                            lastSyncedVersion = version
                        } else {
                            hasPerformedGetSync.value = true
                            // User has not changed UI since sync was started. Ok to apply settings
                            Log.v(TAG,response.toString())
                            started.value = response.getBoolean("tempering")
                            heat.value = Heat.valueOf(response.getString("heat"))
                            fan.value = Heat.valueOf(response.getString("fan"))
                            Log.v(TAG, "Response: " + response.getBoolean("tempering"))

                            lastSyncedVersion = dirtyVersion
                            Log.v(TAG, "Response Complete at " + lastSyncedVersion)
                        }

                        synced.value= SyncState.NotSyncing
                        lastSyncFailed.value = false
                        handler.post({ refresh() })
                },
                Response.ErrorListener {
                    error ->
                        Log.v(TAG, "Error " + error.message)
                        synced.value = SyncState.NotSyncing
                        lastSyncFailed.value = true
                })

        // Add the request to the RequestQueue.
        requestQueue!!.add(request)
    }
}
