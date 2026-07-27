package dev.pideck.app.core;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public final class SessionDiagnosticDeviceTest {
    @Test
    public void localSessionMatchesAuthoritativeBridge() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String local = context.getSharedPreferences("pi_deck", Context.MODE_PRIVATE)
                .getString("session_id_v1", null);
        File tokenFile = new File(context.getFilesDir(), "bridge-token");
        String token;
        try (BufferedReader reader = new BufferedReader(new FileReader(tokenFile))) {
            token = reader.readLine();
        }
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:8787/v1/state").openConnection();
        connection.setRequestProperty("X-PiDeck-Token", token);
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(3_000);
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        } finally {
            connection.disconnect();
        }
        JSONObject state = new JSONObject(body.toString()).getJSONObject("state");
        String remote = state.optString("sessionId", null);
        assertEquals("local=" + local + " remote=" + remote, remote, local);
    }
}
