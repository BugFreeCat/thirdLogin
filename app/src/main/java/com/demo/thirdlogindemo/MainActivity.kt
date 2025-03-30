package com.firebase.cn

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.AttributeSet
import android.util.Base64
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.facebook.CallbackManager
import com.facebook.CallbackManager.Factory.create
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.internal.ServerProtocol
import com.facebook.login.DefaultAudience
import com.facebook.login.LoginBehavior
import com.facebook.login.LoginClient
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.facebook.login.LoginTargetApp
import com.firebase.cn.databinding.ActivityMainBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.android.material.snackbar.Snackbar
import com.google.api.client.extensions.android.json.AndroidJsonFactory
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.*

class MainActivity : AppCompatActivity() {

  private val FACEBOOK_SING_IN = LoginClient.getLoginRequestCode()
  private val GOOGLE_SIGN_IN = 1
  private val TAG = "MainActivity"
  private lateinit var binding: ActivityMainBinding

  // 这里的CLIENT_ID是Google提供的，需要在Google Cloud中创建一个项目，然后在项目的Credentials页面中获取CLIENT_ID
  // 要注意的是，在google cloud 中需要分别创建两个项目，一个是安卓应用，一个是web应用，这里的Client_ID指的是web应用的ID
  private val CLIENT_ID = "xxxx-xxxx.apps.googleusercontent.com"

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setSupportActionBar(binding.toolbar)
  }

  override fun onCreateView(
    parent: View?,
    name: String,
    context: Context,
    attrs: AttributeSet
  ): View? {

    var googleLoginBtn: Button? = parent?.findViewById(R.id.google_login_button)
    var facebookLoginBtn: Button? = parent?.findViewById(R.id.facebook_login_button)
    var exitBtn: Button? = parent?.findViewById(R.id.exit_button)

    googleLoginBtn?.setOnClickListener {
      googleSignIn(context)
    }

    facebookLoginBtn?.setOnClickListener {
      facebookLogin()
    }

    exitBtn?.setOnClickListener {
      Log.i(TAG, "exitBtn click")
      googleLogout(context)
    }

    return super.onCreateView(parent, name, context, attrs)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    // Inflate the menu; this adds items to the action bar if it is present.
    menuInflater.inflate(R.menu.menu_main, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    return when (item.itemId) {
      R.id.action_settings -> true
      else -> super.onOptionsItemSelected(item)
    }
  }

  private fun facebookLogin() {
    lifecycleScope.launch {
      var loginManager = getLoginManager()
      Log.i(TAG, "facebook login click")
      loginManager?.logInWithReadPermissions(this@MainActivity, listOf("public_profile"))
    }
  }

  private fun googleSignIn(context: Context) {
    lifecycleScope.launch{
      val credentialManager = CredentialManager.create(context)

      val nonce = generateNonce()
      Log.i(TAG, "googleSignIn nonce: $nonce")

      //  这里考虑先试用 isFilterByAuthorizedAccounts = false 可以提供创建新用户的能力
      val googleIdOption: GetGoogleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(false)
        .setServerClientId(CLIENT_ID)
        .setAutoSelectEnabled(true)
        .setNonce(nonce)
        .build()

      val request: GetCredentialRequest = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()

      Log.i(TAG, "googleSignIn connection success")
      try {
        val result = credentialManager.getCredential(
          context, request,
        )
        Log.i(TAG, "googleSignIn getCredential success")
        withContext(Dispatchers.IO) {
          handleGoogleSignIn(result)
        }
      } catch (e: Exception) {
        handleFailure(e)
      }
    }
  }

  /**
   * 生成一个nonce值
   * @return nonce值
   */
  fun generateNonce(): String {
    // 使用当前时间戳和随机生成的UUID作为请求参数
    val timestamp = System.currentTimeMillis()
    val uuid = UUID.randomUUID().toString().replace("-", "")

    // 合并请求参数作为一个字符串
    val requestParameters = "$timestamp-$uuid"

    // 计算SHA256摘要
    val digest = MessageDigest.getInstance("SHA-256").digest(requestParameters.toByteArray())

    // Base64编码并确保URL安全性和不换行性
    var nonce = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP)

    // 如果最终字符串长度不够16字符，通过附加UUID确保足够长度
    if (nonce.length < 16) {
      nonce += UUID.randomUUID().toString().replace("-", "").substring(0, 16 - nonce.length)
    }

    // 确保字符串长度不超过500字符
    if (nonce.length > 500) {
      nonce = nonce.substring(0, 500)
    }

    return nonce
  }

  private fun handleGoogleSignIn(result: GetCredentialResponse) {
    Log.i(TAG, "handleGoogleSignIn")
    // Handle the successfully returned credential.
    val credential = result.credential

    // 初始化 NetHttpTransport 和 JacksonFactory
    val transport = NetHttpTransport()
    val jsonFactory = AndroidJsonFactory.getDefaultInstance()

    var responseJson: String? = null

    when (credential) {

      // Passkey credential
      is PublicKeyCredential -> {
        // Share responseJson such as a GetCredentialResponse on your server to
        // validate and authenticate
        responseJson = credential.authenticationResponseJson
      }

      // Password credential
      is PasswordCredential -> {
        // Send ID and password to your server to validate and authenticate.
        val username = credential.id
        val password = credential.password
      }

      // GoogleIdToken credential
      is CustomCredential -> {
        Log.i(TAG, "handleGoogleSignIn credential type: ${credential.type}")
        if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
          try {
            // Use googleIdTokenCredential and extract the ID to validate and authenticate on your server.
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)

            val idTokenString = googleIdTokenCredential.idToken

            // You can use the members of googleIdTokenCredential directly for UX purposes,
            // but don't use them to store or control access to user data.
            // For that you first need to validate the token: pass googleIdTokenCredential.getIdToken() to the backend server.
            var verifier = GoogleIdTokenVerifier.Builder(transport, jsonFactory)
              .setAudience(Collections.singletonList(CLIENT_ID))
              .build()
            var idToken = verifier.verify(idTokenString)
            // To get a stable account identifier (e.g. for storing user data), use the subject ID:
            idToken.getPayload().getSubject()
            Log.i(TAG, "Received a valid google id token response: " + idToken)
          } catch (e: GoogleIdTokenParsingException) {
            Log.e(TAG, "Received an invalid google id token response1", e)
          } catch (e: Exception) {
            Log.e(TAG, "Received an invalid google id token response2", e)
          }
        } else {
          // Catch any unrecognized custom credential type here.
          Log.e(TAG, "Unexpected type of credential")
        }
      }

      else -> {
        // Catch any unrecognized credential type here.
        Log.e(TAG, "Unexpected type of credential")
      }
    }
  }

  fun handleFailure(exception: Exception) {
    Log.e(TAG, "handleFailure", exception)
    if (exception is NoCredentialException) {
      val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .build()

      val googleSignInClient: GoogleSignInClient = GoogleSignIn.getClient(this, gso)

      var signInIntent: Intent = googleSignInClient.getSignInIntent()
      this.startActivityForResult(signInIntent, GOOGLE_SIGN_IN)
    }
  }

  fun googleLogout(context: Context) {
    Log.i(TAG, "exit")
    val credentialManager = CredentialManager.create(context)

    lifecycleScope.launch {
      val clearCredentialStateRequest = ClearCredentialStateRequest()
      credentialManager.clearCredentialState(clearCredentialStateRequest)
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    when (requestCode) {
      GOOGLE_SIGN_IN -> {
        val task: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(data)
        handleSignInResult(task)
      }
      FACEBOOK_SING_IN -> {
        getLoginManager()?.onActivityResult(resultCode, data, object : FacebookCallback<LoginResult> {
          override fun onSuccess(result: LoginResult) {
            Log.i(TAG, "facebook login success resultToken: ${result.accessToken.token}")
            //  在这里处理从facebook获得的token，将之进行解析，并将解析的结果传入server
          }

          override fun onCancel() {
            Log.i(TAG, "facebook login cancel")
          }

          override fun onError(error: FacebookException) {
            Log.i(TAG, "facebook login error resultToken: ${error.message}")
            handleFailure(error)
          }
        })
      }
    }
  }

  private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
    Log.i(TAG, "handleSignInResult: login success ")
    // 初始化 NetHttpTransport 和 JacksonFactory
    val transport = NetHttpTransport()
    val jsonFactory = AndroidJsonFactory.getDefaultInstance()

    try {
      val account: GoogleSignInAccount = completedTask.getResult(ApiException::class.java)
      // 此处可以处理登录成功后的逻辑，使用 account 获取所需的凭据
      account?.let{
        var idTokenString = it.getIdToken();
        var authCode = it.getServerAuthCode();
        Log.i(TAG, "Received an id token: " + idTokenString)

        // 根据您的需求使用 idToken 或 authCode
        // 例如，传递 idToken 到您的后台服务器进行验证
        var verifier = GoogleIdTokenVerifier.Builder(transport, jsonFactory)
          .setAudience(Collections.singletonList(CLIENT_ID))
          .build()
        var idToken = verifier?.verify(idTokenString)
        // To get a stable account identifier (e.g. for storing user data), use the subject ID:
        idToken?.getPayload()?.getSubject()
        Log.i(TAG, "Received an verifire id token: " + idToken)
      }
    } catch (e: ApiException) {
      Log.w(TAG, "signInResult:failed code=" + e.getStatusCode())
      // 处理登录失败的逻辑
    }
  }

  private val properties: LoginButtonProperties = LoginButtonProperties()

  fun getLoginManager(): LoginManager {
    val manager = LoginManager.getInstance()
    manager.setDefaultAudience(properties.defaultAudience)
    manager.setLoginBehavior(properties.loginBehavior)
    manager.setLoginTargetApp(properties.loginTargetApp)
    manager.setAuthType(properties.authType)
    manager.setFamilyLogin(properties.isFamilyLogin)
    manager.setShouldSkipAccountDeduplication(properties.shouldSkipAccountDeduplication)
    manager.setMessengerPageId(properties.messengerPageId)
    manager.setResetMessengerState(properties.resetMessengerState)
    return manager
  }

  open class LoginButtonProperties {
    var defaultAudience = DefaultAudience.FRIENDS
    var permissions: List<String> = emptyList()
    var loginBehavior = LoginBehavior.NATIVE_WITH_FALLBACK
    var authType = ServerProtocol.DIALOG_REREQUEST_AUTH_TYPE
    var loginTargetApp = LoginTargetApp.FACEBOOK
    var shouldSkipAccountDeduplication = false
      protected set
    var messengerPageId: String? = null
    var resetMessengerState = false
    var isFamilyLogin = false
  }
}