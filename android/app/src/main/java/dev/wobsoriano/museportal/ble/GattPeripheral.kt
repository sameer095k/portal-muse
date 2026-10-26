package dev.wobsoriano.museportal.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import dev.wobsoriano.muse.core.BleEvents
import dev.wobsoriano.muse.core.BlePeripheral
import dev.wobsoriano.muse.pairing.Advertisement
import dev.wobsoriano.museportal.ble.gatt.DirectGatt
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * The BLE side of gadget pairing: a GATT server with RX and TX and a connectable advertisement, as
 * an [Advertisement] describes. It goes through [DirectGatt] because the Portal never gives apps
 * GATT the usual way. Everything runs on one handler thread; [events] hears what happened on the
 * link, for the pairing Controller.
 */
@SuppressLint("MissingPermission")
class GattPeripheral(private val context: Context, private val events: BleEvents) : BlePeripheral {
  private val thread = HandlerThread("ble").apply { start() }
  private val handler = Handler(thread.looper)
  private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

  private var gatt: DirectGatt? = null
  private var serverIf = -1
  private var rxHandle = -1
  private var txHandle = -1
  private var cccdHandle = -1
  private var txValue = ByteArray(0)
  private var cccdValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
  private var central: String? = null
  private var mtu = 0
  private var advertising: IBinder? = null
  private var originalName: String? = null
  private val prepared = ByteArrayOutputStream()
  // A newer advertise or unadvertise cancels the steps still waiting from an older one.
  private var session = 0

  private val queue =
      NotifyQueue(
          link = ::notifyNow,
          scheduler = { delay, task -> handler.postDelayed(task, delay) },
      )

  override fun advertise(ad: Advertisement) {
    handler.post { start(ad) }
  }

  override fun unadvertise() {
    handler.post { teardown() }
  }

  override fun notify(packets: List<ByteArray>) {
    handler.post { queue.enqueue(packets) }
  }

  override fun disconnect() {
    handler.post { central?.let { address -> gatt?.serverDisconnect(serverIf, address) } }
  }

  fun close() {
    handler.post {
      teardown()
      thread.quitSafely()
    }
  }

  private fun start(spec: Advertisement) {
    teardown()
    val mySession = ++session
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return fail("pairing needs Android 9")
    val adapter = adapter ?: return fail("no Bluetooth adapter")
    if (!adapter.isEnabled) {
      Log.i(TAG, "enabling Bluetooth")
      adapter.enable()
    }
    awaitThen(mySession, { adapter.isEnabled }, 10_000, "Bluetooth did not turn on") {
      openServer(mySession, spec)
    }
  }

  private fun openServer(mySession: Int, spec: Advertisement) {
    val gatt = DirectGatt.connect(context) ?: return fail("could not reach the Bluetooth GATT service")
    if (mySession != session) return gatt.close()
    this.gatt = gatt
    val rx = UUID.fromString(spec.rx)
    val tx = UUID.fromString(spec.tx)
    val service = BluetoothGattService(UUID.fromString(spec.service), BluetoothGattService.SERVICE_TYPE_PRIMARY)
    service.addCharacteristic(
        BluetoothGattCharacteristic(
            rx,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE))
    service.addCharacteristic(
        BluetoothGattCharacteristic(
                tx,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ)
            .apply {
              addDescriptor(
                  BluetoothGattDescriptor(
                      CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            })

    val events =
        object : DirectGatt.ServerEvents {
          override fun onServerRegistered(status: Int, serverIf: Int) {
            handler.post {
              when {
                mySession != session -> runCatching { gatt.unregisterServer(serverIf) }
                status != BluetoothGatt.GATT_SUCCESS -> fail("could not open the GATT server ($status)")
                else -> {
                  this@GattPeripheral.serverIf = serverIf
                  gatt.addService(serverIf, service)
                }
              }
            }
          }

          override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            handler.post {
              if (mySession != session) return@post
              val rxChar = service?.getCharacteristic(rx)
              val txChar = service?.getCharacteristic(tx)
              if (status != BluetoothGatt.GATT_SUCCESS || rxChar == null || txChar == null) {
                return@post fail("could not add the GATT service ($status)")
              }
              rxHandle = rxChar.instanceId
              txHandle = txChar.instanceId
              // TX's only descriptor gets the handle after TX's value; its own id isn't public API.
              cccdHandle = txHandle + 1
              setNameThenAdvertise(mySession, spec)
            }
          }

          override fun onConnectionState(status: Int, serverIf: Int, connected: Boolean, address: String) {
            handler.post {
              if (mySession != session) return@post
              if (connected) {
                Log.i(TAG, "central connected: $address")
                central = address
                mtu = 0
                events.connected(address)
              } else if (address == central) {
                Log.i(TAG, "central disconnected: $address")
                central = null
                mtu = 0
                cccdValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                queue.clear()
                prepared.reset()
                events.disconnected()
              }
            }
          }

          override fun onMtuChanged(address: String, mtu: Int) {
            handler.post { if (mySession == session) this@GattPeripheral.mtu = mtu }
          }

          override fun onReadRequest(address: String, requestId: Int, offset: Int, handle: Int) {
            handler.post {
              if (mySession != session) return@post
              val value =
                  when (handle) {
                    txHandle -> txValue
                    cccdHandle -> cccdValue
                    else -> ByteArray(0)
                  }
              val slice = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
              respond(address, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            }
          }

          override fun onWriteRequest(
              address: String,
              requestId: Int,
              offset: Int,
              prepared: Boolean,
              responseNeeded: Boolean,
              handle: Int,
              value: ByteArray,
          ) {
            handler.post {
              if (mySession != session) return@post
              val allowed = handle == rxHandle || handle == cccdHandle
              if (responseNeeded) {
                respond(
                    address,
                    requestId,
                    if (allowed) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                    offset,
                    value)
              }
              when (handle) {
                cccdHandle -> cccdValue = value
                rxHandle ->
                    if (prepared) this@GattPeripheral.prepared.write(value)
                    else events.wrote(value, mtu)
              }
            }
          }

          override fun onExecuteWrite(address: String, requestId: Int, execute: Boolean) {
            handler.post {
              if (mySession != session) return@post
              respond(address, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
              if (execute && prepared.size() > 0) events.wrote(prepared.toByteArray(), mtu)
              prepared.reset()
            }
          }

          override fun onNotificationSent(address: String, status: Int) {
            handler.post {
              if (mySession != session) return@post
              if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "notification status $status")
              queue.onSent()
            }
          }
        }
    gatt.registerServer(UUID.randomUUID(), DirectGatt.serverCallback(events))
  }

  // The scan response can only carry the adapter's own name, so the adapter is renamed while
  // pairing and restored afterwards.
  private fun setNameThenAdvertise(mySession: Int, spec: Advertisement) {
    val adapter = adapter ?: return
    if (adapter.name != spec.name) {
      if (originalName == null) originalName = adapter.name
      adapter.name = spec.name
    }
    awaitThen(mySession, { adapter.name == spec.name }, 3_000, null) {
      if (adapter.name != spec.name) Log.w(TAG, "adapter name is still ${adapter.name}")
      startAdvertising(mySession, spec)
    }
  }

  private fun startAdvertising(mySession: Int, spec: Advertisement) {
    val gatt = gatt ?: return
    val parameters =
        AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()
    val data =
        AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid.fromString(spec.service))
            .addManufacturerData(spec.manufacturerId, spec.manufacturerData)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
    val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
    val callback =
        DirectGatt.advertisingCallback(
            object : DirectGatt.AdvertisingEvents {
              override fun onStarted(advertiserId: Int, txPower: Int, status: Int) {
                handler.post {
                  if (mySession != session) return@post
                  if (status == 0) {
                    Log.i(TAG, "advertising as ${spec.name}")
                    events.advertising(true)
                  } else {
                    advertising = null
                    fail("advertising failed: ${advertiseError(status)}")
                  }
                }
              }
            })
    advertising = callback
    gatt.startAdvertisingSet(parameters, data, scanResponse, callback)
  }

  private fun respond(address: String, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
    runCatching { gatt?.sendResponse(serverIf, address, requestId, status, offset, value) }
        .onFailure { Log.w(TAG, "sendResponse failed", it) }
  }

  private fun fail(error: String) {
    Log.w(TAG, error)
    teardown()
    events.advertising(false, error)
  }

  private fun teardown() {
    session++
    queue.clear()
    gatt?.let { gatt ->
      advertising?.let { cb -> runCatching { gatt.stopAdvertisingSet(cb) } }
      central?.let { address -> runCatching { gatt.serverDisconnect(serverIf, address) } }
      if (serverIf >= 0) runCatching { gatt.unregisterServer(serverIf) }
      gatt.close()
    }
    gatt = null
    advertising = null
    central = null
    serverIf = -1
    rxHandle = -1
    txHandle = -1
    cccdHandle = -1
    txValue = ByteArray(0)
    cccdValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
    mtu = 0
    prepared.reset()
    originalName?.let { name -> adapter?.name = name }
    originalName = null
  }

  private fun awaitThen(mySession: Int, ready: () -> Boolean, timeoutMs: Long, timeoutError: String?, then: () -> Unit) {
    val deadline = System.currentTimeMillis() + timeoutMs
    fun check() {
      if (mySession != session) return
      when {
        ready() -> then()
        System.currentTimeMillis() < deadline -> handler.postDelayed(::check, 100)
        timeoutError != null -> fail(timeoutError)
        else -> then()
      }
    }
    check()
  }

  private fun notifyNow(packet: ByteArray): Boolean {
    val gatt = gatt
    val address = central
    if (gatt == null || address == null || txHandle < 0) {
      Log.w(TAG, "notify with no central connected; dropping")
      return false
    }
    txValue = packet
    return runCatching { gatt.sendNotification(serverIf, address, txHandle, false, packet) }
        .onFailure { Log.w(TAG, "notification of a ${packet.size} byte packet failed", it) }
        .isSuccess
  }

  companion object {
    private const val TAG = "MuseBle"
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // AdvertisingSetCallback status codes, which match AdvertiseCallback's.
    fun advertiseError(code: Int) =
        when (code) {
          1 -> "data too large"
          2 -> "too many advertisers"
          3 -> "already started"
          4 -> "internal error"
          5 -> "feature unsupported"
          else -> "error $code"
        }
  }
}
