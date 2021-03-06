package com.example.jrme.face3dv3;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.jrme.face3dv3.filters.convolution.SobelFilterGx;
import com.example.jrme.face3dv3.filters.convolution.SobelFilterGy;
import com.example.jrme.face3dv3.fitting.CostFunction;
import com.example.jrme.face3dv3.util.IOHelper;
import com.example.jrme.face3dv3.util.MyProcrustes;
import com.example.jrme.face3dv3.util.Pixel;
import com.facepp.error.FaceppParseException;
import com.facepp.http.HttpRequests;
import com.facepp.http.PostParameters;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;
import org.json.JSONException;
import org.json.JSONObject;
import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import static com.example.jrme.face3dv3.Constants.BYTES_PER_FLOAT;
import static com.example.jrme.face3dv3.util.IOHelper.fileSize;
import static com.example.jrme.face3dv3.util.IOHelper.readBin2DShapetoMatrix;
import static com.example.jrme.face3dv3.util.IOHelper.readBin83PtIndex;
import static com.example.jrme.face3dv3.util.IOHelper.readBinFloat;
import static com.example.jrme.face3dv3.util.IOHelper.readBinFloatDoubleArray;
import static com.example.jrme.face3dv3.util.IOHelper.writeBinFloatScale;

import static com.example.jrme.face3dv3.util.IOHelper.readBinModel83Pt2DFloat;
import static com.example.jrme.face3dv3.util.ImageHelper.fillHole1;
import static com.example.jrme.face3dv3.util.ImageHelper.fillHole2;
import static com.example.jrme.face3dv3.util.ImageHelper.saveBitmaptoPNG;
import static com.example.jrme.face3dv3.util.MatrixHelper.centroid;
import static com.example.jrme.face3dv3.util.MatrixHelper.maxMinXYZ;
import static com.example.jrme.face3dv3.util.MatrixHelper.translate;
import static com.example.jrme.face3dv3.util.PixelUtil.getMaxMin;
import static java.lang.Math.abs;

/**
 * Get a picture form your phone<br />
 * Use the facepp api to detect face<br />
 * Find face on the picture, and mark its feature points.
 * Apply the Procruste Analysis method to match input model to the face
 * Extract the texture of the face
 * Show the face !
 * @author Jerome & Xiaoping
 */

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    final private int PICTURE_CHOOSE = 1;
    private static final int REQUEST_IMAGE_CAPTURE = 2;

    private static final int DETECT_OK = 11;
    private static final int EXTRACT_OK = 12;
    private static final int SAVE_OK = 13;
    private static final int ERROR = 14;
    private ProgressDialog mProgressDialog;
    private String mCurrentPhotoPath;
    private static final String PICTURE_PATH = "picture path";

    private ImageView imageView = null;
    private Bitmap img = null;
    private Bitmap imgBeforeDetect = null;
    private Button buttonDetect = null;
    private Button buttonExtract = null;
    private Button buttonSave = null;
    private ImageButton buttonFace = null;
    private float imageWidth;
    private float imageHeight;

    RealMatrix xVicMatrix = new Array2DRowRealMatrix(83, 2);
    RealMatrix xBedMatrix = new Array2DRowRealMatrix(83, 2);
    RealMatrix xResult = new Array2DRowRealMatrix(83, 2);
    RealMatrix xAverage83FtPt = new Array2DRowRealMatrix(83, 2);
    RealMatrix xModel83FtPt = new Array2DRowRealMatrix(83, 2);

    private float[][] initialPoints = new float[83][2];
    float rollAngle = 0.0f;

    List<Pixel> facePixels, averagePixels, modelPixels;

    private static final String CONFIG_DIRECTORY = "3DFace/simplification_bin/config";
    private static final String MODEL_2D_83PT_FILE = "ModelPoints2D.dat";

    private static final String TEXTURE_DIRECTORY ="3DFace/simplification_bin/Texture";
    private static final String SHAPE_DIRECTORY ="3DFace/simplification_bin/Shape";

    private static final String AVERAGE_TEXTURE_FILE = "texture.dat";
    private static final String AVERAGE_SHAPE_FILE = "shape.dat";
    private static final String AVERAGE_SHAPE_2D_FILE = "shape2D.dat";
    private static final String FEATURE_S_FILE = "featureshape.dat"; //BIG
    private static final String FEATURE_T_FILE = "featuretexture.dat"; //BIG BIG
    private static final String MODEL_SHAPE_FILE = "modelshape.dat";
    private static final String INDEX83PT_FILE = "modelpoint_index.dat";
    private static final int NUM_CASES_POINTS = fileSize(TEXTURE_DIRECTORY, AVERAGE_TEXTURE_FILE)/BYTES_PER_FLOAT;

    private float[] modelShape;
    private float[] modelTexture;

    // The OpenCV loader callback.
    private BaseLoaderCallback mLoaderCallback =
            new BaseLoaderCallback(this) {
                @Override
                public void onManagerConnected(final int status) {
                    switch (status) {
                        case LoaderCallbackInterface.SUCCESS:
                            Log.d(TAG, "OpenCV loaded successfully");
                            break;
                        default:
                            super.onManagerConnected(status);
                            break;
                    }
                }
            };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        setContentView(R.layout.activity_main);

        // CHeck Heap Size
        ActivityManager am = ((ActivityManager)getSystemService(Activity.ACTIVITY_SERVICE));
        int largeMemory = am.getLargeMemoryClass();
        Log.d(TAG,"heap size = "+largeMemory);

        // Load Model Points
        initialPoints = readBinModel83Pt2DFloat(CONFIG_DIRECTORY,MODEL_2D_83PT_FILE);

        buttonDetect = (Button) this.findViewById(R.id.button);
        buttonDetect.setEnabled(false);
        buttonDetect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View arg0) {
                // show the dialog
                mProgressDialog = ProgressDialog.show(MainActivity.this, "Please wait",
                        "Processing of Face Points Detection ...");
                processDectect();
            }
        });


        buttonExtract = (Button) this.findViewById(R.id.button2);
        buttonExtract.setEnabled(false);
        buttonExtract.setOnClickListener(new View.OnClickListener() {
            public void onClick(View arg0) {
                mProgressDialog = ProgressDialog.show(MainActivity.this, "Please wait",
                        "Processing of Face Extraction ...");
                processExtract();
            }
        });

        buttonSave = (Button) this.findViewById(R.id.button3);
        buttonSave.setEnabled(false);
        buttonSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View arg0) {

                mProgressDialog = ProgressDialog.show(MainActivity.this, "Please wait",
                        "Saving Texture ...");
                processSave();
            }
        });

        buttonFace = (ImageButton) this.findViewById(R.id.imageButton);
        buttonFace.setOnClickListener(new View.OnClickListener() {
            public void onClick(View arg0) {
                //start openGl activity
                final Intent intent = new Intent(MainActivity.this, OpenGLActivity.class);
                startActivity(intent);
            }
        });

        imageView = (ImageView) this.findViewById(R.id.imageView);
        imageView.setImageBitmap(img);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.camera:
                buttonDetect.setEnabled(false);
                buttonExtract.setEnabled(false);
                buttonSave.setEnabled(false);
                Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if(takePictureIntent.resolveActivity(getPackageManager()) != null){
                    // Create the File of the Photo
                    File photoFile = null;
                    try {
                        photoFile = createImageFile();
                        mCurrentPhotoPath = photoFile.getAbsolutePath();
                        Log.d(TAG,"image "+mCurrentPhotoPath);
                    } catch (IOException ex) {
                        // Error occurred while creating the File
                        ex.printStackTrace();
                        photoFile = null;
                        mCurrentPhotoPath = null;
                    }
                    // Continue only if the File was successfully created
                    if (photoFile != null) {
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT,
                                Uri.fromFile(photoFile));
                        Log.d(TAG, "uri " + Uri.fromFile(photoFile));
                        MainActivity.this.startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
                        return true;
                    } else return false;
                }else return false;
            case R.id.gallery:
                buttonDetect.setEnabled(false);
                buttonExtract.setEnabled(false);
                buttonSave.setEnabled(false);
                // Get a picture from gallery
                Intent photoPickerIntent = new Intent(Intent.ACTION_PICK);
                photoPickerIntent.setType("image/*");
                startActivityForResult(photoPickerIntent, PICTURE_CHOOSE);
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        //the image picker callback
        if (requestCode == PICTURE_CHOOSE) {
            if (intent != null) {
                //The Android api ~~~
                //Log.d(TAG, "idButSelPic Photopicker: " + intent.getDataString());
                Cursor cursor = getContentResolver().query(intent.getData(), null, null, null, null);
                cursor.moveToFirst();
                int idx = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
                String fileSrc = cursor.getString(idx);
                Log.d(TAG, "Picture : " + fileSrc);

                //just read size
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                img = BitmapFactory.decodeFile(fileSrc, options);
                //scale size to read
                options.inSampleSize = Math.max(1, (int) Math.ceil(Math.max((double) options.outWidth /
                        1024f, (double) options.outHeight / 1024f)));
                options.inJustDecodeBounds = false;
                img = BitmapFactory.decodeFile(fileSrc, options);
                // detect the correct rotation
                ExifInterface exif = null;
                try {
                    exif = new ExifInterface(fileSrc);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                String orientString = null;
                if (exif != null) {
                    orientString = exif.getAttribute(ExifInterface.TAG_ORIENTATION);
                }
                int orientation = orientString != null ? Integer.parseInt(orientString) :  ExifInterface.ORIENTATION_NORMAL;

                int rotationAngle = 0;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotationAngle = 90;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotationAngle = 180;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotationAngle = 270;

                // Rotate Image
                Matrix matrix = new Matrix();
                matrix.setRotate(rotationAngle, (float) img.getWidth() / 2, (float) img.getHeight() / 2);
                img = Bitmap.createBitmap(img, 0, 0, options.outWidth, options.outHeight, matrix, true);

                Log.d(TAG, "onActivityResult, img = " + img);
                imageView.setImageBitmap(img);
                buttonDetect.setEnabled(true);
                imgBeforeDetect = img;

                // Build the Victim Matrix when image change
                for (int i = 0; i < 83; ++i) {
                    for (int j = 0; j < 2; ++j) {
                        xVicMatrix.setEntry(i, j, initialPoints[i][j]);
                    }
                }
                Log.d(TAG, "get Victim");
                Log.d(TAG, "image width = " + img.getWidth());
                Log.d(TAG, "image height = " + img.getHeight());
            } else {
                Log.d(TAG, "idButSelPic Photopicker canceled");
            }
        }
        // if results comes from the camera
        else if (requestCode == REQUEST_IMAGE_CAPTURE) {

            // if a picture was taken
            if (resultCode == Activity.RESULT_OK) {

                // Free the data of the last picture
                if (img != null) {
                    img.recycle();
                }
                if (imgBeforeDetect != null) {
                    imgBeforeDetect.recycle();
                }

                String fileSrc = mCurrentPhotoPath;
                Log.d(TAG, "Picture:" + fileSrc);

                //just read size
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                img = BitmapFactory.decodeFile(fileSrc, options);
                //scale size to read
                options.inSampleSize = Math.max(1, (int) Math.ceil(Math.max((double) options.outWidth /
                        1024f, (double) options.outHeight / 1024f)));
                options.inJustDecodeBounds = false;
                img = BitmapFactory.decodeFile(fileSrc, options);
                // detect the correct rotation
                ExifInterface exif = null;
                try {
                    exif = new ExifInterface(fileSrc);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                String orientString = null;
                if (exif != null) {
                    orientString = exif.getAttribute(ExifInterface.TAG_ORIENTATION);
                }
                int orientation = orientString != null ? Integer.parseInt(orientString) :  ExifInterface.ORIENTATION_NORMAL;

                int rotationAngle = 0;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotationAngle = 90;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotationAngle = 180;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotationAngle = 270;
                // rotate image
                Matrix matrix = new Matrix();
                matrix.setRotate(rotationAngle, (float) img.getWidth() / 2, (float) img.getHeight() / 2);
                img = Bitmap.createBitmap(img, 0, 0, options.outWidth, options.outHeight, matrix, true);

                Log.d(TAG, "onActivityResult, img = " + img);
                imageView.setImageBitmap(img);

                galleryAddPic();
                mCurrentPhotoPath = null;
                buttonDetect.setEnabled(true);
                imgBeforeDetect = img;
                //build the victim matrix when image change
                for (int i = 0; i < 83; ++i) {
                    for (int j = 0; j < 2; ++j) {
                        xVicMatrix.setEntry(i, j, initialPoints[i][j]);
                    }
                }
                Log.d(TAG, "get Victim");
                Log.d(TAG, "image width = " + img.getWidth());
                Log.d(TAG, "image height = " + img.getHeight());

                // if user canceled from the camera activity
            } else if (resultCode == Activity.RESULT_CANCELED) {
                if(mCurrentPhotoPath != null){
                    File imageFile = new File(mCurrentPhotoPath);
                    imageFile.delete();
                }
            }
        }
    }

    private class FaceppDetect {
        DetectCallback callback = null;

        public void setDetectCallback(DetectCallback detectCallback) {
            callback = detectCallback;
        }

        public void detect(final Bitmap image) {

            new Thread(new Runnable() {

                public void run() {
                    Message msg = null;

                    HttpRequests httpRequests = new HttpRequests("16fcca64b37202c094f53672dc688e38",
                            "ssIfcRfiMqU7jRuw1Yrw3VIlaXMclrj8", true, false);

                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    float scale = Math.min(1, Math.min(600f / img.getWidth(), 600f / img.getHeight()));
                    Matrix matrix = new Matrix();
                    matrix.postScale(scale, scale);

                    Bitmap imgSmall = Bitmap.createBitmap(img, 0, 0, img.getWidth(), img.getHeight(), matrix, false);
                    //Log.v(TAG, "imgSmall size : " + imgSmall.getWidth() + " " + imgSmall.getHeight());

                    imgSmall.compress(Bitmap.CompressFormat.JPEG, 100, stream);
                    byte[] array = stream.toByteArray();
                    String faceId = null;

                    try {
                        //detect face
                        JSONObject result = httpRequests.detectionDetect(new PostParameters().setImg(array).setAttribute("glass,pose"));
                        System.out.println(result);
                        faceId = result.getJSONArray("face").getJSONObject(0).getString("face_id");
                        rollAngle = (float) result.getJSONArray("face").getJSONObject(0).getJSONObject("attribute")
                                .getJSONObject("pose").getJSONObject("roll_angle").getDouble("value");
                        imageWidth = (float) result.getJSONArray("face").getJSONObject(0).getJSONObject("position").getDouble("width");
                        imageHeight = (float) result.getJSONArray("face").getJSONObject(0).getJSONObject("position").getDouble("height");
                        Log.d("faceId", faceId);
                        Log.d("roll_angle", "" + rollAngle);
                        JSONObject result2 = httpRequests.detectionLandmark(new PostParameters().setFaceId(faceId));
                        Log.d(TAG, result2.toString());

                        //finished , then call the callback function
                        if (callback != null) {
                            msg = mHandler.obtainMessage(DETECT_OK);
                            callback.detectResult(result2); //landmark as the result (1 face only)
                        }
                    } catch (FaceppParseException | JSONException e) {
                        e.printStackTrace();
                        msg = mHandler.obtainMessage(ERROR);
                    } finally{
                        // send the message
                        if (msg != null) {
                            mHandler.sendMessage(msg);
                        }
                    }
                }
            }).start();
        }
    }

    interface DetectCallback {
        void detectResult(JSONObject rst);
    }

    /*
     * Process of Face Detection and obtain Features Points
     */
    private void processDectect() {
        FaceppDetect faceppDetect = new FaceppDetect();
        faceppDetect.setDetectCallback(new DetectCallback() {

            public void detectResult(JSONObject rst) {

                Message msg = null;

                // Use Red Paint
                Paint paint = new Paint();
                paint.setColor(Color.RED);
                paint.setStrokeWidth(Math.max(img.getWidth(), img.getHeight()) / 100f);

                //create a new canvas
                Bitmap bitmap = Bitmap.createBitmap(img.getWidth(), img.getHeight(), img.getConfig());
                Canvas canvas = new Canvas(bitmap);
                canvas.drawBitmap(img, new Matrix(), null);

                // find landmark on 1 face only
                Iterator iterator;
                try {
                    int i = 0;
                    iterator = rst.getJSONArray("result").getJSONObject(0).getJSONObject("landmark").keys();

                    while (iterator.hasNext()) {
                        String key = iterator.next().toString();
                        JSONObject ori = rst.getJSONArray("result").getJSONObject(0).getJSONObject("landmark").getJSONObject(key);
                        if(key.equals("right_eye_center")){
                            Log.d(TAG,"right_eye_center index = "+i);
                        }
                        float x = (float) ori.getDouble("x");
                        float y = (float) ori.getDouble("y");
                        x = x / 100 * img.getWidth();
                        y = y / 100 * img.getHeight();
                        canvas.drawPoint(x, y, paint);
                        xBedMatrix.setEntry(i, 0, x); // save Feature Points
                        xBedMatrix.setEntry(i, 1, y); // save Feature Points
                        i++;
                    }

                    //save image
                    img = bitmap;
                    MainActivity.this.runOnUiThread(new Runnable() {
                        public void run() {
                            //show the image
                            imageView.setImageBitmap(img);
                            buttonExtract.setEnabled(true);
                        }
                    });

                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        faceppDetect.detect(img);
    }

    /*
     * Process of Face Extraction and Fitting Algorithm
     */
    private void processExtract() {

        new Thread(new Runnable() {

            @Override
            public void run() {
                Message msg = null;

                try {
                    // Use the Green Paint
                    Paint paint = new Paint();
                    paint.setColor(Color.GREEN);
                    paint.setStrokeWidth(Math.max(img.getWidth(), img.getHeight()) / 100f);

                    // Create a new canvas
                    Bitmap bitmap = Bitmap.createBitmap(img.getWidth(), img.getHeight(), img.getConfig());
                    Canvas canvas = new Canvas(bitmap);
                    canvas.drawBitmap(img, new Matrix(), null);

                    // Draw Initial model points
                    for (int i = 0; i < 83; i++) {
                        canvas.drawPoint((float) xVicMatrix.getEntry(i, 0), (float) xVicMatrix.getEntry(i, 1), paint);
                    }

                    MyProcrustes mProc = new MyProcrustes(xBedMatrix, xVicMatrix, rollAngle);
                    xResult = mProc.getProcrustes(); // 83 points Result of the model
                    double distance = mProc.getProcrustesDistance();
                    Log.d(TAG, "procrustes distance = " + distance);
                    RealMatrix R = mProc.getR();
                    Log.d(TAG, "R = " + R);
                    double S = mProc.getS(); // * (1.0 - 8.0/100.0); // Reduce the size of 8%
                    Log.d(TAG, "S = " + S);
                    RealMatrix T = mProc.getT();
                    Log.d(TAG, "T = " + T);

                    int NUM_CASES_POINTS_2 = fileSize(SHAPE_DIRECTORY, AVERAGE_SHAPE_2D_FILE) / BYTES_PER_FLOAT;
                    int num_points = NUM_CASES_POINTS_2 / 2; // Number of Lines == Row Dimension // num_points = 8489
                    Log.d(TAG,"num_points = " + num_points);
                    RealMatrix averageShape2D =
                            readBin2DShapetoMatrix(SHAPE_DIRECTORY, AVERAGE_SHAPE_2D_FILE, num_points);
                    // Adapt the translation matrix to the new dimension (8489 rows)
                    double tx = T.getEntry(0, 0), ty = T.getEntry(0, 1);// +15; // +18; // Higher value the face go down
                    RealMatrix tt = new Array2DRowRealMatrix(num_points, 2);
                    for (int i = 0; i < num_points; i++) {
                        tt.setEntry(i, 0, tx);
                        tt.setEntry(i, 1, ty);
                    }

                    // Build the 83 Feature Points from the Average2D Shape ////////////////////////
                    RealMatrix averageShape2DFtPt = new Array2DRowRealMatrix(83, 2);
                    int[] landmarks83Index = readBin83PtIndex(CONFIG_DIRECTORY, INDEX83PT_FILE);
                    for (int i = 0; i<landmarks83Index.length; i++) { //
                        int tmp = landmarks83Index[i] - 1; // -1 because the index starts at 1 in modelpoint_index file
                        averageShape2DFtPt.setEntry(i,0,averageShape2D.getEntry(tmp,0));
                        averageShape2DFtPt.setEntry(i,1, averageShape2D.getEntry(tmp,1));
                    }
                    PointF rightEyeAverageShape2DFtPt = new PointF((float) averageShape2DFtPt.getEntry(66,0),
                            (float) averageShape2DFtPt.getEntry(66,1)); // Point correspondant to the RightEyeCenter
                    ////////////////////////////////////////////////////////////////////////////////

                    // Transform it by using RightEye as Reference /////////////////////////////////
                    // Eyes must be perfectly aligned to show a "Good" Face ////////////////////////
                    PointF origin = new PointF(0, 0); // Origin of the image
                    PointF rightEyeInputFt = new PointF((float) xBedMatrix.getEntry(66,0),(float) xBedMatrix.getEntry(66,1));
                    RealMatrix X0 = translate(averageShape2D, rightEyeAverageShape2DFtPt, origin);
                    RealMatrix averageShape2DRes = X0.multiply(R).scalarMultiply(S);
                    averageShape2DRes = translate(averageShape2DRes, origin, rightEyeInputFt);
                    ////////////////////////////////////////////////////////////////////////////////

                    // Get the Pixels of Face from the previous Image
                    facePixels = new ArrayList<>();
                    for (int i = 0; i < num_points; i++) {
                        try {
                            int x = (int) Math.round(averageShape2DRes.getEntry(i, 0));
                            int y = (int) Math.round(averageShape2DRes.getEntry(i, 1));
                            Pixel p = new Pixel(x, y, imgBeforeDetect.getPixel(x, y)); // 1st Constructor x and y
                            facePixels.add(i, p);
                        } catch (Exception e) {
                            throw new IllegalArgumentException("Outside of the image");
                        }
                    }
                    Log.d(TAG,"facePixels size = "+facePixels.size());

                    // Draw White on Face for every Pixels we have extracted
                    for (Pixel p : facePixels) {
                        bitmap.setPixel(p.getX(), p.getY(), Color.WHITE);
                    }

                    // Save Image
                    img = bitmap;
                    MainActivity.this.runOnUiThread(new Runnable() {
                        public void run() {
                            imageView.setImageBitmap(img);
                            buttonSave.setEnabled(true);
                        }
                    });

                    ////////////////////////////////////////////////////////////////////////////////
                    // Compute Cost Function 10 times  ///// Core of the program ///////////////////
                    ////////////////////////////////////////////////////////////////////////////////

                    // Initialisation
                    modelShape = new float[NUM_CASES_POINTS];
                    modelTexture = new float[NUM_CASES_POINTS];
                    modelPixels = new ArrayList<>();
                    averagePixels = new ArrayList<>();
                    float[] averageTexture = readBinFloat(TEXTURE_DIRECTORY, AVERAGE_TEXTURE_FILE, NUM_CASES_POINTS);
                    float[] averageShape = readBinFloat(SHAPE_DIRECTORY, AVERAGE_SHAPE_FILE, NUM_CASES_POINTS);
                    Log.d(TAG,"NUM CASES POINTS = "+ NUM_CASES_POINTS);
                    Log.d(TAG,"averageTexture size = "+ averageTexture.length);
                    Log.d(TAG,"averageShape size = "+ averageShape.length);
                    float [][] s = readBinFloatDoubleArray(SHAPE_DIRECTORY,
                            FEATURE_S_FILE, NUM_CASES_POINTS, 60); // Load the feature shape File
                   float [][] t = readBinFloatDoubleArray(TEXTURE_DIRECTORY,
                            FEATURE_T_FILE, NUM_CASES_POINTS, 100); // feature texture
                    Log.d(TAG,"averageShape size = "+ averageShape.length);
                    Log.d(TAG,"averageTexture size = "+ averageTexture.length);

                    float sigmaI = 10f, sigmaF = 1f;
                    float[] alpha = new float[60];
                    float[] beta = new float[100];
                    for (int i = 0; i < 60; i++) {
                        alpha[i] = 1.0f / 60.0f; // initial alpha value
                    }
                    for (int i = 0; i < 100; i++) {
                        beta[i] = 1.0f / 100.0f; // initial beta value
                    }
                    // Initial Shape Model with initial alpha //////////////////////////////////////
                    float res1, res2, res3;
                    for(int idx=0; idx<NUM_CASES_POINTS; idx = idx + 3){
                        res1 = 0.0f; res2 = 0.0f; res3 = 0.0f;
                        for(int i=0; i <60; i++){
                            res1 += alpha[i] * s[idx][i];
                            res2 += alpha[i] * s[idx + 1][i];
                            res3 += alpha[i] * s[idx +2][i];
                        }
                        modelShape[idx] = averageShape[idx] + res1;
                        modelShape[idx + 1] = averageShape[idx + 1] + res2;
                        modelShape[idx + 2] = averageShape[idx + 2] + res3;
                    }
                    ////////////////////////////////////////////////////////////////////////////////

                    // Initial Texture Model with initial beta /////////////////////////////////////
                    float res4, res5, res6;
                    for(int idx=0; idx<NUM_CASES_POINTS; idx = idx + 3){
                        res4 = 0.0f; res5 = 0.0f; res6 = 0.0f;
                        for(int i=0; i <100; i++){
                            res4 += beta[i] * t[idx][i];
                            res5 += beta[i] * t[idx + 1][i];
                            res6 += beta[i] * t[idx +2][i];
                        }
                        modelTexture[idx] = averageTexture[idx] + res4;
                        modelTexture[idx + 1] = averageTexture[idx + 1] + res5;
                        modelTexture[idx + 2] = averageTexture[idx + 2] + res6;
                    }
                    ////////////////////////////////////////////////////////////////////////////////

                    // Build Average Pixels
                    // Contains Average Shape and Average Texture data
                    for(int i=0, idx=0; i<NUM_CASES_POINTS; i=i+3,idx++){
                        //get R G B and X Y
                        int rgb = Color.rgb((int) averageTexture[i], (int) averageTexture[i + 1], (int) averageTexture[i + 2]);
                        Pixel p = new Pixel(averageShape[i], averageShape[i + 2], rgb); // 2nd Constructor with xF and yF
                        averagePixels.add(idx, p);
                    }
                    modelPixels = averagePixels; // just initialy populate the list, the values doesnt matter

                    // Build Average 83 Feature Points (after Procrustes in the image)
                    for (int i = 0; i<landmarks83Index.length; i++) {
                        int tmp = landmarks83Index[i] - 1; // -1 because the index starts at 1 in modelpoint_index file
                        xAverage83FtPt.setEntry(i, 0, averageShape2DRes.getEntry(tmp,0));
                        xAverage83FtPt.setEntry(i, 1, averageShape2DRes.getEntry(tmp, 1));
                    }

                    Log.d(TAG, "Start Cost Function Itearation");

                    ////////////////////////////////////////////////////////////////////////////////
                    ////////////////////////////////// Iteration ///////////////////////////////////
                    ////////////////////////////////////////////////////////////////////////////////
                    for(int it = 0; it < 10; it++){

                        // Procrustes Analysis Transformation //////////////////////////////////////
                        // Build the Model 2D Shape and 83 Feature Points in it /////////////
                        RealMatrix modelShape2D = new Array2DRowRealMatrix(num_points, 2); // num_points = 8489
                        for (int i = 0, idx = 0; i < num_points ; i++, idx = idx + 3) {
                            modelShape2D.setEntry(i,0, modelShape[idx]);
                            modelShape2D.setEntry(i,1, modelShape[idx + 2]);
                        }

                        RealMatrix modelShape2DFtPt = new Array2DRowRealMatrix(83, 2);
                        for (int i = 0; i<landmarks83Index.length; i++) {
                            int tmp = landmarks83Index[i] - 1; // -1 because the index starts at 1 in modelpoint_index file
                            modelShape2DFtPt.setEntry(i,0, modelShape2D.getEntry(tmp,0));
                            modelShape2DFtPt.setEntry(i,1, modelShape2D.getEntry(tmp,1));
                        }

                        PointF rightEyeModelShape2DFtPt = new PointF((float) modelShape2DFtPt.getEntry(66,0),
                                (float) modelShape2DFtPt.getEntry(66,1)); // the point correspondant to the RightEyeCenter

                        // Transform it by using RightEye as Reference ///
                        // Eyes must be perfectly aligned to show a "Good" Face ///
                        X0 = translate(modelShape2D, rightEyeModelShape2DFtPt, origin);
                        RealMatrix modelShape2DRes = X0.multiply(R).scalarMultiply(S);
                        modelShape2DRes = translate(modelShape2DRes, origin, rightEyeInputFt); // Result Matrix of PA
                        ////////////////////////////////////////////////////////////////////////////

                        // Build Model 83 Feature Points (after Procrustes in the image)
                        for (int i = 0; i<landmarks83Index.length; i++) {
                            int tmp = landmarks83Index[i] - 1; // -1 because the index starts at 1 in modelpoint_index file
                            xModel83FtPt.setEntry(i, 0, modelShape2DRes.getEntry(tmp,0));
                            xModel83FtPt.setEntry(i, 1, modelShape2DRes.getEntry(tmp, 1));
                        }

                        // Update Model Pixels
                        // Contains Model Shape and Model Texture data
                        for(int idx = 0, i = 0; idx <NUM_CASES_POINTS; idx = idx + 3, i++){
                            //get R G B and X Y
                            int rgb = Color.rgb((int) modelTexture[idx], (int) modelTexture[idx + 1], (int) modelTexture[idx + 2]);
                            Pixel p = new Pixel(modelShape[idx], modelShape[idx + 2], rgb); // 2nd Constructor with xF and yF
                            modelPixels.set(i, p);
                        }
                        Bitmap bmpModel = modelImage(); // Create Image of the model
/*
                        Bitmap bmpSobelGx = BmpSobelGx(bmpModel);
                        saveBitmaptoPNG(TEXTURE_DIRECTORY, "bmpSobelGx.png", bmpSobelGx); //save
                        Bitmap bmpSobelGy = BmpSobelGy(bmpModel);
                        saveBitmaptoPNG(TEXTURE_DIRECTORY, "bmpSobelGy.png", bmpSobelGy); //save
*/
                        // Build Cost Function /////////////////////////////////////////////////////
                        CostFunction costFunc =  new CostFunction(facePixels, averagePixels, modelPixels,
                                xBedMatrix, xAverage83FtPt, xModel83FtPt, s, t, bmpModel, alpha, beta, sigmaI, sigmaF);
                        alpha = costFunc.getAlpha();
                        beta = costFunc.getBeta();
                        ////////////////////////////////////////////////////////////////////////////

                        // Update Shape with alpha values //////////////////////////////////////////
                        for(int idx=0; idx<NUM_CASES_POINTS; idx = idx + 3){
                            res1 = 0.0f; res2 = 0.0f; res3 = 0.0f;
                            for(int i=0; i <60; i++){
                                res1 += alpha[i] * s[idx][i];
                                res2 += alpha[i] * s[idx + 1][i];
                                res3 += alpha[i] * s[idx +2][i];
                            }
                            modelShape[idx] = averageShape[idx] + res1;
                            modelShape[idx + 1] = averageShape[idx + 1] + res2;
                            modelShape[idx + 2] = averageShape[idx + 2] + res3;
                        }
                        ////////////////////////////////////////////////////////////////////////////

                        // Update Texture with beta values ///////////////////////////////////////
                        for(int idx=0; idx<NUM_CASES_POINTS; idx = idx + 3){
                            res4 = 0.0f; res5 = 0.0f; res6 = 0.0f;
                            for(int i=0; i <100; i++){
                                res4 += beta[i] * t[idx][i];
                                res5 += beta[i] * t[idx + 1][i];
                                res6 += beta[i] * t[idx +2][i];
                            }
                            modelTexture[idx] = averageTexture[idx] + res4;
                            modelTexture[idx + 1] = averageTexture[idx + 1] + res5;
                            modelTexture[idx + 2] = averageTexture[idx + 2] + res6;
                        }
                        ////////////////////////////////////////////////////////////////////////////

                        // Update sigmaF and sigmaI
                        sigmaF++; // no longer rely on Ef at the end
                        sigmaI--; // put more and more weight on Ei

                        // free ressources
                        bmpModel.recycle();
                    }
                    ////////////////////////////////////////////////////////////////////////////////
                    ////////////////////////////////////////////////////////////////////////////////
                    ////////////////////////////////////////////////////////////////////////////////

                    msg = mHandler.obtainMessage(EXTRACT_OK);
                } catch (Exception e) {
                    e.printStackTrace();
                    msg = mHandler.obtainMessage(ERROR);
                } finally {
                    // send the message
                    if (msg != null) {
                        mHandler.sendMessage(msg);
                    }
                }
            }
        }).start();
    }

    /*
     * Save the Final Shape and Texture in Files
     */
    private void processSave() {

        new Thread(new Runnable() {

            @Override
            public void run() {
                Message msg = null;

                try {
                    IOHelper.convertPixelsToBin(facePixels, "3DFace/simplification_bin/Texture/faceTexture.dat");
                    writeBinFloatScale(SHAPE_DIRECTORY, MODEL_SHAPE_FILE, modelShape);
                    msg = mHandler.obtainMessage(SAVE_OK);
                } catch (Exception e) {
                    e.printStackTrace();
                    msg = mHandler.obtainMessage(ERROR);
                } finally {
                    // send the message
                    if (msg != null) {
                        mHandler.sendMessage(msg);
                    }
                }
            }
        }).start();
    }

    private final Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            AlertDialog.Builder builder;
            LayoutInflater inflater;
            View layout;
            TextView text;
            Toast toast;
            int menuBarId = MainActivity.this.getResources()
                    .getIdentifier("action_bar_default_height","dimen","android");
            int y = getResources().getDimensionPixelSize(menuBarId);

            switch (msg.what) {
                case DETECT_OK:
                    // Inflate the Layout
                    inflater = getLayoutInflater();
                    layout = inflater.inflate(R.layout.toast_success,
                            (ViewGroup) findViewById(R.id.custom_toast_id));
                    text = (TextView) layout.findViewById(R.id.text);
                    text.setText("Face Detected");
                    // Create Custom Toast
                    toast = new Toast(MainActivity.this);
                    toast.setGravity(Gravity.FILL_HORIZONTAL|Gravity.TOP, 0, y);
                    toast.setDuration(Toast.LENGTH_SHORT);
                    toast.setView(layout);
                    toast.show();
                    break;
                case EXTRACT_OK:
                    // Inflate the Layout
                    inflater = getLayoutInflater();
                    layout = inflater.inflate(R.layout.toast_success,
                            (ViewGroup) findViewById(R.id.custom_toast_id));
                    text = (TextView) layout.findViewById(R.id.text);
                    text.setText("Face Extracted");
                    // Create Custom Toast
                    toast = new Toast(MainActivity.this);
                    toast.setGravity(Gravity.FILL_HORIZONTAL|Gravity.TOP, 0, y);
                    toast.setDuration(Toast.LENGTH_SHORT);
                    toast.setView(layout);
                    toast.show();
                    break;
                case SAVE_OK:
                    // Inflate the Layout
                    inflater = getLayoutInflater();
                    layout = inflater.inflate(R.layout.toast_success,
                            (ViewGroup) findViewById(R.id.custom_toast_id));
                    text = (TextView) layout.findViewById(R.id.text);
                    text.setText("Face Texture Saved");
                    // Create Custom Toast
                    toast = new Toast(MainActivity.this);
                    toast.setGravity(Gravity.FILL_HORIZONTAL|Gravity.TOP, 0, y);
                    toast.setDuration(Toast.LENGTH_SHORT);
                    toast.setView(layout);
                    toast.show();
                    break;
                case ERROR:
                    // Inflate the Layout
                    inflater = getLayoutInflater();
                    layout = inflater.inflate(R.layout.toast_error,
                            (ViewGroup) findViewById(R.id.custom_toast_id));
                    text = (TextView) layout.findViewById(R.id.text);
                    text.setText("Error during the process :\n -> Network error.\n -> No face. \n -> File reading error.");
                    // Create Custom Toast
                    toast = new Toast(MainActivity.this);
                    toast.setGravity(Gravity.FILL_HORIZONTAL|Gravity.TOP, 0, y);
                    toast.setDuration(Toast.LENGTH_SHORT);
                    toast.setView(layout);
                    toast.show();
                    break;
            }
            mProgressDialog.dismiss();
        }
    };

    private File createImageFile() throws IOException {
        // Create an image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
        return File.createTempFile(
                imageFileName,  /* prefix */
                ".jpg",         /* suffix */
                storageDir      /* directory */
        );
    }

    // Add the taken image in the Gallery
    private void galleryAddPic() {
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        Log.d(TAG,"image path"+mCurrentPhotoPath);
        File f = new File(mCurrentPhotoPath);
        Uri contentUri = Uri.fromFile(f);
        mediaScanIntent.setData(contentUri);
        this.sendBroadcast(mediaScanIntent);
    }

    /* Some lifecycle callbacks so that the image location path can survive orientation change */
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState){
        outState.putString(PICTURE_PATH, mCurrentPhotoPath);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState){
        super.onRestoreInstanceState(savedInstanceState);
        mCurrentPhotoPath = savedInstanceState.getString(PICTURE_PATH);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart");
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        Log.d(TAG, "onRestart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_11,
                this, mLoaderCallback);
        Log.d(TAG, "onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        if (img != null) {
            img.recycle();
        }
        if (imgBeforeDetect != null) {
            imgBeforeDetect.recycle();
        }
    }

    // Create an Image from Model Pixels /////////////////////////////////////////
    private Bitmap modelImage(){

        int w = 80, h = 80;
        Bitmap bmpModel = Bitmap.createBitmap(w, h,
                Bitmap.Config.ARGB_8888); // this creates a MUTABLE bitmap
        float[] maxMin = getMaxMin(modelPixels);
        Log.d(TAG," max X = "+ maxMin[0]);
        Log.d(TAG," min X = "+ maxMin[1]);
        Log.d(TAG," max Y = "+ maxMin[2]);
        Log.d(TAG, " min Y = " + maxMin[3]);

        for (Pixel p : modelPixels) {
            float a = p.getXF();
            float b = p.getYF();
            int x = (int) ((80-1) * ( p.getXF() - maxMin[1])/ (maxMin[0] - maxMin[1]));
            int y = (int) ((80-1) * ( p.getYF() - maxMin[3]) / (maxMin[2] - maxMin[3]));
            // Set the Pixel Location (int) of Model Image to ModelPixels
            p.setX(x);
            p.setY(y);
            try{
                bmpModel.setPixel( x, y, p.getRGB());
            } catch(Exception e){
                // if failed => print what was wrong
                Log.e(TAG, "a = " + a);
                Log.e(TAG, "b = " + b);
                Log.e(TAG, "x = " + x);
                Log.e(TAG, "y = " + y);
                throw new IllegalArgumentException("Out of border");
            }
        }
        // fill the pixels hole
        fillHole1(bmpModel);
        fillHole2(bmpModel);
        fillHole2(bmpModel); // again

        saveBitmaptoPNG(TEXTURE_DIRECTORY, "modelFace2D.png", bmpModel); //save

        return bmpModel;
    }


    //Sobel is applied inside the Cost Function
    /////////////////////////////////// Apply Sobel Filter /////////////////////////////////////////
    private Bitmap BmpSobelGx(Bitmap srcBmp) {
        // variables
        Mat src = new Mat();
        Bitmap dstBmp = Bitmap.createBitmap(srcBmp.getWidth(),
                srcBmp.getHeight(), Bitmap.Config.ARGB_8888);

        // convert Bmp to Mat
        Utils.bitmapToMat(srcBmp, src);

        SobelFilterGx sobelGx = new SobelFilterGx();
        sobelGx.apply(src, src);

        Utils.matToBitmap(src, dstBmp);

        return dstBmp;
    }

    private Bitmap BmpSobelGy(Bitmap srcBmp) {
        // variables
        Mat src = new Mat();
        Bitmap dstBmp = Bitmap.createBitmap(srcBmp.getWidth(),
                srcBmp.getHeight(), Bitmap.Config.ARGB_8888);

        // convert Bmp to Mat
        Utils.bitmapToMat(srcBmp, src);

        SobelFilterGy sobelGy = new SobelFilterGy();
        sobelGy.apply(src, src);

        Utils.matToBitmap(src, dstBmp);

        return dstBmp;
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////

}