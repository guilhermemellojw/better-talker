package com.bettertalker.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.bettertalker.app.plugin.PublicationBridgePlugin;

public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerPlugin(PublicationBridgePlugin.class);
    }
}
