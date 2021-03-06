// Derived from Spoon.java in square/spoon
// https://github.com/square/spoon/blob/94584b46f6152e7033c2e3fe9997c8491d6fedd1/spoon-client/src/main/java/com/squareup/spoon/Spoon.java
/*
   Copyright 2013 Square, Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

// This file contains significant modifications from the original work
// Modifications Copyright 2015, Twitter Inc

package tools.fastlane.screengrab;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Environment;
import android.os.Looper;
import android.support.test.espresso.Espresso;
import android.support.test.espresso.UiController;
import android.support.test.espresso.ViewAction;
import android.support.test.espresso.matcher.ViewMatchers;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import org.hamcrest.Matcher;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

import tools.fastlane.screengrab.file.Chmod;

public class Screengrab {
    private static final Pattern TAG_PATTERN = Pattern.compile("[a-zA-Z0-9_-]+");

    static final String NAME_SEPARATOR = "_";

    private static final String EXTENSION = ".png";
    private static final String TAG = "Screengrab";
    private static final int FULL_QUALITY = 100;
    private static final String SCREENGRAB_DIR_NAME = "screengrab";

    public static void screenshot(final String screenshotName) {
        // Use Espresso to find the Activity of the Root View. Using a ViewMatcher guarantees that
        // the Activity of the view is RESUMED before taking the screenshot
        Espresso.onView(ViewMatchers.isRoot()).perform(new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return ViewMatchers.isDisplayed();
            }

            @Override
            public String getDescription() {
                return "taking screenshot of the Activity";
            }

            @Override
            public void perform(UiController uiController, View view) {
                final Activity activity = scanForActivity(view.getContext());

                if (activity == null) {
                    throw new IllegalStateException("Couldn't get the activity from the view context");
                }

                if (!TAG_PATTERN.matcher(screenshotName).matches()) {
                    throw new IllegalArgumentException("Tag may only contain the letters a-z, A-Z, the numbers 0-9, " +
                            "underscores, and hyphens");
                }
                try {
                    File screenshotDirectory = getFilesDirectory(activity.getApplicationContext(), Locale.getDefault());
                    String screenshotFileName = screenshotName + NAME_SEPARATOR + System.currentTimeMillis() + EXTENSION;
                    File screenshotFile = new File(screenshotDirectory, screenshotFileName);
                    takeScreenshot(activity, screenshotFile);
                    Log.d(TAG, "Captured screenshot \"" + screenshotFileName + "\"");
                } catch (Exception e) {
                    throw new RuntimeException("Unable to capture screenshot.", e);
                }
            }

            private Activity scanForActivity(Context context) {
                if (context == null) {
                    return null;

                } else if (context instanceof Activity) {
                    return (Activity) context;

                } else if (context instanceof ContextWrapper) {
                    return scanForActivity(((ContextWrapper) context).getBaseContext());
                }

                return null;
            }
        });
    }

    private static void takeScreenshot(final Activity activity, File file) throws IOException {

        // Check if storage is available
        if (!isExternalStorageWritable()) {
            Log.d(TAG, "External storage is not available.");
            return;
        }

        // Get a bitmap from the activity root view. When the drawing cache is enabled,
        // the next call to getDrawingCache() will draw the view in a bitmap.
        View rootView = activity.getWindow().getDecorView().getRootView();
        rootView.setDrawingCacheEnabled(true);
        Bitmap bitmap = Bitmap.createBitmap(rootView.getDrawingCache());
        rootView.setDrawingCacheEnabled(false);

        // Add the SurfaceView bit (see getAllTextureViews() below)
        List<TextureView> tilingViews = getAllTextureViews(rootView);
        if (tilingViews.size() > 0) {
            Canvas canvas = new Canvas(bitmap);
            for (TextureView TextureView : tilingViews) {
                Bitmap b = TextureView.getBitmap(TextureView.getWidth(), TextureView.getHeight());
                int[] location = new int[2];
                TextureView.getLocationInWindow(location);
                int[] location2 = new int[2];
                TextureView.getLocationOnScreen(location2);
                canvas.drawBitmap(b, location[0], location[1], null);
            }
        }

        OutputStream fos = null;
        try {
            fos = new BufferedOutputStream(new FileOutputStream(file));
            bitmap.compress(Bitmap.CompressFormat.PNG, FULL_QUALITY, fos);
            Chmod.chmodPlusR(file);
        } finally {
            bitmap.recycle();
            if (fos != null) {
                fos.close();
            }
        }
    }

    /*
     * Utils
     */

    public static boolean isExternalStorageWritable() {
        // Checks if external storage is available for read and write
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    /*
     * The classic way of taking a screenshot (above) doesn't work with TextureView, this fixes it:
     * http://stackoverflow.com/questions/19704060/screen-capture-textureview-is-black-using-drawingcache
     */

    public static List<TextureView> getAllTextureViews(View view)
    {
        List<TextureView> tilingViews = new ArrayList<TextureView>();
        if (view instanceof TextureView) {
            tilingViews.add((TextureView)view);
        }  else if(view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup)view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                tilingViews.addAll(getAllTextureViews(viewGroup.getChildAt(i)));
            }
        }

        return tilingViews;
    }

    private static void drawDecorViewToBitmap(Activity activity, Bitmap bitmap) {
        activity.getWindow().getDecorView().draw(new Canvas(bitmap));
    }

    private static File getFilesDirectory(Context context, Locale locale) throws IOException {
        File directory = null;

        if (Build.VERSION.SDK_INT >= 21) {
            File externalDir = new File(Environment.getExternalStorageDirectory(), getDirectoryName(context, locale));
            directory = initializeDirectory(externalDir);
        }

        if (directory == null) {
            File internalDir = new File(context.getDir(SCREENGRAB_DIR_NAME, Context.MODE_WORLD_READABLE), localeToDirName(locale));
            directory = initializeDirectory(internalDir);
        }

        if (directory == null) {
            throw new IOException("Unable to get a screenshot storage directory");
        }

        Log.d(TAG, "Using screenshot storage directory: " + directory.getAbsolutePath());
        return directory;
    }

    private static File initializeDirectory(File dir) {
        try {
            createPathTo(dir);

            if (dir.isDirectory() && dir.canWrite()) {
                return dir;
            }
        } catch (IOException ignored) {}

        return null;
    }

    private static String getDirectoryName(Context context, Locale locale) {
        return context.getPackageName() + "/" + SCREENGRAB_DIR_NAME + "/" + localeToDirName(locale);
    }

    private static String localeToDirName(Locale locale) {
        return locale.getLanguage() + "-" + locale.getCountry() + "/images/screenshots";
    }

    private static void createPathTo(File dir) throws IOException {
        File parent = dir.getParentFile();
        if (!parent.exists()) {
            createPathTo(parent);
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create output dir: " + dir.getAbsolutePath());
        }
        Chmod.chmodPlusRWX(dir);
    }

    private Screengrab() {
    }
}
