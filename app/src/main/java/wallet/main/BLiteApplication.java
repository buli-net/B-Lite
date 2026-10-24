package wallet.main;

import android.app.Application;

import org.bitcoinj.utils.AndroidResourceLoader;

/** Initializes app-wide facilities required by the integrated bitcoinj source tree. */
public final class BLiteApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AndroidResourceLoader.initialize(this);
    }
}
