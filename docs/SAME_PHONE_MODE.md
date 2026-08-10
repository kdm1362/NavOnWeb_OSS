# Same-phone projection mode

Same-phone mode runs the Android Auto projection client and browser server on one Android phone. A tablet, vehicle browser, or desktop browser on the same network displays and controls the session.

```text
Android Auto Developer Head Unit Server on the phone
  -> NavOnWeb on the same phone
  -> local or configured relay connection
  -> external browser
```

NavOnWeb always resolves the Android Auto endpoint to `127.0.0.1:5277`; the user cannot replace it with an arbitrary host or port.

## Starting a session

1. Enable Android Auto developer mode on the phone.
2. Open the Android Auto developer menu and start **Head unit server**.
3. Open NavOnWeb.
4. Select the projection profile and video codec, then start the NavOnWeb service.
5. Open the browser address shown by NavOnWeb from another device on the same local network.
6. Enter the pairing code shown in NavOnWeb when the browser asks for it.

The Head Unit Server is controlled by Android Auto, not by NavOnWeb. If Android Auto stops it, the app shows a waiting or reconnecting state until the server is started again.

## Browser behavior

After pairing, the browser negotiates a WebRTC video session and enables touch when the Android Auto input channel is ready. Fullscreen and orientation changes update the projected content rectangle. Leaving fullscreen returns to the normal browser layout.

The phone stores the local pairing relationship. A paired browser can reconnect without entering a new code while that relationship remains valid. Clearing application data, clearing the browser's site data, or explicitly removing the pairing requires a new code.

Changing the projection profile reconnects the Android Auto viewport while keeping the foreground service and browser server active. Changing only the WebRTC codec recreates the browser media session.

## Background operation

The projection foreground service can continue after the user leaves the NavOnWeb activity. The local preview detaches while the decoder and browser output stay active, then reattaches when the activity returns.

Android and device-vendor power controls still apply. Force-stopping NavOnWeb ends the service and prevents restoration until the app is opened again.

## Audio and microphone

Android Auto media, guidance, and system audio can be sent to the paired browser. Browser microphone access normally requires a secure browser context. On a plain local HTTP address, video and touch may work while microphone permission is unavailable; use a trusted HTTPS deployment when microphone input is required.

## Limits

- The phone must support Android Auto's Developer Head Unit Server.
- The phone performs Android Auto decoding and browser encoding at the same time, so higher resolutions use more power and may throttle under heat.
- Local browser access requires network reachability between the devices. Guest Wi-Fi isolation and some hotspot implementations block peer-to-peer traffic.
- Starting or stopping the Android Auto Head Unit Server remains a user action.
- Browser media support varies by browser and hardware codec implementation.
