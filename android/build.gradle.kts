import java.net.URL

plugins {
  id("com.android.library")
  id("expo-module-gradle-plugin")
}

group = "com.expo.xray.vpn"
version = System.getenv("EXPO_XRAY_VPN_VERSION") ?: "0.7.10"

android {
  namespace = "com.expo.xray.vpn"
  defaultConfig {
    versionCode = 1
    versionName = project.version.toString()
  }
  lint {
    abortOnError = false
  }
}

// ── libXray AAR ───────────────────────────────────────────────────────────
// The native AAR is resolved with the following priority:
//   1. Maven artifact (GitHub Packages) — when CI env or expoXrayMaven prop
//   2. Local file (libs/libXray.aar)     — local development fallback
// --------------------------------------------------------------------------
val useMaven = System.getenv("CI") == "true" || project.hasProperty("expoXrayMaven")
val libxrayVersion = System.getenv("LIBXRAY_VERSION") ?: "1.260509.0-patch"

dependencies {
  if (useMaven) {
    logger.lifecycle("expo-xray-vpn: using libXray from Maven ($libxrayVersion)")
    add("implementation", "com.expo.xray.vpn:libxray:$libxrayVersion@aar")
  } else {
    val localAar = file("libs/libXray.aar")
    if (localAar.exists()) {
      logger.lifecycle("expo-xray-vpn: using libXray from local file ($localAar)")
      add("implementation", fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    } else {
      logger.warn("expo-xray-vpn: libXray.aar not found locally, trying Maven fallback...")
      add("implementation", "com.expo.xray.vpn:libxray:$libxrayVersion@aar")
    }
  }
}

// ── Geo data download ─────────────────────────────────────────────────────
// Downloads geoip.dat / geosite.dat before the first compile if missing.
// --------------------------------------------------------------------------
val geoAssetDir = file("src/main/assets/xray")

val downloadGeoData = tasks.register("downloadGeoData") {
  description = "Download latest Xray geo data (geoip.dat, geosite.dat)"
  group = "expo-xray-vpn"

  val geoAssets = mapOf(
    "geoip.dat" to "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat",
    "geosite.dat" to "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat",
  )

  outputs.dir(geoAssetDir)

  doLast {
    geoAssetDir.mkdirs()
    geoAssets.forEach { (name, url) ->
      val targetFile = geoAssetDir.resolve(name)
      if (targetFile.exists()) {
        logger.lifecycle("  ✔ $name already exists, skipping.")
        return@forEach
      }

      logger.lifecycle("  ↓ Downloading $name ...")
      val conn = URL(url).openConnection().apply {
        connectTimeout = 30000
        readTimeout = 60000
      }
      conn.getInputStream().use { input ->
        targetFile.outputStream().use { output ->
          input.copyTo(output)
        }
      }
      logger.lifecycle(
        "  ✔ $name downloaded (${String.format("%.1f", targetFile.length() / 1_048_576.0)} MB)"
      )
    }
  }
}

// Wire geo download just before Kotlin compilation and asset merging
tasks.whenTaskAdded {
  if (name.startsWith("compile") && name.endsWith("Kotlin")) {
    dependsOn(downloadGeoData)
  }
  if (name.startsWith("merge") && name.endsWith("Assets")) {
    dependsOn(downloadGeoData)
  }
}
