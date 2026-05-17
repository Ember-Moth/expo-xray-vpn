package com.starlink.vpn

import android.util.Base64
import org.json.JSONObject

object LibXrayResponse {
  fun encodeText(text: String): String {
    return Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
  }

  fun requireSuccess(encodedResponse: String): JSONObject {
    val json = String(Base64.decode(encodedResponse, Base64.DEFAULT), Charsets.UTF_8)
    val response = JSONObject(json)

    if (!response.optBoolean("success")) {
      throw IllegalStateException(response.optString("error", "libXray call failed."))
    }

    return response
  }

  fun requireDataJsonString(response: JSONObject): String {
    val data = response.opt("data")
    if (data == null || data == JSONObject.NULL) {
      throw IllegalStateException("libXray response data is empty.")
    }

    return data.toString()
  }
}
