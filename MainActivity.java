package com.bgb.upscaler;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Base64;
import org.tensorflow.lite.Interpreter;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_CHOOSER = 1201;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final String MODEL_URL =
      "https://huggingface.co/qualcomm/Real-ESRGAN-x4plus/resolve/94d7747a81c477e9d5ca6947d7d0b6bf0b47eaf0/Real-ESRGAN-x4plus.tflite";
    private File modelFile;
    private Interpreter interpreter;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modelFile = new File(getFilesDir(), "Real-ESRGAN-x4plus.tflite");
        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try { startActivityForResult(params.createIntent(), FILE_CHOOSER); }
                catch (Exception e) { fileCallback = null; return false; }
                return true;
            }
        });
        webView.addJavascriptInterface(new BGBBridge(), "BGBUpscaler");
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void js(String callback, String value) {
        String safe = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
        runOnUiThread(() -> webView.evaluateJavascript(
            "if(window['" + callback + "'])window['" + callback + "']('" + safe + "')", null));
    }

    private synchronized boolean validModel() {
        // Validate the downloaded model by opening it with TensorFlow Lite.
        if (!modelFile.exists() || modelFile.length() < 50_000_000L) return false;
        try {
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(2);
            Interpreter probe = new Interpreter(modelFile, options);
            int[] input = probe.getInputTensor(0).shape();
            int[] output = probe.getOutputTensor(0).shape();
            boolean ok = input.length == 4 && output.length == 4 && input[0] == 1 && output[0] == 1;
            probe.close();
            return ok;
        } catch (Exception e) { return false; }
    }

    private synchronized Interpreter getInterpreter() throws Exception {
        if (!validModel()) throw new Exception("Offline AI model ডাউনলোড করুন।");
        if (interpreter == null) {
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
            interpreter = new Interpreter(modelFile, options);
        }
        return interpreter;
    }

    private class BGBBridge {
        @JavascriptInterface public void modelStatus(String callback) {
            js(callback, validModel() ? "Real-ESRGAN AI model প্রস্তুত — এখন Offline AI ব্যবহার করা যাবে।" :
                "Offline AI model এখনো ডাউনলোড হয়নি। প্রথমবার ডাউনলোডে ইন্টারনেট লাগবে।");
        }
        @JavascriptInterface public void downloadModel(String callback) {
            worker.execute(() -> {
                File temp = new File(getFilesDir(), "model.download");
                try {
                    if (validModel()) { js(callback, "মডেল আগে থেকেই ইনস্টল আছে। Offline AI প্রস্তুত।"); return; }
                    HttpURLConnection c = (HttpURLConnection) new URL(MODEL_URL).openConnection();
                    c.setConnectTimeout(20000); c.setReadTimeout(60000); c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", "BGB-Upscaler-Android");
                    c.connect();
                    if (c.getResponseCode() != 200) throw new IOException("Download failed: HTTP " + c.getResponseCode());
                    long total = c.getContentLengthLong(), done = 0;
                    try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
                        byte[] buf = new byte[64 * 1024]; int n; int last = -1;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n); done += n;
                            if (total > 0) {
                                int pct = (int)(done * 100 / total);
                                if (pct / 5 != last / 5) { last = pct; js(callback, "AI model ডাউনলোড হচ্ছে: " + pct + "%"); }
                            }
                        }
                    } finally { c.disconnect(); }
                    if (modelFile.exists()) modelFile.delete();
                    if (!temp.renameTo(modelFile)) throw new IOException("মডেল ফাইল সংরক্ষণ করা যায়নি।");
                    if (!validModel()) { modelFile.delete(); throw new IOException("TFLite runtime মডেলটি খুলতে পারেনি; মডেল ফাইল সমর্থিত নয়।"); }
                    js(callback, "ডাউনলোড সম্পন্ন। Real-ESRGAN Offline AI প্রস্তুত।");
                } catch (Exception e) {
                    temp.delete(); js(callback, "মডেল ডাউনলোড ব্যর্থ: " + e.getMessage());
                }
            });
        }
        @JavascriptInterface public void upscale(String base64, int scale, String callback) {
            worker.execute(() -> {
                try {
                    byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                    Bitmap input = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (input == null) throw new Exception("ছবি খোলা যায়নি।");
                    Bitmap out = runRealEsrgan(input);
                    if (scale == 2) {
                        Bitmap half = Bitmap.createScaledBitmap(out, Math.max(1, out.getWidth()/2), Math.max(1, out.getHeight()/2), true);
                        out.recycle(); out = half;
                    }
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    out.compress(Bitmap.CompressFormat.JPEG, 94, bos);
                    String payload = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                    int ow = out.getWidth(), oh = out.getHeight();
                    out.recycle(); input.recycle();
                    String js = "if(window['" + callback + "'])window['" + callback + "']({data:'" + payload +
                        "',mime:'image/jpeg',width:" + ow + ",height:" + oh + "})";
                    runOnUiThread(() -> webView.evaluateJavascript(js, null));
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? "AI processing failed" : e.getMessage();
                    String escaped = msg.replace("\\\\", "\\\\\\\\").replace("'", "\\\\'").replace("\\n", " ");
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "if(window['" + callback + "'])window['" + callback + "']({error:'" + escaped + "'})", null));
                }
            });
        }
    }

    private Bitmap runRealEsrgan(Bitmap input) throws Exception {
        Interpreter tflite = getInterpreter();
        int[] inShape = tflite.getInputTensor(0).shape();
        int[] outShape = tflite.getOutputTensor(0).shape();
        if (inShape.length != 4 || outShape.length != 4) throw new Exception("Unsupported AI model tensor shape");
        boolean inNchw = inShape[1] == 3;
        boolean outNchw = outShape[1] == 3;
        int tileH = inNchw ? inShape[2] : inShape[1];
        int tileW = inNchw ? inShape[3] : inShape[2];
        int outH = outNchw ? outShape[2] : outShape[1];
        int outW = outNchw ? outShape[3] : outShape[2];
        int outC = outNchw ? outShape[1] : outShape[3];
        if (outC != 3) throw new Exception("Unsupported AI model color format");
        if (!"FLOAT32".equals(tflite.getInputTensor(0).dataType().toString()) ||
            !"FLOAT32".equals(tflite.getOutputTensor(0).dataType().toString()))
            throw new Exception("এই AI মডেলের tensor type এই build-এ সমর্থিত নয়।");
        int factorX = outW / tileW, factorY = outH / tileH;
        int finalW = input.getWidth() * factorX, finalH = input.getHeight() * factorY;
        if ((long)finalW * finalH > 16_000_000L) throw new Exception("আউটপুট অনেক বড়। ছোট ছবি ব্যবহার করুন।");
        Bitmap result = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        float[][][][] in = inNchw ? new float[1][3][tileH][tileW] : new float[1][tileH][tileW][3];
        float[][][][] out = outNchw ? new float[1][3][outH][outW] : new float[1][outH][outW][3];
        for (int top = 0; top < input.getHeight(); top += tileH) {
            for (int left = 0; left < input.getWidth(); left += tileW) {
                for (int y = 0; y < tileH; y++) {
                    for (int x = 0; x < tileW; x++) {
                        int sx = Math.min(input.getWidth()-1, left+x);
                        int sy = Math.min(input.getHeight()-1, top+y);
                        int c = input.getPixel(sx, sy);
                        float r = ((c >> 16) & 255) / 255f;
                        float g = ((c >> 8) & 255) / 255f;
                        float b = (c & 255) / 255f;
                        if (inNchw) { in[0][0][y][x]=r; in[0][1][y][x]=g; in[0][2][y][x]=b; }
                        else { in[0][y][x][0]=r; in[0][y][x][1]=g; in[0][y][x][2]=b; }
                    }
                }
                tflite.run(in, out);
                Bitmap tile = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                int[] outPixels = new int[outW*outH];
                for (int y=0; y<outH; y++) for (int x=0; x<outW; x++) {
                    float r0 = outNchw ? out[0][0][y][x] : out[0][y][x][0];
                    float g0 = outNchw ? out[0][1][y][x] : out[0][y][x][1];
                    float b0 = outNchw ? out[0][2][y][x] : out[0][y][x][2];
                    int r=clamp8(r0*255f), g=clamp8(g0*255f), b=clamp8(b0*255f);
                    outPixels[y*outW+x] = 0xff000000 | (r<<16) | (g<<8) | b;
                }
                tile.setPixels(outPixels,0,outW,0,0,outW,outH);
                canvas.drawBitmap(tile, left*factorX, top*factorY, paint);
                tile.recycle();
            }
        }
        return result;
    }
    private int clamp8(float v) { return Math.max(0, Math.min(255, Math.round(v))); }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER && fileCallback != null) {
            Uri[] result = (resultCode == RESULT_OK && data != null && data.getData() != null) ? new Uri[]{data.getData()} : null;
            fileCallback.onReceiveValue(result); fileCallback = null;
        }
    }
    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        super.onDestroy(); worker.shutdown();
        if (interpreter != null) interpreter.close();
    }
}
