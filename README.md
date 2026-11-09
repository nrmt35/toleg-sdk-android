# Toleg SDK for Android

Native Android (Kotlin) SDK for accepting payments via the Toleg app (app-to-app).

- No Compose or AppCompat dependency: works from any `Activity`
- Coroutine (`suspend`) API, plus a callback API for Java
- Languages: Turkmen, Russian, English. Light and dark themes
- Checks that Toleg is installed and at least version 2.15.2, and offers the Play Store update if not

## Install

The SDK builds with [JitPack](https://jitpack.io). Add the repository in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then add the dependency (a release tag or a commit hash):

```kotlin
dependencies {
    implementation("com.github.nrmt35:toleg-sdk-android:1.0.0")
}
```

Or copy the `toleg-sdk` module into your project and use `implementation(project(":toleg-sdk"))`.

Requirements: `minSdk 24`, Java 17.

## Setup

Register a deep link so Toleg can redirect back to your app after payment. Add an
intent filter to the activity that starts the payment (`AndroidManifest.xml`):

```xml
<activity
    android:name=".CheckoutActivity"
    android:exported="true"
    android:launchMode="singleTop">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="yourapp" android:host="payment-result" />
    </intent-filter>
</activity>
```

The SDK's manifest already declares the `<queries>` entry it needs to read the
installed Toleg version on Android 11 and later.

## Usage

```kotlin
import com.belgilabs.toleg.*

class CheckoutActivity : ComponentActivity() {
    private val toleg = TolegSdk()

    private fun pay() = lifecycleScope.launch {
        val request = TolegPaymentRequest(
            merchantId = "{YOUR_MERCHANT_ID}",       // from Toleg
            amount = 12550,                          // in tenne (125.50 TMT)
            returnUrl = "yourapp://payment-result",  // must match the intent filter
            orderId = "ORDER-123",                   // order id from your backend
        )

        try {
            val status = toleg.pay(
                activity = this@CheckoutActivity,
                request = request,
                options = TolegPayOptions(
                    // themeMode = TolegThemeMode.DARK,
                    // locale = Locale("tk"),
                    // showOrderId = false,
                ),
            ) {
                // The user came back from Toleg (deep link or app switch).
                // Check the order status on your backend using the orderId you sent.
                api.getOrderStatus(request.orderId) // -> TolegPaymentStatus
            }
            // status: SUCCESS / FAILED / UNKNOWN, or null if the user closed the sheet
        } catch (e: TolegSdkException) {
            // Toleg link could not be opened
        }
    }

    // Optional: the SDK also detects the return through the activity lifecycle.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        toleg.handleIntent(intent)
    }

    override fun onDestroy() {
        toleg.dispose()
        super.onDestroy()
    }
}
```

### Java

```java
toleg.pay(activity, request, new TolegPayOptions(), new TolegPaymentCallback() {
    @Override public void onReturn(TolegStatusResolver resolver) {
        backend.checkOrder(orderId, status -> resolver.resolve(status));
    }
    @Override public void onFinished(TolegPaymentStatus finalStatus) { }
    @Override public void onError(TolegSdkException error) { }
});
```

## Payment status

Return one of these from `onReturn`:

| Status       | Behavior                                              |
| ------------ | ----------------------------------------------------- |
| `SUCCESS`    | Shows the success animation and closes the sheet      |
| `FAILED`     | Shows the failure animation and closes the sheet      |
| `UNKNOWN`    | Shows an info message; the user taps "Understood"     |
| `UNFINISHED` | Keeps loading; the SDK polls until resolved or timed out |

`onReturn` runs every time the user comes back to the app while a payment is in progress.
While the status is `UNFINISHED`, it also runs every `refreshIntervalMillis` (20–60 s,
default 20 s). After 5 minutes the sheet shows the `UNKNOWN` message. If `onReturn`
throws (for example, no network), the SDK treats it as `UNFINISHED` and tries again.

## Options

| Option                  | Default  | Description                                                |
| ----------------------- | -------- | ---------------------------------------------------------- |
| `themeMode`             | `SYSTEM` | `SYSTEM`, `LIGHT` or `DARK`                                |
| `locale`                | activity | `tk`, `ru` or `en`; other languages fall back to English   |
| `showOrderId`           | `true`   | Show the order ID row                                      |
| `refreshIntervalMillis` | `20000`  | Polling interval while `UNFINISHED` (20 000–60 000)        |
| `checkTolegVersion`     | `true`   | Show the "update Toleg" state when Toleg is missing or too old. Set `false` only for testing |

## Demo app

`demo/` is a sample app. Set `MERCHANT_ID` in
`demo/src/main/kotlin/com/belgilabs/toleg/demo/MainActivity.kt` to your merchant ID.
It simulates the backend status so you can try every state:

```bash
./gradlew :demo:installDebug
```

To simulate Toleg's redirect back to the app:

```bash
adb shell am start -a android.intent.action.VIEW -d "tolegdemo://payment-result"
```
