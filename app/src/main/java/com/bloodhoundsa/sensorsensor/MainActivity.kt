package com.bloodhoundsa.sensorsensor

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.support.v7.app.AppCompatActivity
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.processors.PublishProcessor

class MainActivity : AppCompatActivity() {

    companion object {
        val TAG = MainActivity::class.java.simpleName
        //        val MASS = 0.153.toFloat() //kilograms
        val SENSITIVITY_MULTIPLIER = 3
        val PREF_KEY_WEIGHT = "weight"
        val PREF_KEY_SENSITIVITY = "sensitivity"
        val VIBRATION_DURATION = 1000L //millis
    }

    data class ForceEvent(val force: Float = 0f, val timestamp: Long = 0L)

    lateinit var sensorManager: SensorManager
    lateinit var sensor: Sensor
    lateinit var procSensorEvents: PublishProcessor<SensorEvent>
    lateinit var textLabel: TextView
    lateinit var textSeekBar: TextView
    lateinit var btnReset: View
    lateinit var editWeight: EditText
    lateinit var seekBar: android.support.v7.widget.AppCompatSeekBar
    lateinit var disposables: CompositeDisposable
    var sensorEventListener: SensorEventListener? = null
    lateinit var vibrator: Vibrator
    var deviceWeight: Float = 0f
    lateinit var prefs: SharedPreferences

    var sensitivity: Int = 0
        get() = SENSITIVITY_MULTIPLIER * (field + 1) //returns the displayable value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        textLabel = findViewById(R.id.text_label)
        textSeekBar = findViewById(R.id.seekbar_label)
        btnReset = findViewById(R.id.button_reset)
        seekBar = findViewById(R.id.seek_sensitivity)
        editWeight = findViewById(R.id.edit_weight)

        prefs = getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE)

        sensitivity = prefs.getInt(PREF_KEY_SENSITIVITY, 0)
        seekBar.progress = (sensitivity/ SENSITIVITY_MULTIPLIER) - 1
        textSeekBar.text = getString(R.string.seekbar_label, sensitivity)

        deviceWeight = prefs.getFloat(PREF_KEY_WEIGHT, 0.1f)
        editWeight.setText(deviceWeight.toString())


        disposables = CompositeDisposable()
        procSensorEvents = PublishProcessor.create()

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION, false)

        if (sensor == null) {
            Toast.makeText(this, "Linear Accelerometer not available", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Linear Accelerometer installed", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "LinearAccel {reportMode=${sensor.reportingMode},isWakeUp=${sensor.isWakeUpSensor}}")
        }
    }

    override fun onResume() {
        super.onResume()
        if (sensorEventListener == null) {
            sensorEventListener = object : SensorEventListener {
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }

                override fun onSensorChanged(event: SensorEvent?) {
//                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//                    Log.d(TAG, "${event!!.values[0]}m/s2")
                    procSensorEvents.offer(event)
                }
            }

            sensorManager.registerListener(sensorEventListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        attachToSensor()

        btnReset.setOnClickListener { v -> attachToSensor() }

        // Listen for SeekBar changes
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    sensitivity = progress
                    prefs.edit().putInt(PREF_KEY_SENSITIVITY, progress).apply()
                }
                textSeekBar.text = getString(R.string.seekbar_label, sensitivity)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })

        editWeight.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
//                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
//                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (s.isBlank() || s.isEmpty()) {
                    editWeight.error = "Required!"
                } else {

                    val newWeight = s.trim().toString()
                    deviceWeight = if (newWeight.isEmpty()) 0.0f else newWeight.toFloat()
                    prefs.edit().putFloat(PREF_KEY_WEIGHT, deviceWeight).apply()
                }
            }
        })

    }

    private fun attachToSensor() {

        disposables.clear()  //unsubscribe before attaching with new initial value
        textLabel.text = getString(R.string.sensor_value_force, 0f)

        val disposable = procSensorEvents
                .map { t: SensorEvent ->
                    val ax = t.values[0]
                    val ay = t.values[1]
                    val az = t.values[2]
                    val atotal = Math.sqrt(Math.pow(ax.toDouble(), 2.toDouble())
                            + Math.pow(ay.toDouble(), 2.toDouble())
                            + Math.pow(az.toDouble(), 2.toDouble()))
                    val force = atotal.toFloat() * deviceWeight //force(N) == ACCEL (m/s2) * MASS(Kg)
                    ForceEvent(force, t.timestamp)
                }
                .scan({ f1, f2 ->
                    // returns the larger of the two values
                    Log.d(TAG, String.format("{weight=%.3f,sens=%d,f1=%.2f,f2=%.2f,time=%d", deviceWeight, sensitivity, f1.force, f2.force, (f2.timestamp - f1.timestamp)))
//                    if (f1.force > f2.force) {
//                        // decelerating
//                        f1
//                    } else {
//                        //accelerating
//
//                        f2
//                    }
                    if (f1.force - f2.force > sensitivity) {
                        //abrupt
                        Toast.makeText(this, getString(R.string.ouch, f1.force), Toast.LENGTH_SHORT).show()
                        vibrate()
                    }
                    f2
                })
                .subscribe({ forceEvent ->
                    textLabel.text = getString(R.string.sensor_value_force, forceEvent.force)
                })

        disposables.add(disposable)
    }

    override fun onPause() {
        super.onPause()

        if (sensorEventListener != null) {
            sensorManager.unregisterListener(sensorEventListener)
            sensorEventListener = null
        }

        disposables.clear()

    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(VIBRATION_DURATION, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(VIBRATION_DURATION)
        }
    }
}
