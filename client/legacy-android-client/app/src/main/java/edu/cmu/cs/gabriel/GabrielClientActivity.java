package edu.cmu.cs.gabriel;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.VideoView;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;

import edu.cmu.cs.gabriel.network.AccStreamingThread;
import edu.cmu.cs.gabriel.network.AudioStreamingThread;
import edu.cmu.cs.gabriel.network.ControlThread;
import edu.cmu.cs.gabriel.network.NetworkProtocol;
import edu.cmu.cs.gabriel.network.ResultReceivingThread;
import edu.cmu.cs.gabriel.network.VideoStreamingThread;
import edu.cmu.cs.gabriel.token.ReceivedPacketInfo;
import edu.cmu.cs.gabriel.token.TokenController;
import edu.cmu.cs.gabriel.util.PingThread;
import edu.cmu.cs.gabriel.util.ResourceMonitoringService;

public class GabrielClientActivity extends Activity implements TextToSpeech.OnInitListener, SensorEventListener{

    private static final String LOG_TAG = "Main";

    // major components for streaming sensor data and receiving information
    private String serverIP = null;
    private String schedulerIP = null;
    private VideoStreamingThread videoStreamingThread = null;
    private AccStreamingThread accStreamingThread = null;
    private AudioStreamingThread audioStreamingThread = null;
    private ResultReceivingThread resultThread = null;
    private ControlThread controlThread = null;
    private TokenController tokenController = null;
    private PingThread pingThread = null;

    private boolean isRunning = false;
    private boolean isFirstExperiment = true;

    private CameraPreview preview = null;
    private Camera mCamera = null;
    public byte[] reusedBuffer = null;

    private SensorManager sensorManager = null;
    private Sensor sensorAcc = null;
    private TextToSpeech tts = null;
    private MediaController mediaController = null;

    private ReceivedPacketInfo receivedPacketInfo = null;

    // views
    private ImageView imgView = null;
    private VideoView videoView = null;
    private TextView textView = null;
    private ImageView imgPreview = null;
    private Dialog dialog = null;
    private EditText textSchedulerAddress = null;
    private EditText textGabrielAddress = null;

    private Button btnRequest = null;
    private Button btnStart =  null;

    // audio
    private AudioRecord audioRecorder = null;
    private Thread audioRecordingThread = null;
    private boolean isAudioRecording = false;
    private int audioBufferSize = -1;

    private int currentState = 0;

    // demo mode
    private boolean isTest = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(LOG_TAG, "++onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED+
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON+
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        imgView = (ImageView) findViewById(R.id.guidance_image);
        videoView = (VideoView) findViewById(R.id.guidance_video);

        dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog);

        dialog.setTitle("Wearable Cognitive Assistance");
        dialog.setCancelable(true);

        textSchedulerAddress = (EditText) dialog.findViewById(R.id.text_scheduler_address);
        textGabrielAddress = (EditText) dialog.findViewById(R.id.text_gabriel_address);

        serverIP = Const.SERVER_IP;
        schedulerIP = Const.SCHEDULER_IP;
    }

    @Override
    protected void onResume() {
        Log.v(LOG_TAG, "++onResume");
        super.onResume();

        // dim the screen
//        WindowManager.LayoutParams lp = getWindow().getAttributes();
//        lp.dimAmount = 1.0f;
//        lp.screenBrightness = 0.01f;
//        getWindow().setAttributes(lp);

        showDialog();
//        initOnce();
//        if (Const.IS_EXPERIMENT) { // experiment mode
//            runExperiments();
//        } else { // demo mode
//            serverIP = Const.SERVER_IP;
//            initPerRun(serverIP, Const.TOKEN_SIZE, null);
//        }
    }

    @Override
    protected void onPause() {
        Log.v(LOG_TAG, "++onPause");
        updateCurrentState();
        this.terminate();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Log.v(LOG_TAG, "++onDestroy");
        super.onDestroy();
    }

    private void showDialog() {
        textSchedulerAddress.setText(schedulerIP);
        textGabrielAddress.setText(serverIP);

        final RadioGroup radioGroup = (RadioGroup) dialog.findViewById(R.id.radio_gabriel_mode);

        btnRequest = (Button) dialog.findViewById(R.id.btn_request_scheduler);
        btnStart = (Button) dialog.findViewById(R.id.btn_start_gabriel);

        final Button btnExit = (Button) dialog.findViewById(R.id.btn_exit_gabriel);

        btnRequest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                schedulerIP = textSchedulerAddress.getText().toString();
                new RequestTask().execute(schedulerIP);
            }
        });

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                serverIP = textGabrielAddress.getText().toString();
                isTest = ((RadioButton) radioGroup.findViewById(radioGroup.getCheckedRadioButtonId())).getText().equals("Test");
//                try {
//                    int status = ((Integer) new StartTask().execute(serverIP).get()).intValue();
//                    if (status != 200) {
//                        AlertDialog alertDialog = new AlertDialog.Builder(GabrielClientActivity.this).create();
//                        alertDialog.setTitle("Alert");
//                        alertDialog.setMessage("Gabriel service is not available.");
//                        alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK",
//                                new DialogInterface.OnClickListener() {
//                                    public void onClick(DialogInterface dialog, int which) {
//                                        dialog.dismiss();
//                                    }
//                                });
//                        alertDialog.show();
//                        return;
//                    }
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                } catch (ExecutionException e) {
//                    e.printStackTrace();
//                }
                initOnce();
                if (Const.IS_EXPERIMENT) {
                    runExperiments();
                 } else {
                    initPerRun(serverIP, Const.TOKEN_SIZE, null);
                    dialog.dismiss();
                }

                populateCurrentState();
            }
        });

        btnExit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.cancel();
                GabrielClientActivity.this.finish();
            }
        });

        dialog.show();
    }

    /**
     * Does initialization for the entire application. Called only once even for multiple experiments.
     */
    private void initOnce() {
        Log.v(LOG_TAG, "++initOnce");

        if (Const.SENSOR_VIDEO) {
            preview = (CameraPreview) findViewById(R.id.camera_preview);
            mCamera = preview.checkCamera();
            preview.start();
            mCamera.setPreviewCallbackWithBuffer(previewCallback);
            reusedBuffer = new byte[1920 * 1080 * 3 / 2]; // 1.5 bytes per pixel
            mCamera.addCallbackBuffer(reusedBuffer);
        }

        textView = (TextView) findViewById(R.id.guidance_text);
        textView.setMovementMethod(new ScrollingMovementMethod());

        imgPreview = (ImageView) findViewById(R.id.image_preview);

        Const.ROOT_DIR.mkdirs();
        Const.EXP_DIR.mkdirs();

        // TextToSpeech.OnInitListener
        if (tts == null) {
            tts = new TextToSpeech(this, this);
        }

        // Media controller
        if (mediaController == null) {
            mediaController = new MediaController(this);
        }

        // IMU sensors
        if (Const.SENSOR_ACC) {
            if (sensorManager == null) {
                sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
                sensorAcc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                sensorManager.registerListener(this, sensorAcc, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }

        // Audio
        if (Const.SENSOR_AUDIO) {
            if (audioRecorder == null) {
                startAudioRecording();
            }
        }

        startResourceMonitoring();

        isRunning = true;
    }

    /**
     * Does initialization before each run (connecting to a specific server).
     * Called once before each experiment.
     */
    private void initPerRun(String serverIP, int tokenSize, File latencyFile) {
        Log.v(LOG_TAG, "++initPerRun");

        if ((pingThread != null) && (pingThread.isAlive())) {
            pingThread.kill();
            pingThread.interrupt();
            pingThread = null;
        }
        if ((videoStreamingThread != null) && (videoStreamingThread.isAlive())) {
            videoStreamingThread.stopStreaming();
            videoStreamingThread = null;
        }
        if ((accStreamingThread != null) && (accStreamingThread.isAlive())) {
            accStreamingThread.stopStreaming();
            accStreamingThread = null;
        }
        if ((audioStreamingThread != null) && (audioStreamingThread.isAlive())) {
            audioStreamingThread.stopStreaming();
            audioStreamingThread = null;
        }
        if ((resultThread != null) && (resultThread.isAlive())) {
            resultThread.close();
            resultThread = null;
        }

        if (Const.IS_EXPERIMENT) {
            if (isFirstExperiment) {
                isFirstExperiment = false;
            } else {
                try {
                    Thread.sleep(20 * 1000);
                } catch (InterruptedException e) {}
//                controlThread.sendControlMsg("ping");
                // wait a while for ping to finish...
                try {
                    Thread.sleep(5 * 1000);
                } catch (InterruptedException e) {}
            }
        }
        if (tokenController != null) {
            tokenController.close();
        }
        if ((controlThread != null) && (controlThread.isAlive())) {
            controlThread.close();
            controlThread = null;
        }

        if (serverIP == null) return;

        if (Const.BACKGROUND_PING) {
	        pingThread = new PingThread(serverIP, Const.PING_INTERVAL);
	        pingThread.start();
        }

        tokenController = new TokenController(tokenSize, latencyFile);

        controlThread = new ControlThread(serverIP, Const.CONTROL_PORT, returnMsgHandler, tokenController);
        controlThread.start();

        if (Const.IS_EXPERIMENT) {
//            controlThread.sendControlMsg("ping");
            // wait a while for ping to finish...
            try {
                Thread.sleep(5 * 1000);
            } catch (InterruptedException e) {}
        }

        resultThread = new ResultReceivingThread(serverIP, Const.RESULT_RECEIVING_PORT, returnMsgHandler);
        resultThread.start();

        if (Const.SENSOR_VIDEO) {
            videoStreamingThread = new VideoStreamingThread(serverIP, Const.VIDEO_STREAM_PORT, returnMsgHandler, tokenController, mCamera, imgPreview, isTest);
            videoStreamingThread.start();
        }

        if (Const.SENSOR_ACC) {
            accStreamingThread = new AccStreamingThread(serverIP, Const.ACC_STREAM_PORT, returnMsgHandler, tokenController);
            accStreamingThread.start();
        }

        if (Const.SENSOR_AUDIO) {
            audioStreamingThread = new AudioStreamingThread(serverIP, Const.AUDIO_STREAM_PORT, returnMsgHandler, tokenController);
            audioStreamingThread.start();
        }
    }

    /**
     * Runs a set of experiments with different server IPs and token numbers.
     * IP list and token sizes are defined in the Const file.
     */
    private void runExperiments() {
        final Timer startTimer = new Timer();
        TimerTask autoStart = new TimerTask() {
            int ipIndex = 0;
            int tokenIndex = 0;
            @Override
            public void run() {
                GabrielClientActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // end condition
                        if ((ipIndex == Const.SERVER_IP_LIST.length) || (tokenIndex == Const.TOKEN_SIZE_LIST.length)) {
                            Log.d(LOG_TAG, "Finish all experiemets");

                            initPerRun(null, 0, null); // just to get another set of ping results

                            startTimer.cancel();
                            terminate();
                            return;
                        }

                        // make a new configuration
                        serverIP = Const.SERVER_IP_LIST[ipIndex];
                        int tokenSize = Const.TOKEN_SIZE_LIST[tokenIndex];
                        File latencyFile = new File (Const.EXP_DIR.getAbsolutePath() + File.separator +
                                "latency-" + serverIP + "-" + tokenSize + ".txt");
                        Log.i(LOG_TAG, "Start new experiment - IP: " + serverIP +"\tToken: " + tokenSize);

                        // run the experiment
                        initPerRun(serverIP, tokenSize, latencyFile);

                        // move to the next experiment
                        tokenIndex++;
                        if (tokenIndex == Const.TOKEN_SIZE_LIST.length){
                            tokenIndex = 0;
                            ipIndex++;
                        }
                    }
                });
            }
        };

        // run 5 minutes for each experiment
        startTimer.schedule(autoStart, 1000, 5*60*1000);
    }

    private PreviewCallback previewCallback = new PreviewCallback() {
        // called whenever a new frame is captured
        public void onPreviewFrame(byte[] frame, Camera mCamera) {
            if (isRunning) {
                Camera.Parameters parameters = mCamera.getParameters();
                if (videoStreamingThread != null){
                    videoStreamingThread.push(frame, parameters);
                }
            }
        }
    };

    /**
     * Notifies token controller that some response is back
     */
    private void notifyToken() {
        Message msg = Message.obtain();
        msg.what = NetworkProtocol.NETWORK_RET_TOKEN;
        receivedPacketInfo.setGuidanceDoneTime(System.currentTimeMillis());
        msg.obj = receivedPacketInfo;
        try {
            tokenController.tokenHandler.sendMessage(msg);
        } catch (NullPointerException e) {
            // might happen because token controller might have been terminated
        }
    }

    private void processServerControl(String msg) {
        try {
            JSONObject msgJSON = new JSONObject(msg);

            // Switching on/off image sensor
            if (msgJSON.has(NetworkProtocol.SERVER_CONTROL_SENSOR_TYPE_IMAGE)) {
                boolean sw = msgJSON.getBoolean(NetworkProtocol.SERVER_CONTROL_SENSOR_TYPE_IMAGE);
                if (sw) { // turning on
                    Const.SENSOR_VIDEO = true;
                    tokenController.reset();
                    if (preview == null) {
                        preview = (CameraPreview) findViewById(R.id.camera_preview);
                        mCamera = preview.checkCamera();
                        preview.start();
                        mCamera.setPreviewCallbackWithBuffer(previewCallback);
                        reusedBuffer = new byte[1920 * 1080 * 3 / 2]; // 1.5 bytes per pixel
                        mCamera.addCallbackBuffer(reusedBuffer);
                    }
                    if (videoStreamingThread == null) {
                        videoStreamingThread = new VideoStreamingThread(serverIP, Const.VIDEO_STREAM_PORT, returnMsgHandler, tokenController, mCamera, imgPreview, isTest);
                        videoStreamingThread.start();
                    }
                } else { // turning off
                    Const.SENSOR_VIDEO = false;
                    if (preview != null) {
                        mCamera.setPreviewCallback(null);
                        preview.close();
                        reusedBuffer = null;
                        preview = null;
                        mCamera = null;
                    }
                    if (videoStreamingThread != null) {
                        videoStreamingThread.stopStreaming();
                        videoStreamingThread = null;
                    }
                }
            }

            // Switching on/off ACC sensor
            if (msgJSON.has(NetworkProtocol.SERVER_CONTROL_SENSOR_TYPE_ACC)) {
                boolean sw = msgJSON.getBoolean(NetworkProtocol.SERVER_CONTROL_SENSOR_TYPE_ACC);
                if (sw) { // turning on
                    Const.SENSOR_ACC = true;
                    if (sensorManager == null) {
                        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
                        sensorAcc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                        sensorManager.registerListener(this, sensorAcc, SensorManager.SENSOR_DELAY_NORMAL);
                    }
                    if (accStreamingThread == null) {
                        accStreamingThread = new AccStreamingThread(serverIP, Const.ACC_STREAM_PORT, returnMsgHandler, tokenController);
                        accStreamingThread.start();
                    }
                } else { // turning off
                    Const.SENSOR_ACC = false;
                    if (sensorManager != null) {
                        sensorManager.unregisterListener(this);
                        sensorManager = null;
                        sensorAcc = null;
                    }
                    if (accStreamingThread != null) {
                        accStreamingThread.stopStreaming();
                        accStreamingThread = null;
                    }
                }
            }
            // Switching on/off audio sensor
            if (msgJSON.has(NetworkProtocol.SERVER_CONTROL_SENSOR_TYPE_AUDIO)) {
                boolean sw = msgJSON.getBoolean(NetworkProtocol.SERVER_CONTROL_SENSOR_TYPE_AUDIO);
                if (sw) { // turning on
                    Const.SENSOR_AUDIO = true;
                    if (audioRecorder == null) {
                        startAudioRecording();
                    }
                    if (audioStreamingThread == null) {
                        audioStreamingThread = new AudioStreamingThread(serverIP, Const.AUDIO_STREAM_PORT, returnMsgHandler, tokenController);
                        audioStreamingThread.start();
                    }
                } else { // turning off
                    Const.SENSOR_AUDIO = false;
                    if (audioRecorder != null) {
                        stopAudioRecording();
                    }
                    if (audioStreamingThread != null) {
                        audioStreamingThread.stopStreaming();
                        audioStreamingThread = null;
                    }
                }
            }

            // Camera configs
            if (preview != null) {
                int targetFps = -1, imgWidth = -1, imgHeight = -1;
                if (msgJSON.has(NetworkProtocol.SERVER_CONTROL_FPS))
                    targetFps = msgJSON.getInt(NetworkProtocol.SERVER_CONTROL_FPS);
                if (msgJSON.has(NetworkProtocol.SERVER_CONTROL_IMG_WIDTH))
                    imgWidth = msgJSON.getInt(NetworkProtocol.SERVER_CONTROL_IMG_WIDTH);
                if (msgJSON.has(NetworkProtocol.SERVER_CONTROL_IMG_HEIGHT))
                    imgHeight = msgJSON.getInt(NetworkProtocol.SERVER_CONTROL_IMG_HEIGHT);
                if (targetFps != -1 || imgWidth != -1)
                    preview.updateCameraConfigurations(targetFps, imgWidth, imgHeight);
            }

        } catch (JSONException e) {
            Log.e(LOG_TAG, msg);
            Log.e(LOG_TAG, "error in processing server control messages");
            return;
        }
    }

    /**
     * Handles messages passed from streaming threads and result receiving threads.
     */
    private Handler returnMsgHandler = new Handler() {
        public void handleMessage(Message msg) {

            if (msg.what == NetworkProtocol.NETWORK_RET_FAILED) {
                //terminate();
            }
            else if (msg.what == NetworkProtocol.NETWORK_RET_MESSAGE) {
                receivedPacketInfo = (ReceivedPacketInfo) msg.obj;
                receivedPacketInfo.setMsgRecvTime(System.currentTimeMillis());

                textView.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(receivedPacketInfo.msgRecvTime)) + " " + receivedPacketInfo.status + "\n");
            }
            else if (msg.what == NetworkProtocol.NETWORK_RET_SPEECH) {
                String ttsMessage = (String) msg.obj;

                if (tts != null){
                    Log.d(LOG_TAG, "tts to be played: " + ttsMessage);
                    // TODO: check if tts is playing something else
                    tts.setSpeechRate(1.0f);
                    String[] splitMSGs = ttsMessage.split("\\.");
                    HashMap<String, String> map = new HashMap<String, String>();
                    map.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "unique");

                    if (splitMSGs.length == 1)
                        tts.speak(splitMSGs[0].toString().trim(), TextToSpeech.QUEUE_FLUSH, map); // the only sentence
                    else {
                        tts.speak(splitMSGs[0].toString().trim(), TextToSpeech.QUEUE_FLUSH, null); // the first sentence
                        for (int i = 1; i < splitMSGs.length - 1; i++) {
                            tts.playSilence(350, TextToSpeech.QUEUE_ADD, null); // add pause for every period
                            tts.speak(splitMSGs[i].toString().trim(),TextToSpeech.QUEUE_ADD, null);
                        }
                        tts.playSilence(350, TextToSpeech.QUEUE_ADD, null);
                        tts.speak(splitMSGs[splitMSGs.length - 1].toString().trim(),TextToSpeech.QUEUE_ADD, map); // the last sentence
                    }
                }
            }
            else if (msg.what == NetworkProtocol.NETWORK_RET_IMAGE || msg.what == NetworkProtocol.NETWORK_RET_ANIMATION) {
                Bitmap feedbackImg = (Bitmap) msg.obj;
                imgView = (ImageView) findViewById(R.id.guidance_image);
                videoView = (VideoView) findViewById(R.id.guidance_video);
                imgView.setVisibility(View.VISIBLE);
                videoView.setVisibility(View.GONE);
                imgView.setImageBitmap(feedbackImg);
            }
            else if (msg.what == NetworkProtocol.NETWORK_RET_VIDEO) {
                String url = (String) msg.obj;
                imgView.setVisibility(View.GONE);
                videoView.setVisibility(View.VISIBLE);
                videoView.setVideoURI(Uri.parse(url));
                videoView.setMediaController(mediaController);
                //Video Loop
                videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    public void onCompletion(MediaPlayer mp) {
                        videoView.start();
                    }
                });
                videoView.start();
            }
            else if (msg.what == NetworkProtocol.NETWORK_RET_DONE) {
                notifyToken();
            }
            else if (msg.what == NetworkProtocol.NETWORK_RET_CONFIG) {
                String controlMsg = (String) msg.obj;
                processServerControl(controlMsg);
            }
        }
    };

    /**
     * Terminates all services.
     */
    private void terminate() {
        Log.v(LOG_TAG, "++terminate");

        isRunning = false;

        if ((pingThread != null) && (pingThread.isAlive())) {
            pingThread.kill();
            pingThread.interrupt();
            pingThread = null;
        }
        if ((resultThread != null) && (resultThread.isAlive())) {
            resultThread.close();
            resultThread = null;
        }
        if ((videoStreamingThread != null) && (videoStreamingThread.isAlive())) {
            videoStreamingThread.stopStreaming();
            videoStreamingThread = null;
        }
        if ((accStreamingThread != null) && (accStreamingThread.isAlive())) {
            accStreamingThread.stopStreaming();
            accStreamingThread = null;
        }
        if ((audioStreamingThread != null) && (audioStreamingThread.isAlive())) {
            audioStreamingThread.stopStreaming();
            audioStreamingThread = null;
        }
        if ((controlThread != null) && (controlThread.isAlive())) {
            controlThread.close();
            controlThread = null;
        }
        if (tokenController != null){
            tokenController.close();
            tokenController = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (preview != null) {
            mCamera.setPreviewCallback(null);
            preview.close();
            reusedBuffer = null;
            preview = null;
            mCamera = null;
        }
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
            sensorManager = null;
            sensorAcc = null;
        }
        if (audioRecorder != null) {
            stopAudioRecording();
        }
        stopResourceMonitoring();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        try {
            new StopTask().execute(serverIP).get();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
        this.finish();
    }

    private class RequestTask extends AsyncTask<String, Integer, String> {
        private ProgressDialog dialog = new ProgressDialog(GabrielClientActivity.this);

        @Override
        protected String doInBackground(String... urls) {
            String content = null;
            try {
                HttpParams httpParameters = new BasicHttpParams();
                HttpConnectionParams.setConnectionTimeout(httpParameters, 5000);
                HttpConnectionParams.setSoTimeout(httpParameters, 5000);
                HttpClient client = new DefaultHttpClient(httpParameters);
                HttpGet get = new HttpGet("http://" + urls[0] + ":23633/request");
                HttpResponse response = client.execute(get);
                BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
                content = reader.readLine();
                Thread.sleep(3000L);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return content;
        }
        @Override
        protected void onPreExecute() {
            dialog.show();
        }
        @Override
        protected void onPostExecute(final String result) {
            dialog.dismiss();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    textGabrielAddress.setText(result);
                }
            });
        }
    }

    private void updateCurrentState() {
        currentState = resultThread.getCurrentState();
        Log.v(LOG_TAG, "Obtaining current state: " + currentState);
    }

    private void populateCurrentState() {
        resultThread.setCurrentState(currentState);
//        controlThread.setCurrentState(currentState);
        videoStreamingThread.setCurrentState(currentState);
//        controlThread.sendControlMsg("sync"); // TODO: process this in the server
        Log.v(LOG_TAG, "Pushing current state: " + currentState);
    }

    private class StartTask extends AsyncTask<String, Integer, Integer> {
        private ProgressDialog dialog = new ProgressDialog(GabrielClientActivity.this);

        @Override
        protected Integer doInBackground(String... urls) {
            int status = 404;
            try {
                HttpParams httpParameters = new BasicHttpParams();
                HttpConnectionParams.setConnectionTimeout(httpParameters, 5000);
                HttpConnectionParams.setSoTimeout(httpParameters, 5000);
                HttpClient client = new DefaultHttpClient(httpParameters);
                HttpGet get = new HttpGet("http://" + urls[0] + ":23633/start");
                HttpResponse response = client.execute(get);
                status = response.getStatusLine().getStatusCode();
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return status;
        }
        @Override
        protected void onPreExecute() {
            dialog.show();
        }
        @Override
        protected void onPostExecute(final Integer result) {
            dialog.dismiss();
        }
    }

    private class StopTask extends AsyncTask<String, Integer, Integer> {
        private ProgressDialog dialog = new ProgressDialog(GabrielClientActivity.this);

        @Override
        protected Integer doInBackground(String... urls) {
            int status = 404;
            try {
                HttpParams httpParameters = new BasicHttpParams();
                HttpConnectionParams.setConnectionTimeout(httpParameters, 5000);
                HttpConnectionParams.setSoTimeout(httpParameters, 5000);
                HttpClient client = new DefaultHttpClient(httpParameters);
                HttpGet get = new HttpGet("http://" + urls[0] + ":23633/stop");
                HttpResponse response = client.execute(get);
                status = response.getStatusLine().getStatusCode();
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return status;
        }
        @Override
        protected void onPreExecute() {
            dialog.show();
        }
        @Override
        protected void onPostExecute(final Integer result) {
            dialog.dismiss();
        }
    }

    /**************** SensorEventListener ***********************/
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        /*
         * Currently only ACC sensor is supported
         */
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER)
            return;
        if (accStreamingThread != null) {
            accStreamingThread.push(event.values);
        }
    }
    /**************** End of SensorEventListener ****************/

    /**************** TextToSpeech.OnInitListener ***************/
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            if (tts == null) {
                tts = new TextToSpeech(this, this);
            }
            int result = tts.setLanguage(Locale.US);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(LOG_TAG, "Language is not available.");
            }
            int listenerResult = tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onDone(String utteranceId) {
                    Log.v(LOG_TAG,"progress on Done " + utteranceId);
//                  notifyToken();
                }
                @Override
                public void onError(String utteranceId) {
                    Log.v(LOG_TAG,"progress on Error " + utteranceId);
                }
                @Override
                public void onStart(String utteranceId) {
                    Log.v(LOG_TAG,"progress on Start " + utteranceId);
                }
            });
            if (listenerResult != TextToSpeech.SUCCESS) {
                Log.e(LOG_TAG, "failed to add utterance progress listener");
            }
        } else {
            // Initialization failed.
            Log.e(LOG_TAG, "Could not initialize TextToSpeech.");
        }
    }
    /**************** End of TextToSpeech.OnInitListener ********/

    /**************** Audio recording ***************************/
    private void startAudioRecording() {
        audioBufferSize = AudioRecord.getMinBufferSize(Const.RECORDER_SAMPLERATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        Log.d(LOG_TAG, "buffer size of audio recording: " + audioBufferSize);
        audioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                Const.RECORDER_SAMPLERATE, Const.RECORDER_CHANNELS,
                Const.RECORDER_AUDIO_ENCODING, audioBufferSize);
        audioRecorder.startRecording();

        isAudioRecording = true;

        audioRecordingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readAudioData();
            }
        }, "AudioRecorder Thread");
        audioRecordingThread.start();
    }

    private void readAudioData() {
        byte data[] = new byte[audioBufferSize];

        while (isAudioRecording) {
            int n = audioRecorder.read(data, 0, audioBufferSize);

            if (n != AudioRecord.ERROR_INVALID_OPERATION && n > 0) {
                if (audioStreamingThread != null) {
                    audioStreamingThread.push(data);
                }
            }
        }
    }

    private void stopAudioRecording() {
        isAudioRecording = false;
        if (audioRecorder != null) {
            if (audioRecorder.getState() == AudioRecord.STATE_INITIALIZED)
                audioRecorder.stop();
            audioRecorder.release();
            audioRecorder = null;
            audioRecordingThread = null;
        }
    }
    /**************** End of audio recording ********************/

    /**************** Battery recording *************************/
    /*
	 * Resource monitoring of the mobile device
     * Checks battery and CPU usage, as well as device temperature
	 */
    Intent resourceMonitoringIntent = null;

    public void startResourceMonitoring() {
        Log.i(LOG_TAG, "Starting Battery Recording Service");
        resourceMonitoringIntent = new Intent(this, ResourceMonitoringService.class);
        startService(resourceMonitoringIntent);
    }

    public void stopResourceMonitoring() {
        Log.i(LOG_TAG, "Stopping Battery Recording Service");
        if (resourceMonitoringIntent != null) {
            stopService(resourceMonitoringIntent);
            resourceMonitoringIntent = null;
        }
    }
    /**************** End of battery recording ******************/
}
