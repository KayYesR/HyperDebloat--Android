package io.github.kayyesr.hyperdebloat

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.conscrypt.Conscrypt
import java.security.Security

class HyperDebloatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // TLS 1.3 for wireless debugging comes from the bundled Conscrypt; BouncyCastle signs the ADB certificate.
        if (Security.getProvider("Conscrypt") == null) Security.insertProviderAt(Conscrypt.newProvider(), 1)
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) Security.addProvider(BouncyCastleProvider())
    }
}
