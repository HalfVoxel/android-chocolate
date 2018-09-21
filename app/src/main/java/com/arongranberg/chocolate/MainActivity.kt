package com.arongranberg.chocolate

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.*
import app.akexorcist.bluetotohspp.library.BluetoothSPP
import app.akexorcist.bluetotohspp.library.BluetoothState
import app.akexorcist.bluetotohspp.library.DeviceList
import com.arongranberg.chocolate.R.*
import com.arongranberg.chocolate.R.id.current_temperature
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.utils.ColorTemplate
import org.joda.time.DateTime
import org.joda.time.Duration
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.timerTask

class MainActivity : Activity() {
    val handler = Handler()
    var timer : Timer = Timer()
    var label: TextView? = null
    var startButton: Button? = null
    var progress: ProgressBar? = null

    val bluetooth = BluetoothSPP(this);

    var dirtyingTime : DateTime = DateTime.now()
    internal var lastSyncFailed = Observable(false)
    internal var lastSyncedVersion = -1
    internal var dirtyVersion = 0
    internal var hasPerformedGetSync = Observable(false)

    val started = Observable(false)
    val heat = Observable(Heat.Auto)
    val fan = Observable(Heat.Auto)
    val synced = Observable(SyncState.NotSyncing)
    val active = Observable(false)
    val mass = Observable(0.100f)
    val minTemp = Observable(27f)
    val maxTemp = Observable(48f)
    val finalTemp = Observable(32f)
    val sliderMode = Observable(SliderMode.Mass)

    val syncTemperature = Observable(SyncState.NotSyncing)

    var chart : LineChart? = null
    var temperature : ArrayList<Entry> = ArrayList()
    var temperaturePrediction : ArrayList<Entry> = ArrayList()
    var forcedStage = Observable(State.Auto)

    companion object {
        private val TAG = "Chocolate"
        internal var lastSyncTime = DateTime.now()
    }

    enum class SliderMode {
        Mass,
        MinT,
        MaxT,
        FinalT
    }

    enum class Heat {
        Off,
        On,
        Auto
    }

    enum class State {
        Heat,
        Cool,
        Keep,
        Auto
    }

    enum class SyncState {
        NotSyncing,
        Syncing
    }

    interface IObservable {
        fun listen (listener : (() -> Unit))
    }

    class Heater(val minT: Float, val maxT: Float, val finalT: Float) {
        var stage = 0
        var output = 0.0

        fun update(temperature: Float, dt: Float): Float {
            if (stage == 0 && temperature > maxT) stage = 1
            if (stage == 1 && temperature < minT) stage = 2

            var heatOn = when(stage) {
                0 -> true
                1 -> false
                2 -> temperature < finalT
                else -> false
            }

            if (heatOn) {
                output = 1.0 - (1.0 - output) * Math.exp(-0.1*dt)
            } else {
                output *= Math.exp(-0.1 * dt)
            }

            return output.toFloat()
        }
    }

    fun heatInput(heater: Heater, temperature: Float, dt: Float): Float {
        val power = 25 // Watts
        return heater.update(temperature, dt) * power * dt
    }

    val roomTemperature = 25f

    // The temperature (in Celcius) that corresponds to zero energy
    // This should be the room temperature
    val specificHeatBelow40 = 1590f // J/(kgC°)
    val specificHeatAbove40 = 1670f // J/(kgC°)
    val latentHeat = 45000f // J/kg

    fun specificHeat (temperature: Float): Float {
        if (temperature < 26) {
            return specificHeatBelow40
        } else if (temperature < 26 + 1) {
            return specificHeatBelow40 + latentHeat/1
        } else if (temperature < 40) {
            return specificHeatBelow40
        } else {
            return specificHeatAbove40
        }
    }

    val energyToTemperatureT = FloatArray(100, { 0f })
    val energyToTemperatureE = FloatArray(100, { 0f })

    init {
        val dT = 1f
        var T = 0.0f
        var E = 0.0f
        for (i in 0 until energyToTemperatureT.size) {
            E += specificHeat(T + 0.5f*dT)
            T += dT
            energyToTemperatureT[i] = T
            energyToTemperatureE[i] = E
        }
    }

    /** Maps a value in the range [start1, start2] to the range [end1, end2] */
    fun mapTo(start1: Float, start2: Float, end1: Float, end2: Float, value: Float): Float {
        return ((value - start1)/(start2 - start1)) * (end2 - end1) + end1
    }

    fun energyToTemperature(energy: Float, mass: Float): Float {
        val energyPerMass = energy / mass
        var mn = 0
        var mx = energyToTemperatureT.size
        while(mx > mn + 1) {
            val mid = (mn + mx)/2
            if (energyToTemperatureE[mid] > energyPerMass) {
                mx = mid
            } else {
                mn = mid
            }
        }
        return mapTo(energyToTemperatureE[mn]*mass, energyToTemperatureE[mx]*mass, energyToTemperatureT[mn], energyToTemperatureT[mx], energy)
        //return roomTemperature + energy / (specificHeat * mass)
    }

    fun temperatureToEnergy(temperature: Float, mass: Float): Float {
        var mn = 0
        var mx = energyToTemperatureT.size
        while(mx > mn + 1) {
            val mid = (mn + mx)/2
            if (energyToTemperatureT[mid] > temperature) {
                mx = mid
            } else {
                mn = mid
            }
        }
        return mapTo(energyToTemperatureT[mn], energyToTemperatureT[mx], energyToTemperatureE[mn]*mass, energyToTemperatureE[mx]*mass, temperature)
        //return (temperature - roomTemperature) * (specificHeat * mass)
    }

    fun predictTempering(): ArrayList<Entry> {
        var energy = 0f
        val mass = this.mass.value

        var time = 0f
        val heater = Heater(minTemp.value, maxTemp.value, finalTemp.value)

        // Bootstrap heater
        val temperatureClone = temperature.clone() as ArrayList<Entry>
        for (item in temperatureClone) {
            heater.update(item.y, 5f)
        }

        val stage = forcedStage.value
        if (stage != State.Auto) {
            heater.stage = stage.ordinal
        }

        val prediction = ArrayList<Entry>()
        if (temperatureClone.isEmpty()) {
            energy = temperatureToEnergy(roomTemperature, mass)
            prediction.add(Entry(0f, energyToTemperature(energy, mass)))
        } else {
            time = temperatureClone.last().x
            prediction.add(temperatureClone.last())
            energy = temperatureToEnergy(prediction.last().y, mass)
        }

        var maxTime = Float.POSITIVE_INFINITY
        var pointAveragerX = 0f
        var pointAveragerY = 0f
        var pointAveragerCount = 0
        val roughEstimatedTime = ((30 * 60) / 0.100f) * mass
        val dt = roughEstimatedTime / 2000f // seconds
        for (i in 0..4000) {
            val T = energyToTemperature(energy, mass)
            energy += heatInput(heater, T, dt)

            //val heatTransferCoefficient = 100f // W/(m^2*K)
            //val heatTransferArea = 10*0.0006f // m^2, Veeery approximate
            //energy -= heatTransferArea * heatTransferCoefficient * (T - roomTemperature) * dt
            // Empirical constants
            energy -= 0.45f * (T - roomTemperature) * dt

            pointAveragerX += time
            pointAveragerY += energyToTemperature(energy, mass)
            pointAveragerCount++
            if (pointAveragerCount == 6) {
                prediction.add(Entry(pointAveragerX / pointAveragerCount, pointAveragerY / pointAveragerCount))
                pointAveragerCount = 0
                pointAveragerX = 0f
                pointAveragerY = 0f
            }

            time += dt

            if (heater.stage == 2 && maxTime.isInfinite()) {
                maxTime = time + 10*60f
            }

            if (time >= maxTime) break
        }

        return prediction
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout.activity_main)

        label = findViewById<TextView>(id.label);
        progress = findViewById<ProgressBar>(id.progressBar);

        startButton = findViewById<Button>(id.start)
        startButton!!.setOnClickListener({ started.value = !started.value })

//        val fastenButton = findViewById<Button>(id.fasten)
//        fastenButton.setOnClickListener({ fasten() })
//
//        val unfastenButton = findViewById<Button>(id.unfasten)
//        unfastenButton.setOnClickListener({ unfasten() })

        val chart = findViewById<LineChart>(R.id.chart)
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

        react({ dirty(); sync() }, started, heat, fan, minTemp, maxTemp, finalTemp, forcedStage)

        react({ previous, current ->
            handler.post {
                if (current == SyncState.NotSyncing) {
                    refreshGraph()
                }
            }
        }, syncTemperature)
        Log.v(TAG, "CREATE")

        (findViewById<Button>(id.heat_off)).setOnClickListener { heat.value = Heat.Off }
        (findViewById<Button>(id.heat_on)).setOnClickListener { heat.value = Heat.On }
        (findViewById<Button>(id.heat_auto)).setOnClickListener { heat.value = Heat.Auto }

        (findViewById<Button>(id.fan_off)).setOnClickListener { fan.value = Heat.Off }
        (findViewById<Button>(id.fan_on)).setOnClickListener { fan.value = Heat.On }
        (findViewById<Button>(id.fan_auto)).setOnClickListener { fan.value = Heat.Auto }

        (findViewById<Button>(id.mode_auto)).setOnClickListener { forcedStage.value = State.Auto }
        (findViewById<Button>(id.mode_heat)).setOnClickListener { forcedStage.value = State.Heat }
        (findViewById<Button>(id.mode_cool)).setOnClickListener { forcedStage.value = State.Cool }
        (findViewById<Button>(id.mode_keep)).setOnClickListener { forcedStage.value = State.Keep }

        // val weight = (findViewById<NumberPicker>(id.weight_picker))

//        val values = arrayOf(50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150, 160, 170, 180, 190, 200, 220, 240, 260, 280, 300, 325, 350, 375, 400, 400, 450, 500, 550, 600, 650, 700, 750, 800, 900, 1000, 1100, 1200, 1300, 1400, 1500, 1600, 1700, 1800, 1900, 2000).map { it.toString() + " g" }.reversed().toTypedArray()
//        weight.minValue = 0
//        weight.maxValue = values.size - 1
//        weight.displayedValues = values
//        weight.wrapSelectorWheel = false

        val slider = (findViewById<SeekBar>(id.slider))
        val sliderLabel = (findViewById<TextView>(id.slider_label))
        slider.max = 1000000
        slider.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val fraction = progress.toFloat() / seekBar.max
                val output = mapSlider(fraction)

                when(sliderMode.value) {
                    SliderMode.Mass -> mass.value = 0.001f * output
                    SliderMode.MinT -> minTemp.value = output
                    SliderMode.MaxT -> maxTemp.value = output
                    SliderMode.FinalT -> finalTemp.value = output
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
            }

        })

        val lowLabel = findViewById<Button>(R.id.low_label)
        val highLabel = findViewById<Button>(R.id.high_label)
        val finalLabel = findViewById<Button>(R.id.final_label)
        val massLabel = findViewById<Button>(R.id.mass_label)

        lowLabel.setOnClickListener { sliderMode.value = SliderMode.MinT }
        highLabel.setOnClickListener { sliderMode.value = SliderMode.MaxT }
        finalLabel.setOnClickListener { sliderMode.value = SliderMode.FinalT }
        massLabel.setOnClickListener { sliderMode.value = SliderMode.Mass }

        react({
            sliderLabel.text = when(sliderMode.value) {
                SliderMode.Mass -> Math.round(mass.value * 1000).toString() + " g"
                SliderMode.MinT -> Math.round(minTemp.value).toString() + " °C"
                SliderMode.MaxT -> Math.round(maxTemp.value).toString() + " °C"
                SliderMode.FinalT -> Math.round(finalTemp.value).toString() + " °C"
            }

            massLabel.text = Math.round(mass.value * 1000).toString() + " g"
            lowLabel.text = Math.round(minTemp.value).toString() + " °C"
            highLabel.text = Math.round(maxTemp.value).toString() + " °C"
            finalLabel.text = Math.round(finalTemp.value).toString() + " °C"

            tryPredictTemperatureAsync()
        }, mass, minTemp, maxTemp, finalTemp, sliderMode, forcedStage)

        react({
            val targetValue = when(sliderMode.value) {
                SliderMode.Mass -> mass.value * 1000
                SliderMode.MinT -> minTemp.value
                SliderMode.MaxT -> maxTemp.value
                SliderMode.FinalT -> finalTemp.value
            }
            slider.progress = Math.round(slider.max * inverseMapSlider(targetValue))
        }, sliderMode)

        var update : Runnable? = null
        update = Runnable {
            tryPredictTemperatureAsync()
            refreshGraph()
            handler.postDelayed(update, 1000)
        }

        react({
            (findViewById<TextView>(current_temperature)).text = String.format("%.1f °C", temperature.lastOrNull()?.y ?: 0)
        }, syncTemperature)

        mass.init()
        sliderMode.init()

        if (!bluetooth.isBluetoothAvailable) {
            Toast.makeText(applicationContext, "Bluetooth is not available", Toast.LENGTH_SHORT).show();
            finish();
        }

        val connect = findViewById<Button>(id.connect)

        bluetooth.setBluetoothConnectionListener(object : BluetoothSPP.BluetoothConnectionListener {
            override fun onDeviceConnected(name: String, address: String) {
                connect.setText("Connected to $name")
            }

            override fun onDeviceDisconnected() {
                connect.setText("Connection lost")
            }

            override fun onDeviceConnectionFailed() {
                connect.setText("Unable to connect")
            }
        })

        bluetooth.setOnDataReceivedListener { data: ByteArray, message: String ->
            Log.v("Bluetooth", message)
            processMessage(message)
        }

        connect.setOnClickListener {
            if (this.bluetooth.serviceState == BluetoothState.STATE_CONNECTED) {
                this.bluetooth.disconnect()
            } else {
                val intent = Intent(applicationContext, DeviceList::class.java)
                startActivityForResult(intent, BluetoothState.REQUEST_CONNECT_DEVICE)
            }
        }

        update.run()
        //handler.postDelayed(update, 1000)
    }

    fun processMessage(message: String) {
        var str = message
        if (str.startsWith("T:")) {
            handler.post {
                str = str.substring(2)
                val splits = str.split(" ")
                val integers = splits.map { s -> s.toInt() }
                val temperatureValuesToRead = integers[0]
                val startIndex = integers[1]
                if (temperatureValuesToRead + 2 != integers.size) {
                    Log.v(TAG, "Expected to read $temperatureValuesToRead integers, but found ${integers.size - 2}");
                    return@post
                }

                if (lastReceivedTemperatureIndex > startIndex + temperatureValuesToRead - 1) {
                    // Machine rebooted?
                    // Keep all values
                } else {
                    val removeAllAfterIndex = temperature.size - 1 - (lastReceivedTemperatureIndex - startIndex)
                    // Wow, so inefficient, but Kotlin is weird
                    temperature.removeAll(temperature.filterIndexed { i, entry -> i >= removeAllAfterIndex })
                }
                val startTime = if (temperature.isNotEmpty()) temperature.last().x else 0f
                for (i in 0 until temperatureValuesToRead) {
                    val compressedTemperature = integers[i+2].toByte()
                    val temp = decompressTemperature(compressedTemperature)
                    temperature.add(Entry(startTime + i.toFloat() * 5, temp))
                }
                Log.v(TAG, "Received ${temperature.size} entries with index " + startIndex)
                lastReceivedTemperatureIndex = startIndex + temperatureValuesToRead - 1
                syncTemperature.value = SyncState.NotSyncing
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!bluetooth.isBluetoothEnabled) {
            bluetooth.enable()
        } else {
            if (!bluetooth.isServiceAvailable) {
                bluetooth.setupService()
                bluetooth.startService(BluetoothState.DEVICE_OTHER);
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetooth.stopService();
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        if (requestCode == BluetoothState.REQUEST_CONNECT_DEVICE) {
            if (resultCode == Activity.RESULT_OK)
                bluetooth.connect(data)
        } else if (requestCode == BluetoothState.REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) {
                bluetooth.setupService()
            } else {
                Toast.makeText(getApplicationContext()
                        , "Bluetooth was not enabled."
                        , Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    var predictingTemperature = false
    val threadPool: ExecutorService = Executors.newCachedThreadPool()

    fun tryPredictTemperatureAsync() {
        if (!predictingTemperature) {
            predictingTemperature = true
            threadPool.execute {
                temperaturePrediction = predictTempering()
                predictingTemperature = false
                handler.post({ refreshGraph() })
            }
        }
    }
    fun sliderMin(): Float {
        return when (sliderMode.value) {
            SliderMode.Mass -> 50f
            SliderMode.MaxT -> 40f
            else -> roomTemperature + 0.1f
        }
    }

    fun sliderMax(): Float {
        return when (sliderMode.value) {
            SliderMode.Mass -> 2000f
            SliderMode.MaxT -> 60f
            else -> 40f
        }
    }

    fun inverseMapSlider(output: Float): Float {
        var mn = 0.0f
        var mx = 1.0f
        for (i in 0..20) {
            val mid = (mn + mx)/2f
            var v = mapSlider(mid)
            if (v < output) {
                mn = mid
            } else if (v > output) {
                mx = mid
            } else {
                break
            }
        }
        mn = (mn + mx) * 0.5f
        System.out.println("In " + output + " out: " + mapSlider(mn))
        return mn
    }

    fun mapSlider(fraction: Float): Float {
        val outputMn = sliderMin().toDouble()
        val outputMx = sliderMax().toDouble()
        val mapping = Math.pow(outputMx/outputMn, fraction.toDouble()) * outputMn
        val rounding = Math.max(0, Math.log10(mapping).toInt() - 1)
        val power10 = Math.pow(10.0, rounding.toDouble())
        return (Math.round(mapping / power10) * power10).toFloat()
    }

    fun refreshGraph() {
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
        dataSet.axisDependency = YAxis.AxisDependency.RIGHT

        val temperaturePrediction = this.temperaturePrediction
        if (temperaturePrediction.size == 0) temperaturePrediction.add(Entry(0f, 0f))
        val temperaturePrediction2 = temperaturePrediction.map { Entry((it.x - minTime)/secondsInMinute, it.y) }.toMutableList()
        val dataSet2 = LineDataSet(temperaturePrediction2, "")
        dataSet2.color = ColorTemplate.rgb("#aeb7ef")
        dataSet2.lineWidth = 1.5f
        dataSet2.valueTextColor = 0xFFFFFF
        dataSet2.setDrawCircles(false)
        dataSet2.axisDependency = YAxis.AxisDependency.RIGHT

        var maximum = Math.max(temp.maxBy { it.y }!!.y, temperaturePrediction2.maxBy { it.y }!!.y)
        maximum = (Math.ceil(maximum / 10.0)*10).toFloat()

        val lineData = LineData(dataSet, dataSet2)
        chart!!.data = lineData
        chart!!.axisRight.axisMaximum = maximum
        //val lineData = LineData(dataSet)
        //chart.data = lineData
        chart!!.notifyDataSetChanged()
        chart!!.invalidate()
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

        if (active.value && Duration(syncTemperature.lastModifiedTime, DateTime.now()).millis > 2000) syncTemperature()

        if ((lastSyncedVersion < dirtyVersion || !hasPerformedGetSync.value) && synced.value != SyncState.Syncing && Duration(dirtyingTime, DateTime.now()).millis > 800) {
            sync()
        }

        // Dirty every 30 seconds to make sure the state of the machine is up to date.
        // The temperature buffer will overflow after around 20 minutes
        if (Duration(dirtyingTime, DateTime.now()).millis > 30000) {
            syncTemperature.value = SyncState.NotSyncing
            dirty()
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

    internal fun sendCommand(command : String) {
        Log.v(TAG, "Sending command: " + command)
        if (bluetooth.isBluetoothAvailable) {
            bluetooth.send("PACKET:" + command.length + ":" + command, false)
        } else {
            Log.v(TAG, "Bluetooth not available")
        }

        /*Thread {
            var socket : Socket? = null
            try {
                Log.v(TAG, "Connecting...")
                socket = Socket("home.arongranberg.com", 6001)
                socket.soTimeout = 6000
                socket.getOutputStream().write(command.toByteArray())
                val result = IOUtils.toString(socket.getInputStream())
                if (result == "OK") {
                    Log.v(TAG, "Received message ACK")
                } else {
                    Log.e(TAG, "Expected 'OK' response, received: '$result'")
                    lastSyncFailed.value = true
                }
            } catch (e : Exception) {
                lastSyncFailed.value = true
                Log.e(TAG, "Failed to send command", e)
            } finally {
                socket?.close()
            }
        }.start()*/
    }

    fun decompressTemperature(t: Byte): Float {
        val maxT = 80f;
        val minT = 20f;
        return t * ((maxT - minT) / 255.0f) + minT
    }

    var lastReceivedTemperatureIndex = 0
    internal fun syncTemperature() {
        syncTemperature.value = SyncState.Syncing
        sendCommand("GET TEMPERATURES")

        /*syncTemperature.value = SyncState.Syncing
        Thread {
            var socket : Socket? = null
            try {
                Log.v(TAG, "Checking temperatures...")
                socket = Socket("home.arongranberg.com", 6001)
                socket.soTimeout = 6000
                val command = "GET TEMPERATURES"
                socket.getOutputStream().write(command.toByteArray())
                val result = ByteBuffer.wrap(IOUtils.toByteArray(socket.getInputStream()))
                result.order(ByteOrder.LITTLE_ENDIAN)
                if (result.capacity() < 4) {
                    throw Exception("Not enough data received when syncing temperature")
                }

                // Deserialize on main thread
                handler.post({
                    val startIndex = result.getInt(0)
                    val temperatureValuesToRead = result.capacity()-4

                    if (lastReceivedTemperatureIndex > startIndex + temperatureValuesToRead - 1) {
                        // Machine rebooted?
                        // Keep all values
                    } else {
                        val removeAllAfterIndex = temperature.size - 1 - (lastReceivedTemperatureIndex - startIndex)
                        // Wow, so inefficient, but Kotlin is weird
                        temperature.removeAll(temperature.filterIndexed { i, entry -> i >= removeAllAfterIndex })
                    }
                    val startTime = if(temperature.isNotEmpty()) temperature.last().x else 0f
                    for (i in 0 until temperatureValuesToRead) {
                        val compressedTemperature = result.get(i+4)
                        val temp = decompressTemperature(compressedTemperature)
                        Log.v(TAG, "Temp: " + temp)
                        temperature.add(Entry(startTime + i.toFloat() * 5, temp))
                    }
                    Log.v(TAG, "Received ${temperature.size} entries with index " + startIndex)
                    lastReceivedTemperatureIndex = startIndex + temperatureValuesToRead - 1
                })
                lastSyncFailed.value = false
            } catch (e : Exception) {
                lastSyncFailed.value = true
                Log.e(TAG, "Failed to check temperatures", e)
            } finally {
                syncTemperature.value = SyncState.NotSyncing
                socket?.close()
            }
        }.start()*/
    }

    fun calculateTemperingState (): Int {
        if (!started.value) {
            return -1
        }

        if (forcedStage.value != State.Auto) {
            return forcedStage.value.ordinal
        }

        val heater = Heater(minTemp.value, maxTemp.value, finalTemp.value)
        for (entry in temperature) {
            heater.update(entry.y, 5f)
        }
        return heater.stage
    }

    internal fun sync(upload : Boolean) {
        synced.value = SyncState.Syncing
        val buffer = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        val temperingState = calculateTemperingState()
        buffer.putFloat(minTemp.value)
        buffer.putFloat(maxTemp.value)
        buffer.putFloat(finalTemp.value)
        buffer.putInt(temperingState)
        buffer.putInt(when(heat.value) {
            Heat.Off -> 0
            Heat.On -> 1
            Heat.Auto -> 2
        })
        buffer.putInt(2) // Auto
        buffer.putInt(2) // Auto
        bluetooth.send("PACKET:" + (buffer.capacity()+2) + ":S=", false)
        bluetooth.send(buffer.array(), false)
        synced.value = SyncState.NotSyncing
        hasPerformedGetSync.value = true

//
//        val version = dirtyVersion
//        hasPerformedGetSync.value = true
//        Thread {
//            var socket : Socket? = null
//            try {
//                Log.v(TAG, "Connecting...")
//                socket = Socket("home.arongranberg.com", 6001)
//                socket.soTimeout = 6000
//                socket.getOutputStream().write(buffer.array())
//                Log.v(TAG, "Reading...")
//                // val output = BufferedReader(InputStreamReader(socket.getInputStream())).lines().collect(Collectors.joining('\n'))
//                val output = IOUtils.toString(socket.getInputStream())
//                Log.v(TAG, "Response: " + output)
//                Log.v(TAG, "Done")
//                lastSyncedVersion = version
//                lastSyncFailed.value = false
//            } catch (e : Exception) {
//                lastSyncFailed.value = true
//                Log.e(TAG, "Failed to sync", e)
//            } finally {
//                socket?.close()
//                synced.value = SyncState.NotSyncing
//            }
//        }.start()
    }
}
