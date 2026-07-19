# Revert to Version 4.5 (Ultimate Stability)

The user wants to revert the app from v6.0 (GATT/BLE-based) to v4.5 (RFCOMM-based "Ultimate Stability").
The Windows client indicates that v4.5 uses RFCOMM for the main communication and BLE manufacturer data to broadcast the RFCOMM port.

## Proposed Changes

### [Component] Android App Core

#### [MODIFY] [BluetoothServer.kt](file:///C:/Users/varad/AndroidStudioProjects/hotspot/app/src/main/java/com/varadan/hotspot/BluetoothServer.kt)
- Revert the core engine to use RFCOMM alongside BLE.
- Implement an RFCOMM `BluetoothServerSocket` listener.
- Implement the "HELO"/"READY" handshake expected by the Windows client.
- Update BLE advertisement to include the RFCOMM port in manufacturer data (ID 0xFFFF).
- Update logs to reflect "v4.5 Engine: STABLE".

#### [MODIFY] [MainActivity.kt](file:///C:/Users/varad/AndroidStudioProjects/hotspot/app/src/main/java/com/varadan/hotspot/MainActivity.kt)
- Update the UI display string from "Final Master v6.0" to "Ultimate Stability v4.5".

## Verification Plan

### Automated Tests
- I will verify the code compiles and the `BluetoothServer` starts without errors.
- I'll check if the logs show the "v4.5 Engine: STABLE" message.

### Manual Verification
- Deploy the app to a device.
- Observe the "Ultimate Stability v4.5" text on the Home screen.
- Verify that starting the server logs "v4.5 Engine: STABLE" and "RFCOMM: Listening on port X".
