package de.dgstudios.iptvstream

import app.cash.paparazzi.DeviceConfig

/**
 * Google-Play-Screenshots fürs Handy: dieselben Demo-Ansichten wie im README, aber im
 * Store-Format 1080x1920 (9:16, Play erlaubt höchstens 2:1). Nur Demo-Daten, keine echten Sender.
 * `./gradlew :mobile:recordPaparazziPlayDebug --tests '*StoreScreenshots*'`
 */
class StoreScreenshots : DemoScreenshots(
    DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 1920, softButtons = false),
)
