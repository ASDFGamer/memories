package gallery.memories.service

import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import gallery.memories.MainActivity
import gallery.memories.R
import io.github.g00fy2.versioncompare.Version
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@UnstableApi class AccountService(private val mActivity: MainActivity) {
    companion object {
        val TAG = "AccountService"
    }

    var authHeader: String? = null
    var memoriesUrl: String? = null

    private fun toast(message: String) {
        mActivity.runOnUiThread {
            Toast.makeText(mActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    fun login(baseUrl: String, loginFlowUrl: String) {
        // Make POST request to login flow URL
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(loginFlowUrl)
            .header("User-Agent", "Memories")
            .post("".toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        val response = client.newCall(request).execute()

        // Read response body
        val body = response.body?.string()
        response.body?.close()
        if (body == null) {
            toast("Failed to get login flow response")
            return
        }

        // Parse response body as JSON
        val json = JSONObject(body)
        val pollToken: String
        val pollUrl: String
        val loginUrl: String
        try {
            val pollObj = json.getJSONObject("poll")
            pollToken = pollObj.getString("token")
            pollUrl = pollObj.getString("endpoint")
            loginUrl = json.getString("login")

            toast("Opening login page...")

            // Open login page in browser
            mActivity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)))
        } catch (e: Exception) {
            Log.e(TAG, "login: ", e)
            toast("Failed to parse login flow response")
            return
        }

        // Start polling in background
        Thread {
            pollLogin(pollUrl, pollToken, baseUrl)
        }.start()
    }

    private fun pollLogin(pollUrl: String, pollToken: String, baseUrl: String) {
        mActivity.binding.webview.post {
            mActivity.binding.webview.loadUrl("file:///android_asset/waiting.html")
        }

        val client = OkHttpClient()
        val rbody = "token=$pollToken".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
        var pollCount = 0

        while (true) {
            pollCount += 3
            if (pollCount >= 10 * 60) return

            // Sleep for 3s
            Thread.sleep(3000)

            // Poll login flow URL
            val request = Request.Builder()
                .url(pollUrl)
                .post(rbody)
                .build()
            val response = client.newCall(request).execute()
            Log.v(TAG, "pollLogin: Got status code ${response.code}")

            // Check status code
            if (response.code != 200) {
                response.body?.close()
                continue
            }

            // Read response body
            val body = response.body!!.string()
            response.body?.close()
            val json = JSONObject(body)
            val loginName = json.getString("loginName")
            val appPassword = json.getString("appPassword")

            mActivity.runOnUiThread {
                // Save login info (also updates header)
                storeCredentials(baseUrl, loginName, appPassword)
                mActivity.runOnUiThread {
                    mActivity.loadDefaultUrl()
                }
            }

            return;
        }
    }

    fun checkCredentialsAndVersion() {
        memoriesUrl.let { base ->
            val request = Request.Builder()
                .url(base + "api/describe")
                .get()
                .header("Authorization", authHeader ?: "")
                .build()

            val response = OkHttpClient().newCall(request).execute()
            val body = response.body?.string()
            response.body?.close()

            // Check status code
            if (response.code == 401) {
                return loggedOut()
            }

            // Could not connect to memories
            if (response.code == 404) {
                return toast(mActivity.getString(R.string.err_no_ver))
            }

            // Check body
            if (body == null || response.code != 200) {
                toast(mActivity.getString(R.string.err_no_describe))
                return
            }

            val json = JSONObject(body)
            val version = json.getString("version")
            val uid = json.get("uid")

            // Check UID exists
            if (uid.equals(null) && authHeader != null) {
                return loggedOut()
            }

            // Check minimum version
            if (Version(version) < Version(mActivity.getString(R.string.min_server_version))) {
                return toast(mActivity.getString(R.string.err_no_ver))
            }
        }
    }

    fun loggedOut() {
        toast(mActivity.getString(R.string.err_logged_out))
        deleteCredentials()
        mActivity.runOnUiThread {
            mActivity.loadDefaultUrl()
        }
    }

    fun storeCredentials(url: String, user: String, password: String) {
        mActivity.getSharedPreferences("credentials", 0).edit()
            .putString("memoriesUrl", url)
            .putString("user", user)
            .putString("password", password)
            .apply()
        memoriesUrl = url
        setAuthHeader(Pair(user, password))
    }

    fun getCredentials(): Pair<String, String>? {
        val prefs = mActivity.getSharedPreferences("credentials", 0)
        memoriesUrl = prefs.getString("memoriesUrl", null)
        val user = prefs.getString("user", null)
        val password = prefs.getString("password", null)
        if (user == null || password == null) return null
        return Pair(user, password)
    }

    fun deleteCredentials() {
        authHeader = null
        memoriesUrl = null
        mActivity.getSharedPreferences("credentials", 0).edit()
            .remove("memoriesUrl")
            .remove("user")
            .remove("password")
            .apply()
    }

    fun refreshAuthHeader() {
        setAuthHeader(getCredentials())
    }

    private fun setAuthHeader(credentials: Pair<String, String>?) {
        if (credentials != null) {
            val auth = "${credentials.first}:${credentials.second}"
            authHeader = "Basic ${Base64.encodeToString(auth.toByteArray(), Base64.NO_WRAP)}"
            return
        }
        authHeader = null
    }
}