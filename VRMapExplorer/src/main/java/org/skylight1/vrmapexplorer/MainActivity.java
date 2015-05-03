/*
 * Copyright 2014 Google Inc. All Rights Reserved.

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.skylight1.vrmapexplorer;

import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.esri.android.map.ags.ArcGISImageServiceLayer;
import com.esri.core.geometry.Envelope;
import com.esri.core.geometry.Geometry;
import com.esri.core.geometry.MultiPath;
import com.esri.core.geometry.Point;
import com.esri.core.geometry.SpatialReference;
import com.esri.core.map.CallbackListener;
import com.esri.core.map.Feature;
import com.esri.core.map.FeatureResult;
import com.esri.core.map.Graphic;
import com.esri.core.map.ImageServiceParameters;
import com.esri.core.tasks.query.QueryParameters;
import com.esri.core.tasks.query.QueryTask;
import com.google.vrtoolkit.cardboard.CardboardActivity;
import com.google.vrtoolkit.cardboard.CardboardView;
import com.google.vrtoolkit.cardboard.Eye;
import com.google.vrtoolkit.cardboard.HeadTransform;
import com.google.vrtoolkit.cardboard.Viewport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

import javax.microedition.khronos.egl.EGLConfig;

public class MainActivity extends CardboardActivity implements CardboardView.StereoRenderer {

  private int mTextureDataHandle;

    private static class HelpfulArcGISImageServiceLayer extends ArcGISImageServiceLayer {

        public HelpfulArcGISImageServiceLayer(String url, ImageServiceParameters options) {
            super(url, options);
        }

        @Override
        public void getImageAsych(int w, int h, Envelope extent, CallbackListener<byte[]> imagecallback) {
            super.getImageAsych(w, h, extent, imagecallback);
        }

        @Override
        public byte[] getImage(int width, int height, Envelope extent) throws Exception {
            return super.getImage(width, height, extent);
        }
    }

  private static final String TAG = "MainActivity";

  private static final float Z_NEAR = 0.1f;
  private static final float Z_FAR = 10000.0f;

  private static final float CAMERA_Z = 0.01f;
  private static final float TIME_DELTA = 0.3f;

  private static final int COORDS_PER_VERTEX = 3;
  private static final int COORDS_PER_TEXTURE = 2;

    private static final int IMAGE_SIDE = 4000;

  // We keep the light always position just above the user.
  private static final float[] LIGHT_POS_IN_WORLD_SPACE = new float[] { 0.0f, 2.0f, 0.0f, 1.0f };

  private static final Point mapCenter = new Point(-8835375.854977166, 5410791.715050752);

  private final float[] lightPosInEyeSpace = new float[4];

  private FloatBuffer floorVertices;
  private FloatBuffer floorTextureCoords;

  private int floorProgram;

  private float[] modelCube;
  private float[] camera;
  private float[] view;
  private float[] headView;
  private float[] modelViewProjection;
  private float[] modelView;
  private float[] modelFloor;

  private float objectDistance = 12f;
  private float floorDepth = 200f;

  private CardboardOverlayView overlayView;

  ProgressDialog progress;

  /** This will be used to pass in the transformation matrix. */
  private int mMVPMatrixHandle;

  /** This will be used to pass in the modelview matrix. */
  private int mMVMatrixHandle;

  /** This will be used to pass in the light position. */
  private int mLightPosHandle;

  /** This will be used to pass in the texture. */
  private int mTextureUniformHandle;

  /** This will be used to pass in model position information. */
  private int mPositionHandle;

  /** This will be used to pass in model texture coordinate information. */
  private int mTextureCoordinateHandle;

  /**
   * Converts a raw text file, saved as a resource, into an OpenGL ES shader.
   *
   * @param type The type of shader we will be creating.
   * @param resId The resource ID of the raw text file about to be turned into a shader.
   * @return The shader object handler.
   */
  private int loadGLShader(int type, int resId) {
    String code = readRawTextFile(resId);
    int shader = GLES20.glCreateShader(type);
    GLES20.glShaderSource(shader, code);
    GLES20.glCompileShader(shader);

    // Get the compilation status.
    final int[] compileStatus = new int[1];
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);

    // If the compilation failed, delete the shader.
    if (compileStatus[0] == 0) {
      Log.e(TAG, "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader));
      GLES20.glDeleteShader(shader);
      shader = 0;
    }

    if (shader == 0) {
      throw new RuntimeException("Error creating shader.");
    }

    return shader;
  }

  /**
   * Checks if we've had an error inside of OpenGL ES, and if so what that error is.
   *
   * @param label Label to report in case of error.
   */
  private static void checkGLError(String label) {
    int error;
    while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
      Log.e(TAG, label + ": glError " + error);
      throw new RuntimeException(label + ": glError " + error);
    }
  }

  /**
   * Sets the view to our CardboardView and initializes the transformation matrices we will use
   * to render our scene.
   */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.common_ui);
    CardboardView cardboardView = (CardboardView) findViewById(R.id.cardboard_view);
    cardboardView.setRenderer(this);
    setCardboardView(cardboardView);

    modelCube = new float[16];
    camera = new float[16];
    view = new float[16];
    modelViewProjection = new float[16];
    modelView = new float[16];
    modelFloor = new float[16];
    headView = new float[16];

    overlayView = (CardboardOverlayView) findViewById(R.id.overlay);
    overlayView.show3DToast("Pull the magnet when you find an object.");

  Runnable task = new Runnable() {
      public void run() {
          try {
              String[] queryArray = {"http://services.arcgisonline.com/ArcGIS/rest/services/Demographics/USA_Average_Household_Size/MapServer/3", "AVGHHSZ_CY>3.5"};
              AsyncQueryTask asyncQuery = new AsyncQueryTask();
              asyncQuery.execute(queryArray);
          } catch (Exception e) {
              Log.e("Arcadius Error", "Before starting thread: begin ex");
              e.printStackTrace();
              Log.e("Arcadius Error", "Before starting thread: end ex");
          }
      }
  };
  new Handler(Looper.getMainLooper()).post(task);
}



private class AsyncQueryTask extends AsyncTask<String, Void, FeatureResult> {

    @Override
    protected void onPreExecute() {
        progress = new ProgressDialog(MainActivity.this);

        progress = ProgressDialog.show(MainActivity.this, "",
                "Please wait....query task is executing");

    }

    /**
     * First member in string array is the query URL; second member is the
     * where clause.
     */
    @Override
    protected FeatureResult doInBackground(String... queryArray) {

        if (queryArray == null || queryArray.length <= 1)
            return null;

        String url = queryArray[0];
        QueryParameters qParameters = new QueryParameters();
        String whereClause = queryArray[1];
        SpatialReference sr = SpatialReference.create(102100);
       // qParameters.setGeometry(mMapView.getExtent());
        qParameters.setOutSpatialReference(sr);
        qParameters.setReturnGeometry(true);
        qParameters.setWhere(whereClause);

        QueryTask qTask = new QueryTask(url);

        try {
            FeatureResult results = qTask.execute(qParameters);
            return results;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;

    }



    Float[] getEveryNthPointFromThisMultiPath(MultiPath arcMultiPathParam, int everyNpoints) {
        int arcPointCount = arcMultiPathParam.getPointCount();
        Log.e("arcadiusDebug", "arcPointCount: "+arcPointCount);

        ArrayList<Float> newSmallerArrayToReturn = new ArrayList<Float>();

        for(int i=0; i<arcMultiPathParam.getPointCount(); i+=everyNpoints) {
            newSmallerArrayToReturn.add((float)arcMultiPathParam.getPoint(i).getX());
            newSmallerArrayToReturn.add((float)arcMultiPathParam.getPoint(i).getZ());
            newSmallerArrayToReturn.add((float)arcMultiPathParam.getPoint(i).getY());
        }

        Float[] simpleArray = new Float[ newSmallerArrayToReturn.size() ];

        newSmallerArrayToReturn.toArray(simpleArray);

        Log.e("arcadiusDebug", "Big array points: "+arcMultiPathParam.getPointCount());
        Log.e("arcadiusDebug", "Small array points: "+simpleArray.length);
        return simpleArray;
    }


    @Override
    protected void onPostExecute(FeatureResult results) {

        String message = "No result comes back";

        if (results != null) {
            int size = (int) results.featureCount();
            Log.e("arcadiusDebug", "Number of Polygons: "+size);
            for (Object element : results) {
                progress.incrementProgressBy(size / 100);
                if (element instanceof Feature) {
                    Feature feature = (Feature) element;
                    // turn feature into graphic\

                    Geometry arcGeometry = feature.getGeometry();
                    Geometry.Type arcType = arcGeometry.getType();

                    MultiPath arcMultiPath = (MultiPath)arcGeometry;

                    Graphic graphic = new Graphic(feature.getGeometry(),
                            feature.getSymbol(),
                            feature.getAttributes());

                    Float[] SmallerArray = getEveryNthPointFromThisMultiPath(arcMultiPath, 100);
                }
            }
            // update message with results
            message = String.valueOf(results.featureCount())
                    + " results have returned from query.";

        }
        progress.dismiss();

        overlayView.show3DToast(message);
    }
}


  @Override
  public void onRendererShutdown() {
    Log.i(TAG, "onRendererShutdown");
  }

  @Override
  public void onSurfaceChanged(int width, int height) {
    Log.i(TAG, "onSurfaceChanged");
  }

  /**
   * Creates the buffers we use to store information about the 3D world.
   *
   * <p>OpenGL doesn't use Java arrays, but rather needs data in a format it can understand.
   * Hence we use ByteBuffers.
   *
   * @param config The EGL configuration used when creating the surface.
   */
  @Override
  public void onSurfaceCreated(EGLConfig config) {
    Log.i(TAG, "onSurfaceCreated");
    GLES20.glClearColor(0.5f, 0.8f, 0.9f, 1.0f); // Dark background so text shows up well.

    // make a floor
    ByteBuffer bbFloorVertices = ByteBuffer.allocateDirect(WorldLayoutData.FLOOR_COORDS.length * 4);
    bbFloorVertices.order(ByteOrder.nativeOrder());
    floorVertices = bbFloorVertices.asFloatBuffer();
    floorVertices.put(WorldLayoutData.FLOOR_COORDS);
    floorVertices.position(0);

    mTextureDataHandle = loadTexture(this);

    ByteBuffer bbFloorTextureCoords = ByteBuffer.allocateDirect(WorldLayoutData.FLOOR_TEXTURE_COORDS.length * 4);
    bbFloorTextureCoords.order(ByteOrder.nativeOrder());
    floorTextureCoords = bbFloorTextureCoords.asFloatBuffer();
    floorTextureCoords.put(WorldLayoutData.FLOOR_TEXTURE_COORDS);
    floorTextureCoords.position(0);

    int floorVertexShader = loadGLShader(GLES20.GL_VERTEX_SHADER, R.raw.floor_vertex);
    int floorFragmentShader = loadGLShader(GLES20.GL_FRAGMENT_SHADER, R.raw.floor_fragment);

    floorProgram = ShaderHelper.createAndLinkProgram(floorVertexShader, floorFragmentShader,
            new String[]{"a_Position", "a_Color", "a_Normal", "a_TexCoordinate"});

    checkGLError("Floor program");

    checkGLError("Floor program params");

    GLES20.glEnable(GLES20.GL_DEPTH_TEST);

    // Object first appears directly in front of user.
    Matrix.setIdentityM(modelCube, 0);
    Matrix.translateM(modelCube, 0, 0, 0, -objectDistance);

    Matrix.setIdentityM(modelFloor, 0);
    Matrix.translateM(modelFloor, 0, 0, -floorDepth, 0); // Floor appears below user.

    checkGLError("onSurfaceCreated");
  }

    public byte[] loadTile() {
        ImageServiceParameters ops = new ImageServiceParameters();
        ops.setFormat(ImageServiceParameters.IMAGE_FORMAT.JPG);

        HelpfulArcGISImageServiceLayer imageServiceLayer = new HelpfulArcGISImageServiceLayer(
                "http://sampleserver6.arcgisonline.com/arcgis/rest/services/Toronto/ImageServer",
                ops);
        try {
            byte[] image = imageServiceLayer.getImage(IMAGE_SIDE, IMAGE_SIDE, new Envelope(mapCenter, 15000, 15000));

            return image;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

  public int loadTexture(final Context context)
  {
    final int[] textureHandle = new int[1];

    GLES20.glGenTextures(1, textureHandle, 0);

    if (textureHandle[0] != 0) {
      // Read in the resource

      byte[] tile = loadTile();

      final BitmapFactory.Options options = new BitmapFactory.Options();
      options.inScaled = false;   // No pre-scaling
      final Bitmap bitmap = BitmapFactory.decodeByteArray(tile, 0, tile.length, options);

      // Bind to the texture in OpenGL
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0]);

      // Set filtering
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);

      // Load the bitmap into the bound texture.
      GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);

      // Recycle the bitmap, since its data has been loaded into OpenGL.
      bitmap.recycle();
    }

    if (textureHandle[0] == 0)
    {
      throw new RuntimeException("Error loading texture.");
    }

    return textureHandle[0];
  }

  /**
   * Converts a raw text file into a string.
   *
   * @param resId The resource ID of the raw text file about to be turned into a shader.
   * @return The context of the text file, or null in case of error.
   */
  private String readRawTextFile(int resId) {
    InputStream inputStream = getResources().openRawResource(resId);
    try {
      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append("\n");
      }
      reader.close();
      return sb.toString();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * Prepares OpenGL ES before we draw a frame.
   *
   * @param headTransform The head transformation in the new frame.
   */
  @Override
  public void onNewFrame(HeadTransform headTransform) {
    // Build the Model part of the ModelView matrix.
    Matrix.rotateM(modelCube, 0, TIME_DELTA, 0.5f, 0.5f, 1.0f);

    // Build the camera matrix and apply it to the ModelView.
    Matrix.setLookAtM(camera, 0, 0.0f, 0.0f, CAMERA_Z, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);

    headTransform.getHeadView(headView, 0);

    checkGLError("onReadyToDraw");
  }

  /**
   * Draws a frame for an eye.
   *
   * @param eye The eye to render. Includes all required transformations.
   */
  @Override
  public void onDrawEye(Eye eye) {
    GLES20.glClearColor(0.5f, 0.8f, 0.9f, 1.0f); // Dark background so text shows up well.

    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    checkGLError("colorParam");

    // Apply the eye transformation to the camera.
    Matrix.multiplyMM(view, 0, eye.getEyeView(), 0, camera, 0);

    // Set the position of the light
    Matrix.multiplyMV(lightPosInEyeSpace, 0, view, 0, LIGHT_POS_IN_WORLD_SPACE, 0);

    // Build the ModelView and ModelViewProjection matrices
    // for calculating cube position and light.
    float[] perspective = eye.getPerspective(Z_NEAR, Z_FAR);

    // Set modelView for the floor, so we draw floor in the correct location
    Matrix.multiplyMM(modelView, 0, view, 0, modelFloor, 0);
    Matrix.multiplyMM(modelViewProjection, 0, perspective, 0,
            modelView, 0);
    drawFloor();
  }

  @Override
  public void onFinishFrame(Viewport viewport) {
  }

  /**
   * Draw the floor.
   *
   * <p>This feeds in data for the floor into the shader. Note that this doesn't feed in data about
   * position of the light, so if we rewrite our code to draw the floor first, the lighting might
   * look strange.
   */
  public void drawFloor() {
      GLES20.glUseProgram(floorProgram);
      checkGLError("drawing floor1");

      // Set program handles for cube drawing.
      mMVPMatrixHandle = GLES20.glGetUniformLocation(floorProgram, "u_MVPMatrix");
      mMVMatrixHandle = GLES20.glGetUniformLocation(floorProgram, "u_MVMatrix");
      mLightPosHandle = GLES20.glGetUniformLocation(floorProgram, "u_LightPos");
      mTextureUniformHandle = GLES20.glGetUniformLocation(floorProgram, "u_Texture");
      checkGLError("drawing floor1");

      mPositionHandle = GLES20.glGetAttribLocation(floorProgram, "a_Position");
//    mColorHandle = GLES20.glGetAttribLocation(floorProgram, "a_Color");
//    mNormalHandle = GLES20.glGetAttribLocation(floorProgram, "a_Normal");
      mTextureCoordinateHandle = GLES20.glGetAttribLocation(floorProgram, "a_TexCoordinate");
      checkGLError("drawing floor1");


      floorVertices.position(0);
      GLES20.glVertexAttribPointer(mPositionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
              0, floorVertices);
      GLES20.glEnableVertexAttribArray(mPositionHandle);
      checkGLError("drawing floor1");

      floorTextureCoords.position(0);
      GLES20.glVertexAttribPointer(mTextureCoordinateHandle, COORDS_PER_TEXTURE, GLES20.GL_FLOAT, false,
              0, floorTextureCoords);
      GLES20.glEnableVertexAttribArray(mTextureCoordinateHandle);
      checkGLError("drawing floor1");

      // Pass in the modelview matrix.
      GLES20.glUniformMatrix4fv(mMVMatrixHandle, 1, false, modelView, 0);
      checkGLError("drawing floor1");

      // Pass in the combined matrix.
      GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, modelViewProjection, 0);
      checkGLError("drawing floor1");

      // Pass in the light position in eye space.
      GLES20.glUniform3f(mLightPosHandle, 10, 10, 10);
      checkGLError("drawing floor1");

      GLES20.glEnableVertexAttribArray(mPositionHandle);
      checkGLError("drawing floor1");
      GLES20.glEnableVertexAttribArray(mTextureCoordinateHandle);
      checkGLError("drawing floor1");

      // Bind the texture to this unit.
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureDataHandle);
      checkGLError("drawing floor1");

      // Tell the texture uniform sampler to use this texture in the shader by binding to texture unit 0.
      GLES20.glUniform1i(mTextureUniformHandle, 0);

      checkGLError("drawing floor1");

      // Draw the cube.
      GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);

      checkGLError("drawing floor");
  }
}
