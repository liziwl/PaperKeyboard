package edu.wisc.perperkeyboard;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup.LayoutParams;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.TextView.OnEditorActionListener;
import android.widget.ToggleButton;
import edu.wisc.jj.BasicKNN;
import edu.wisc.jj.Item;
import edu.wisc.jj.SPUtil;


public class TestingActivity extends Activity implements RecBufListener{
	/*************constant values***********************/
	private static final String LTAG = "testing activity debug";
	private static final int STROKE_CHUNKSIZE=MainActivity.STROKE_CHUNKSIZE;
	private static final int SAMPLING_RATE= 48000;
	private static final int CHANNEL_COUNT = 2;	
	
	/*************UI ********************************/
	private TextView text;
	private EditText editText;
	private TextView textInputRate;
	private volatile static String charas = "";
	private volatile static List<String> showDetectResult;
	private TextView debugKNN;
	private TextView totalInputText;
	private TextView totalAccuracyText;
	private ToggleButton ShiftButton;
	private ToggleButton CapsButton;
	private TextView recentAccuracyText;
	
	/*************Audio Processing*******************/
	private BasicKNN mKNN;
//	private boolean inStrokeMiddle;
//	private int strokeSamplesLeft;
	private Thread recordingThread;
	private RecBuffer mBuffer ;	
	private AudioBuffer strokeAudioBuffer;
//	private short[] strokeBuffer;

	/************self-correction and online training control**************/
	private int clickTimes = 0;
//	private boolean onlineTraining = true;
	private String previousKey = "";
	private boolean halt = false;
	private boolean clickOnceAndSame = false;
	private int CLASSIFY_K = 1;
	private volatile List<Button> hintButtonList;
	
	/********************Shift and caps*****************************/
	private boolean shift;
	private boolean caps;
	/********************statistics**************************/
	private Statistic stat;	
	
	/********************gyro helper**************************/
	private GyroHelper mGyro;

	/********************dictionary**************************/
	private Dictionary mDict;
	//use WORD_SPLITTER to separate words from words
	//should be " ". right now for training simplicity, used an arbitrary character
	private static final String WORD_SPLITTER = "a";	
	private boolean dictStatus = true;
	
	@SuppressLint("NewApi")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		/************init UI************************/
		setContentView(R.layout.activity_testing);
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
		text = (TextView) findViewById(R.id.text_detectionResult);
		textInputRate = (TextView) findViewById(R.id.text_detection);
		editText = (EditText) findViewById(R.id.inputChar);
		debugKNN = (TextView) findViewById(R.id.text_debugKNN);
		totalInputText = (TextView) findViewById(R.id.text_inputTimes);
		totalAccuracyText = (TextView) findViewById(R.id.text_errorTimes);
		ShiftButton = (ToggleButton) findViewById(R.id.toggle_shift);
		CapsButton = (ToggleButton) findViewById(R.id.toggle_caps);
		recentAccuracyText = (TextView) findViewById(R.id.text_recentAccuracy);
	
		editText.setOnClickListener(new View.OnClickListener() {
		    @Override
		    public void onClick(View v) {
		    	halt = true;
		    }
		});
		editText.setOnEditorActionListener(new OnEditorActionListener() {
		    @Override
		    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
		        boolean handled = false;
		        //halt = true;
		        if (actionId == EditorInfo.IME_ACTION_SEND) {
		    
		        	mKNN.correctWrongDetection( v.getText()
							.toString(),previousKey);
					charas=charas.substring(0,charas.length()-1);
					showDetectResult.remove(showDetectResult.size()-1);
					//update output according to shift and caps
					updateData(v.getText().toString());
		        	halt = false;
		        	stat.addInput(2,v.getText().toString());
		        	updateUI();
		            handled = true;
		        }
		        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
		        imm.toggleSoftInput(0, 0);
		        return handled;
		    }
		});
		editText.clearFocus();
		
		/***********init values******************/
		halt = false;
		//Intent i = getIntent();
		//mKNN = (KNN)i.getSerializableExtra("SampleObject");
		mKNN = MainActivity.mKNN;
		text.setText("Training Size:"+String.valueOf(mKNN.getTrainingSize()));
		debugKNN.setText(mKNN.getChars());
		showDetectResult = new ArrayList<String>();
		dictStatus = false;
		
		/****************Init RecBuffer and thread*****************/
		mBuffer = new RecBuffer();
		recordingThread = new Thread(mBuffer);
		clickTimes = 0;
		Log.d(LTAG, "on create called once for main acitivity");
		this.register(mBuffer);
		recordingThread = new Thread(mBuffer);
		Toast.makeText(getApplicationContext(),
				"Please Wait Until This disappear", Toast.LENGTH_SHORT).show();
		recordingThread.start();
		text.requestFocus();
		stat = new Statistic(System.currentTimeMillis());
		
		/****************audio buffer to store key strokes*****************/
		this.strokeAudioBuffer=new AudioBuffer(SAMPLING_RATE,CHANNEL_COUNT,STROKE_CHUNKSIZE);
		
		/********************gyro helper**************************/				
		this.mGyro=MainActivity.mGyro;
		
		/********************dictionary**************************/
		//use dict_2of12inf in resource/raw folder
		this.mDict=new Dictionary(getApplicationContext(), R.raw.dict_2of12inf);
	}


	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.testing, menu);
		return true;
	}

	/**
	 * use this method to register on RecBuffer to let it know who is listening
	 * 
	 * @param r
	 */

	@Override
	public void register(RecBuffer r) {
		// set receiving thread to be this class
		r.setReceiver(this);
	}

	/**
	 * This method will be called every time the buffer of recording thread is
	 * full
	 * 
	 * @param data
	 *            : audio data recorded. For stereo data, data at odd indexes
	 *            belongs to one channel, data at even indexes belong to another
	 * @throws FileNotFoundException
	 */
	@SuppressLint("NewApi")
	public void onRecBufFull(short[] data) {
		/*********************smoothy the curve*****************************************/		
		SPUtil.smooth(data);		
		
		/**************** put recording to buffer ********************/
		this.strokeAudioBuffer.add(data);
		
		/*********************check whether gyro agrees that there is a key stroke *******************/
		long curTime=System.nanoTime();
		//first case: screen is being touched
		if (Math.abs(curTime-this.mGyro.lastTouchScreenTime) < mGyro.TOUCHSCREEN_TIME_INTERVAL){
			Log.d("onRecBufFull", "screen touch detected nearby");
			return;
		} 
		
		/*****************when gyro feels some shake on the desk,check audio hints******/
		//if there is such an audio data ready for processing
		if (this.strokeAudioBuffer.hasKeyStrokeData()){
			//check whether gyro agrees or not
			// this assumption holds, since we are using 2000 
			// data samples, (40ms) gyro will be updated around 4 times 
			if (Math.abs(curTime-this.mGyro.lastTouchDeskTime) >= mGyro.DESK_TIME_INTERVAL){ 
				Log.d("onRecBufFull", "no desk vibration feeled. not valid audio data. lastTouchDesktime: "+this.mGyro.lastTouchDeskTime + " .current time: "+curTime);
				this.strokeAudioBuffer.clearValidIdx();
				return;
			}
			if (!halt)
				this.runAudioProcessing(this.strokeAudioBuffer.getKeyStrokeAudioForFeature());
		} else {
			//no audio data ready yet
			// 2 cases:
			// 1. detected a stroke, waiting for more audio
			// 2. no stroke detected yet 
			if (this.strokeAudioBuffer.needMoreAudio()){
				//do nothing when there's already a key stroke in place
				//we are just waiting for more data
				return;
			} else {
				int startIdx = KeyStroke.detectStroke_threshold(data);
				if (-1 == startIdx) { // when there is no stroke
					return;
				} else {
					//detect a new key stroke
					//revert the startIdx back, so that we get info before the strong peak
					this.strokeAudioBuffer.setValidIdx(startIdx-200, data.length);
				}
			}
		}
		
//			if (data.length >= strokeSamplesLeft) {
//				System.arraycopy(data, 0, strokeBuffer, STROKE_CHUNKSIZE * 2
//						- 1 - strokeSamplesLeft, strokeSamplesLeft);
//				this.inStrokeMiddle = false;
//				this.strokeSamplesLeft = 0;
//				this.strokeBuffer = Arrays.copyOf(this.strokeBuffer,
//						STROKE_CHUNKSIZE * 2);
//				// get the audio features from this stroke and add it to the
//				// training set, do it in background
//				if(!halt)
//					this.runAudioProcessing();
//			} else { // if the length is smaller than the needed sample left
//				System.arraycopy(data, 0, strokeBuffer, STROKE_CHUNKSIZE * 2
//						- 1 - strokeSamplesLeft, data.length);
//				this.inStrokeMiddle = true;
//				this.strokeSamplesLeft = this.strokeSamplesLeft - data.length;
//			}
	}

	/**
	 * audio processing. extract features from audio. Add features to KNN.
	 */
	public void runAudioProcessing(short[] audioStroke) {
		// separate left and right channel
		short[][] audioStrokeData = KeyStroke.seperateChannels(audioStroke);
		
		
		/******************log audio****************************/
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss");
		Date date = new Date();
		DecimalFormat df = new DecimalFormat("0.0000");
		
		String Name ="/sdcard/PaperKeyboardLog/"+"test"+ "_"+System.currentTimeMillis()/10;		

		this.shortToPcm(audioStroke, Name);
		File mFile_log = new File(Name);
		FileOutputStream outputStream_log = null;
		try {
			// choose to append
			if(!mFile_log.exists()){
				mFile_log.createNewFile();
			}
			outputStream_log = new FileOutputStream(mFile_log, false);
			OutputStreamWriter osw = new OutputStreamWriter(outputStream_log);
			for (int i = 0; i < audioStrokeData[0].length; i++) {
				osw.write(String.valueOf(audioStrokeData[0][i])+"\n");	
			}
			osw.flush();
			osw.close();
			osw = null;
		} catch (FileNotFoundException e) {
			// System.out.println("the directory doesn't exist!");
			//return false;
		} catch (IOException e) {
			// System.out.println("IOException occurs");
			//return false;
		}
		/******************log audio****************************/		
		
		
		
		// get features
		double[] features= SPUtil.getAudioFeatures(audioStrokeData);
		
		
		/******************log features****************************/
		Name = "/sdcard/PaperKeyboardLog/"+"feature_"+ "_"+System.currentTimeMillis()/10;
		mFile_log = new File(Name);
		outputStream_log = null;
		try {
			// choose to append
			if(!mFile_log.exists()){
				mFile_log.createNewFile();
			}
			outputStream_log = new FileOutputStream(mFile_log, false);
			OutputStreamWriter osw = new OutputStreamWriter(outputStream_log);
//			String header = "paper keyboard test result: \n"
//					+ "file created time: " + dateFormat.format(date) + "\n";
//			osw.write(header);
//			osw.write("feature start\n");
			for (int i = 0; i < features.length; i++) {
				osw.write(String.valueOf(features[i]) + "\n");	
			}
//			osw.write("\nfeature stop\n");				
			osw.flush();
			osw.close();
			osw = null;
		} catch (FileNotFoundException e) {
			 System.out.println("the directory doesn't exist!");
//			return false;
		} catch (IOException e) {
			 System.out.println("IOException occurs");
			//return false;
		}
		/******************log features****************************/		

		
		
		
		
		
		/*********get hints from dictionary*****************/
		List<String> hintsFromDict=null;
		if (this.dictStatus){
			String historyLower=charas.toLowerCase();
			Log.d(LTAG,"history lower :" +historyLower);
			int splitterIndex=historyLower.lastIndexOf(WORD_SPLITTER.charAt(0));
			int endIndex=(historyLower.length() >0)? historyLower.length():0;
			Log.d(LTAG,"start index: "+(splitterIndex+1));		
			Log.d(LTAG,"end index: "+endIndex);
			String unfinishedWord="";
			if ((splitterIndex+1) <=endIndex)
				unfinishedWord=historyLower.substring(splitterIndex+1, endIndex);
			Log.d(LTAG,"to dictionary:" +unfinishedWord);		
			hintsFromDict=this.mDict.getPossibleChar(unfinishedWord);
		}
		
		/********** detect using KNN *******/		
		final String detectResult = mKNN.classify(features, this.CLASSIFY_K,hintsFromDict);
		
		this.previousKey =  detectResult;		
		//add unsure sample to staging area
		//TODO 
		//mKNN.addToStage(detectResult, features);
		
		/**********statistic***************/
		stat.addInput(0,detectResult); //we suppose the input is correct		

		
		/**********caps and shift*******/
		//set shift and caps condition
		this.shift = false; //clear shift after use
		if(detectResult.equals("LShift") || detectResult.equals("RShift")){
			this.shift = true;
		}
		if(detectResult.equals("Caps")){
			this.caps = !this.caps;
		}
		
		//get hints from KNN with regarding to the dictionary result
		//argument is the number of hints needed
		final List<String> labels = mKNN.getHints(5,hintsFromDict);
		//always show word_splitter as a hint
		labels.add(WORD_SPLITTER);
		
		/************* update UI ********************/
		//decide which character to show
		this.updateData(detectResult);

		/************************ debug ******************/
//		final String[] closestList=mKNN.getClosestList();
//		String debug="";
//		for (String item: closestList)
//			debug+=item+",";
//		this.updateData(debug);
		/************************ debug ******************/		
		
		this.runOnUiThread(new Runnable() {
			@SuppressLint("NewApi")
			@Override
			public void run() {
				// if(clickTimes < 2){
				/***Update UI********/
				updateUI();
				
				/********hint buttons*************/
				RelativeLayout rl = (RelativeLayout) findViewById(R.id.testActivity_layout);				
				// rm all existing hint buttons on screen
				if (null == hintButtonList) {
					hintButtonList = new LinkedList<Button>();
				} else {
					for (Button mButton : hintButtonList) {
						rl.removeView(mButton);
					}
					hintButtonList.clear();
				}
				// create new hint buttons
				for (int i = 0; i < labels.size(); i++) {
					Button myButton = new Button(getApplicationContext());
					hintButtonList.add(myButton);
					myButton.setText(labels.get(i));
					myButton.setId(100 + i);
					myButton.setOnClickListener(new OnClickListener() {
						@Override
						public void onClick(View v) {
							// race condition, UI is updating KNN, need to make
							// sure the background thread will not change mKNN
		
							mGyro.lastTouchScreenTime=System.nanoTime();														
							//TODO
							//mKNN.correctWrongDetection(((Button) v).getText()
								//	.toString(),detectResult);
							charas=charas.substring(0,charas.length()-1);
							showDetectResult.remove(showDetectResult.size()-1);
							//update output according to shift and caps
							updateData(((Button)v).getText().toString());
							
							/*******if false recognition result is shift or caps**************/
							if(detectResult.equals("LShift") || detectResult.equals("RShift")){
								shift = false;
							}
							if(detectResult.equals("Caps")){
								caps = !caps;
							}
							
							/******if new correction input is shift or caps**********/
							if(((Button)v).getText().toString().equals("LShift")
									|| ((Button)v).getText().toString().equals("RShift"))
								shift = true;
							if(((Button)v).getText().toString().equals("Caps"))
								caps = true;
							
							
							Log.d("after correction: ", mKNN.toString());
							
							stat.addInput(1, ((Button)v).getText().toString());
							//Update UI;
							updateUI();
						}
					});
					RelativeLayout.LayoutParams rp = new RelativeLayout.LayoutParams(
							LayoutParams.WRAP_CONTENT,
							LayoutParams.WRAP_CONTENT);
					rp.addRule(RelativeLayout.BELOW, R.id.text_detectionResult);
					if (0 != i) {
						rp.addRule(RelativeLayout.RIGHT_OF,
								myButton.getId() - 1);
					}
					myButton.setLayoutParams(rp);
					rl.addView(myButton);
				}
			}
		});
	}

	
	
	
	/**
	 * This is just a function that is to make runAudioProcessing function more
	 * clear It decides what to do according to backspace click time
	 * 
	 * @param features
	 *            : features that are extracted by runAudiaoProcessing
	 */
	/*
	private void dealwithBackSpace(double[] features) {
		String newKey;

		if (clickTimes != 1) { // only one way to make click Time > 1, that is
								// user click backSpace continuously
			newKey = mKNN.classify(features, this.CLASSIFY_K);
			mKNN.addTrainingItem(newKey, features);// online training
			charas += newKey;
			Log.d(LTAG, "clockTimes:0, charas: " + charas);
			clickTimes = 0;
		} else {
			newKey = mKNN.classify(features, this.CLASSIFY_K);
			if (newKey != previousKey) // we think this is user's input error
			{
				Item currentItem = new Item(features);
				Item[] closest = mKNN.getClosestList();
				// if distance is greater than a threshold, we choose the next
				// closest
				if (mKNN.findDistance(closest[0], currentItem) > mKNN.DISTTHRE) {
					clickTimes = 0;
					Log.d(LTAG, "clockTime:1 different form previous, charas: "
							+ charas);
				}
				charas += newKey;
			} else { // Newkey equals previous key, it might be our error,
				// if dist(feature, newKey) > threshold, we choose next closest
				// key as output
				// get the nearest 2 nodes
				mKNN.classify(features, this.CLASSIFY_K);
				Item[] closest = mKNN.getClosestList();
				newKey = closest[1].category;
				charas += newKey;
				Log.d(LTAG, "clockTime:1, same as previous, charas: " + charas);
				clickOnceAndSame = true;// pass this value to deal with the
										// condition that user want to click
										// several times of backspace
			}
			// pass previous feature to next stage
			this.previousKey = newKey;
			this.previousFeature = features;
		}
	}
*/

	/***
	 * This fuction is called when user click backspace button on screen It
	 * remove one character from the displayed characters if it is user click
	 * backspace input and then click backspace, we regard this as
	 */
	public void onClickButtonBackSpace(View view) {
		int len = charas.length();
		if(len > 0)
			charas = charas.substring(0, len-1);
		int len1 = showDetectResult.size();
		if(len > 0)
			showDetectResult.remove(len1-1);
		updateUI();
		stat.addInput(3, "backspace");
	}

	/**
	 * finish testing, save logs to file
	 * @param view
	 */
	public void onClickButtonFinish(View view){
		
		stat.doLogs("PaperKeyboard");
		android.os.Process.killProcess(android.os.Process.myPid());
	}
	
	/**
	 * when click button Dict
	 * @param view
	 */
	public void onClickDict(View view){
		this.dictStatus = !this.dictStatus;
		Button button = (Button) findViewById(R.id.button_Dict);
		if(dictStatus)
			button.setText("Use-Dict");
		else
			button.setText("Non-Dict");
			
	}
	
	/***
	 * when ever new input is changed, use this function to decide which data to be add into the string
	 * this function concerns the SHIFT and CAPSLOCK
	 * @param newData
	 */
	public void updateData(String newData){
		String detectResult = newData;
		CapTrans cap = new CapTrans();
		if(this.caps){
			if(!this.shift){
				charas+= cap.transWhenCaps(detectResult);
				showDetectResult.add(cap.transWhenCaps(detectResult));
			}else {
				charas+= detectResult;
				showDetectResult.add(detectResult);
			}
		}else{
			if(this.shift){
				showDetectResult.add(cap.transWhenCaps(detectResult));
				charas+= cap.transWhenCaps(detectResult);
			}
			else{
				charas+=detectResult;
				showDetectResult.add(detectResult);
			}
		}
		
	}
	
	
	public void updateUI(){
		/***Update UI********/
		textInputRate.setText("input rate:" + String.valueOf(stat.inputRate)+ "ch/s") ;
		//text.setText(charas);
		text.setText(showDetectResult.toString());
		totalInputText.setText(String.valueOf(this.stat.totalInputTimes));
		totalAccuracyText.setText(String.valueOf(this.stat.totalAccuracy * 100) + "%");
		if(shift){
			//ShiftButton.animate();
			ShiftButton.setChecked(true);
		}
		else ShiftButton.setChecked(false);
		if(caps){
			CapsButton.setChecked(true);
		}else CapsButton.setChecked(false);
		//totalAccuracyText.setText(String.valueOf(errorInputTimes));
		debugKNN.setText(mKNN.getChars());
		recentAccuracyText.setText(String.valueOf(stat.recentAccuracy*100) + "%");
		
	}
	
	@Override
	  protected void onResume() {
	    super.onResume();
	    if (mGyro != null){
	    	mGyro.register();
	    } else {
	    	Log.d(LTAG, "try to register gyro sensor. but there is no GyroHelper class used");
	    }
	  }

	  @Override
	  protected void onPause() {
	    super.onPause();
	    if (mGyro != null){
	    	mGyro.unregister();
	    } else {
	    	Log.d(LTAG, "try to register gyro sensor. but there is no GyroHelper class used");
	    }
	  }	
	  
	  
	  
	  /********************************** debug ****************************/	  
	// Transfer Int array into Byte array
	// little endian
	// @param: int[] IntData
	public byte[] shortToByte(short[] shortData) {
		byte[] ret = new byte[shortData.length * 2];
		int i = 0;
		for (i = 0; i < shortData.length; i++) {
			ret[i * 2] = (byte)(shortData[i] & 0xff);
			ret[i * 2 + 1] = (byte)((shortData[i] >> 8) & 0xff);
		}
		return ret;
	}

	// Save int array into PCM file
	//
	public void shortToPcm(short[] shortData, String Name) {
		byte[] buffer;
		try {
			buffer = this.shortToByte(shortData);
			FileOutputStream fos = new FileOutputStream(Name + ".pcm");
			fos.write(buffer, 0, buffer.length);
			fos.close();
		} catch (Exception e) {
		}
	}
	  /********************************** debug ****************************/
	  
	  
}
