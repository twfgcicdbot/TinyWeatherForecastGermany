/*
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2020, 2021, 2022, 2023, 2024 Pawel Dube
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package de.kaffeemitkoffein.tinyweatherforecastgermany;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.UnderlineSpan;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class WeatherWarningActivity extends Activity {

    ArrayList<WeatherWarning> weatherWarnings;
    ArrayList<WeatherWarning> localWarnings;
    Weather.WeatherLocation ownLocation;
    View.OnTouchListener mapTouchListener;
    ArrayList<Polygon> polygoncache;
    ArrayList<Polygon> excluded_polygoncache;
    ImageView germany;
    ImageView warningactivity_map_collapsed;
    RelativeLayout mapcontainer;
    ProgressBar rainSlideProgressBar;
    TextView rainSlideProgressBarText;
    TextView rainSlideTime;
    ImageView rainDescription;
    Bitmap germanyBitmap;
    Bitmap warningsBitmap;
    Bitmap radarBitmap;
    Bitmap administrativeBitmap;
    Bitmap visibleBitmap;
    ZoomableImageView mapZoomable;
    RelativeLayout map_collapsed_container;
    LinearLayout warningactivityMapinfoContainer;
    boolean deviceIsLandscape;
    private GestureDetector gestureDetector;
    ListView weatherList;
    WeatherWarningAdapter weatherWarningAdapter;
    Context context;
    ActionBar actionBar;
    ScheduledExecutorService scheduledExecutorService;
    boolean hide_rain = false;
    boolean hide_admin = true;
    WeatherLocationManager weatherLocationManager;
    RelativeLayout gpsProgressHolder;

    Bundle zoomMapState = null;

    RadarMN2.MercatorProjectionTile mercatorProjectionTile;

    boolean forceWeatherUpdateFlag = false;

    static float MAP_PIXEL_WIDTH;
    static float MAP_PIXEL_HEIGHT;

    public final static String WEATHER_WARNINGS_UPDATE="WEATHER_WARNINGS_UPDATE";
    public final static String WEATHER_WARNINGS_UPDATE_RESULT="WEATHER_WARNINGS_UPDATE_RESULT";
    public final static String ACTION_RAINRADAR_UPDATE="ACTION_RAINRADAR_UPDATE";

    public final static String SIS_ZOOMMAPSTATEBUNDLE="ZOOMMAPSTATEBUNDLE";
    public final static String SIS_HIDERAIN="HIDERAIN";
    public final static String SIS_HIDEADMIN="HIDEADMIN";

    public final static long CREDENTIALS_FADE_TIME_DELAY = 7000l;

    PopupWindow hintPopupWindow = null;

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (intent!=null){
                // update warning display if warnings have been updated
                if (intent.getAction().equals(WEATHER_WARNINGS_UPDATE)) {
                    weatherWarnings = WeatherWarnings.getCurrentWarnings(getApplicationContext(),true);
                    displayWarnings();
                    updateActionBarLabels();
                    hideProgressBar();
                }
                if (intent.hasExtra(WEATHER_WARNINGS_UPDATE_RESULT)){
                    // gets result if update was successful, currently not used
                    boolean updateResult = intent.getBooleanExtra(WEATHER_WARNINGS_UPDATE_RESULT,false);
                }
                if (intent.getAction().equals(MainActivity.MAINAPP_HIDE_PROGRESS)){
                    forceWeatherUpdateFlag = false;
                    hideProgressBar();
                }
            }
        }
    };

    private int nextRainSlide = 0;
    private int lastDrawnRainSlide = 0;
    private long rainSlidesStartTime = 0;
    private boolean cancelRainSlides = false;
    private boolean rainSlidesRunning = false;
    private boolean validSlideSetObtained = false;
    public final static int RAINSLIDEDELAY=750;

    private boolean isNextRainSlide(){
        if (nextRainSlide==lastDrawnRainSlide+1){
            return true;
        }
        if (nextRainSlide==0){
            if (lastDrawnRainSlide==APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE){
                return true;
            }
        }
        return false;
    }

    private boolean rainDrawLock = false;

    private final Runnable rainRadarRunnable = new Runnable() {
        @Override
        public void run() {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            nextRainSlide=0;
            rainSlidesRunning = true;
            while (!cancelRainSlides){
                long startTime = Calendar.getInstance().getTimeInMillis();
                rainDrawLock = true;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        nextRainSlide++;
                        drawRadarSlide(nextRainSlide);
                        rainDrawLock = false;
                    }
                });
                if (nextRainSlide>APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE){
                    nextRainSlide=0;
                }
                long stopTime = Calendar.getInstance().getTimeInMillis();
                long waitTime = RAINSLIDEDELAY - (stopTime-startTime);
                if (waitTime>0){
                    try {
                        Thread.sleep(waitTime);
                    } catch (InterruptedException e) {
                        // do nothing
                    }
                } else {
                    while (rainDrawLock){
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            // do nothing
                        }
                    }
                }
            }
            rainSlidesRunning = false;
            cancelRainSlides = false;
        }
    };

    private synchronized void startRainRadar(){
        if (!hide_rain){
            if (!rainSlidesRunning){
                // make sure not to start rain radar before views are created
                germany.post(new Runnable() {
                    @Override
                    public void run() {
                        new Thread(rainRadarRunnable).start();
                    }
                });
            }
        }
    }

    APIReaders.RadarMNSetGeoserverRunnable radarMNSetGeoserverRunnable;

    View.OnTouchListener forwardRainSlidesOnTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            float position = motionEvent.getX()/ view.getWidth();
            nextRainSlide = Math.round(position * 24f);
            return true;
        }
    };

    APIReaders.WeatherWarningsRunnable weatherWarningsUpdateRunnable;

    @Override
    public void onSaveInstanceState(Bundle state){
        if (mapZoomable != null){
            zoomMapState = mapZoomable.saveZoomViewState();
        }
        if (zoomMapState!=null){
            state.putBundle(SIS_ZOOMMAPSTATEBUNDLE,zoomMapState);
        }
        state.putBoolean(SIS_HIDERAIN,hide_rain);
        state.putBoolean(SIS_HIDEADMIN,hide_admin);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onRestoreInstanceState(Bundle state){
        super.onRestoreInstanceState(state);
        // do nothing here for the moment
    }

    @Override
    protected void onResume() {
        super.onResume();
        // stop rain radar while processing warnings to avoid performance issues on older devices
        cancelRainSlides=true;
        registerForBroadcast();
        if (germany==null){
            germany = (ImageView) findViewById(R.id.warningactivity_map);
        }
        PrivateLog.log(getApplicationContext(),PrivateLog.WARNINGS,PrivateLog.INFO,"app resumed.");
        if (WeatherSettings.GPSAuto(context)){
            weatherLocationManager.checkLocation();
        }
        if (DataStorage.Updates.isSyncDue(context,WeatherSettings.Updates.Category.WARNINGS)){
            if (Weather.suitableNetworkAvailable(context)){
                PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.INFO,"Weather warnings are outdated, updating data.");
                scheduledExecutorService.execute(weatherWarningsUpdateRunnable);
            } else {
                PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.ERR,"Weather warnings need to be updated, but no suitable network connection found.");
            }
        } else {
            // displayWarnings() must be in scheduledExecutorService queue to make sure it is executed before
            // radarMNSetGeoserverRunnable (see below)
            scheduledExecutorService.execute(new Runnable() {
                @Override
                public void run() {
                    weatherWarnings = WeatherWarnings.getCurrentWarnings(getApplicationContext(),true);
                    PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.INFO,"Weather warnings are up to date, showing the data available.");
                    displayWarnings();
                }
            });
        }
        // at this point, do not cancel slides (anymore)
        cancelRainSlides = false;
        scheduledExecutorService.execute(radarMNSetGeoserverRunnable);
        // start rain radar (again) if applicable
        // not needed, will always be invoked by radarMNSetGeoserverRunnable
        /*
        if (!hide_rain){
            scheduledExecutorService.execute(new Runnable() {
                @Override
                public void run() {
                    startRainRadar();
                }
            });
        }
         */
    }

    @Override
    protected void onPause(){
        cancelRainSlides = true;
        // save the map state if desired
        if (WeatherSettings.saveMapState(context)){
            PrivateLog.log(getApplicationContext(),PrivateLog.WARNINGS,PrivateLog.INFO,"Saving map state enabled.");
            if (mapZoomable!=null){
                zoomMapState = mapZoomable.saveZoomViewState();
                WeatherSettings.setZoomStateBundle(context,zoomMapState);
                PrivateLog.log(getApplicationContext(),PrivateLog.WARNINGS,PrivateLog.INFO,"Map zoom state saved.");
            }
        }
        unregisterReceiver(receiver);
        super.onPause();
        PrivateLog.log(getApplicationContext(),PrivateLog.WARNINGS,PrivateLog.INFO,"app paused.");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (hintPopupWindow!=null){
            if (hintPopupWindow.isShowing()){
                hintPopupWindow.dismiss();
                WeatherSettings.setHintCounter2(context,WeatherSettings.getHintCounter2(context)-1);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PrivateLog.log(getApplicationContext(),PrivateLog.WARNINGS,PrivateLog.INFO,"WeatherWarningActivity started.");
        try {
            ThemePicker.SetTheme(this);
        } catch (Exception e){
            PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.INFO,"Error setting theme in WeatherWarnings activity.");
        }
        super.onCreate(savedInstanceState);
        context = getApplicationContext();
        mercatorProjectionTile = RadarMN2.getRadarMapMercatorProjectionTile(context);
        mercatorProjectionTile.setScaleFactor(RadarMN2.getScaleFactor(context));
        rainSlidesStartTime = WeatherSettings.getPrefRadarLastdatapoll(context);
        WeatherSettings.setRotationMode(this);
        setContentView(R.layout.activity_weatherwarning);
        registerForBroadcast();
        // try to restore zoom factor if it is available from the savedInstanceState
        if (savedInstanceState!=null){
            Bundle bundle = savedInstanceState.getBundle(SIS_ZOOMMAPSTATEBUNDLE);
            if (bundle!=null){
                zoomMapState = bundle;
            }
            hide_rain = savedInstanceState.getBoolean(SIS_HIDERAIN,!WeatherSettings.showRadarByDefault(getApplicationContext()));
            hide_admin = savedInstanceState.getBoolean(SIS_HIDEADMIN,!WeatherSettings.showAdminMapByDefault(getApplicationContext()));
        } else {
            if (WeatherSettings.saveMapState(context)){
                PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.INFO,"Restored map position.");
                zoomMapState = WeatherSettings.getZoomStateBundle(context);
            }
            hide_rain = !WeatherSettings.showRadarByDefault(getApplicationContext());
            hide_admin = !WeatherSettings.showAdminMapByDefault(getApplicationContext());
        }
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        // action bar layout
        actionBar = getActionBar();
        actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_HOME|ActionBar.DISPLAY_HOME_AS_UP|ActionBar.DISPLAY_SHOW_TITLE);
        mapcontainer = (RelativeLayout) findViewById(R.id.warningactivity_mapcontainer);
        map_collapsed_container = (RelativeLayout) findViewById(R.id.warningactivity_map_collapsed_container);
        warningactivity_map_collapsed = (ImageView) findViewById(R.id.warningactivity_map_collapsed);
        warningactivityMapinfoContainer = (LinearLayout) findViewById(R.id.warningactivity_mapinfo_container);
        if (warningactivity_map_collapsed!=null){
            warningactivity_map_collapsed.setImageResource(WeatherIcons.getIconResource(context,WeatherIcons.MAP_COLLAPSED));
        }
        // in layout w6600dp-land this element does not exist. This is the safest way to
        // limit collapse-function to portrait mode.
        if (map_collapsed_container!=null){
            deviceIsLandscape = false;
            map_collapsed_container.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent motionEvent) {
                    if (germany!=null){
                        showMap();
                    }
                    return true;
                }
            });
        } else {
            deviceIsLandscape = true;
        }
        rainSlideProgressBar = (ProgressBar) findViewById(R.id.warningactivity_rainslideprogressbar);
        rainSlideProgressBarText = (TextView) findViewById(R.id.warningactivity_rainslideprogressbartext);
        rainSlideTime = (TextView) findViewById(R.id.warningactivity_rainslidetime);
        rainSlideTime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                nextRainSlide=0;
            }
        });
        rainDescription = (ImageView) findViewById(R.id.warningactivity_mapinfo);
        rainDescription.setOnTouchListener(forwardRainSlidesOnTouchListener);
        gpsProgressHolder = (RelativeLayout) findViewById(R.id.gps_progress_holder);
        displayOsmNotice();
        radarMNSetGeoserverRunnable = new APIReaders.RadarMNSetGeoserverRunnable(getApplicationContext()){
            @Override
            public void onProgress(long startTime, final int progress) {
                if (progress==1){
                    rainSlidesStartTime = startTime;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            germany.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!hide_rain){
                                        drawRadarSlide(0);
                                    }
                                }
                            });
                        }
                    });
                }
                updateRainSlideProgressBar(progress);
            }
            @Override
            public void onFinished(long startTime, boolean success){
                super.onFinished(startTime,success);
                if (success){
                    validSlideSetObtained = true;
                    germany.post(new Runnable() {
                        @Override
                        public void run() {
                            startRainRadar();
                        }
                    });
                } else {
                    validSlideSetObtained = false;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        rainSlideProgressBar.setVisibility(View.INVISIBLE);
                        rainSlideProgressBarText.setVisibility(View.INVISIBLE);
                    }
                });
            }
        };
        weatherLocationManager = new WeatherLocationManager(context){
          @Override
          public void newLocation(Location location){
              super.newLocation(location);
              Weather.WeatherLocationFinder weatherLocationFinder = new Weather.WeatherLocationFinder(context,location){
                @Override
                public void newWeatherLocation(Weather.WeatherLocation weatherLocation){
                    ownLocation = weatherLocation;
                    displayWarnings();
                }
              };
              weatherLocationFinder.run();
          }
        };
        weatherWarningsUpdateRunnable = new APIReaders.WeatherWarningsRunnable(getApplicationContext()) {
            @Override
            public void onStart() {
                showProgressBar();
                super.onStart();
            }
            @Override
            public void onNegativeResult() {
                hideProgressBar();
                displayWarnings();
                PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.ERR,"Getting warnings failed.");
                super.onNegativeResult();
            }
            @Override
            public void onPositiveResult(ArrayList<WeatherWarning> warnings) {
                hideProgressBar();
                DataStorage.Updates.setLastUpdate(context,WeatherSettings.Updates.Category.WARNINGS,Calendar.getInstance().getTimeInMillis());
                PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.INFO,"Updated warnings: "+warnings.size()+" records.");
                weatherWarnings = warnings;
                for (int i=0; i<weatherWarnings.size(); i++){
                    weatherWarnings.get(i).initPolygons(context);
                }
                super.onPositiveResult(warnings);
                // finally do a sync of other parameters; if nothing is due, nothing will happen
                // warnings will be also ignored, because setLastUpdate was called.
                scheduledExecutorService.execute(new Runnable() {
                    @Override
                    public void run() {
                        ContentResolver.requestSync(MainActivity.getManualSyncRequest(context,WeatherSyncAdapter.UpdateFlags.FLAG_UPDATE_DEFAULT));
                    }
                });
                displayWarnings();
            }
        };
        WeatherSettings.saveGPSfixtime(context,0);
        // set to station, perhaps override with current location later
        ownLocation = WeatherSettings.getSetStationLocation(getApplicationContext());
        getApplication().registerActivityLifecycleCallbacks(weatherLocationManager);
        weatherLocationManager.setView(gpsProgressHolder);
        weatherLocationManager.registerCancelButton((Button) findViewById(R.id.cancel_gps));
        popupHint();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu){
        MenuInflater mi = getMenuInflater();
        mi.inflate(R.menu.weatherwarning_activity,menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem mi) {
        int item_id = mi.getItemId();
        if (item_id == R.id.menu_refresh) {
            if (mapZoomable!=null){
                zoomMapState = mapZoomable.saveZoomViewState();
            }
            PrivateLog.log(this, PrivateLog.WARNINGS,PrivateLog.INFO, "starting update of weather warnings");
            SyncRequest syncRequest = MainActivity.getManualSyncRequest(context,WeatherSyncAdapter.UpdateFlags.FLAG_UPDATE_WARNINGS);
            ContentResolver.requestSync(syncRequest);
            forceWeatherUpdateFlag = true;
            // force update or rain radar if shown
            if (!hide_rain){
                    cancelRainSlides=true;
            }
            return true;
        }
        if (item_id==R.id.hide_rain) {
            if ((hide_rain) && (hide_admin)){
                hide_rain = false;
            } else
            if ((!hide_rain) && (hide_admin)){
                hide_admin = false;
            } else
            if (!hide_rain) {
                hide_rain = true;
            }
            else {
                hide_rain = true; hide_admin = true;
            }
            if (!hide_rain){
                germany.post(new Runnable() {
                    @Override
                    public void run() {
                        startRainRadar();
                    }
                });
            } else {
                if (rainSlidesRunning){
                    cancelRainSlides=true;
                }
            }
            drawMapBitmap();
            return true;
        }
        return super.onOptionsItemSelected(mi);
    }

    public void updateActionBarLabels(){
        final SimpleDateFormat simpleDateFormat = Weather.getSimpleDateFormat(Weather.SimpleDateFormats.DATEYEARTIME);
        String update = simpleDateFormat.format(DataStorage.Updates.getLastUpdate(context,WeatherSettings.Updates.Category.WARNINGS));
        if (weatherWarnings!=null){
            actionBar.setSubtitle(update+" ("+weatherWarnings.size()+")");
        } else {
            actionBar.setSubtitle(getApplicationContext().getResources().getString(R.string.warnings_update_fail));
        }
    }

    private void displayWarnings(){
        if (weatherWarnings!=null){
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateActionBarLabels();
                }
            });
            TextView noWarnings = (TextView) findViewById(R.id.warningactivity_no_warnings);
            if (weatherWarnings.size()==0){
                noWarnings.setVisibility(View.VISIBLE);
            } else {
                noWarnings.setVisibility(View.GONE);
            }
            TextView warningsDeprecated = (TextView) findViewById(R.id.warningactivity_warnings_deprecated);
            if (DataStorage.Updates.isSyncDue(context,WeatherSettings.Updates.Category.WARNINGS)){
                warningsDeprecated.setVisibility(View.VISIBLE);
            } else {
                warningsDeprecated.setVisibility(View.GONE);
            }
        }
        WeatherWarnings.getWarningsForLocationRunnable getWarningsForLocationRunnable = new WeatherWarnings.getWarningsForLocationRunnable(getApplicationContext(),null,null) {
            @Override
            public void onResult(ArrayList<WeatherWarning> result) {
                localWarnings = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        weatherList = (ListView) findViewById(R.id.warningactivity_listview);
                        weatherWarningAdapter = new WeatherWarningAdapter(getBaseContext(),weatherWarnings,scheduledExecutorService);
                        weatherWarningAdapter.setLocalWarnings(localWarnings);
                        weatherList.setAdapter(weatherWarningAdapter);
                        weatherList.setSelection(WeatherWarnings.getFirstWarningPosition(weatherWarnings,localWarnings));
                        weatherList.invalidate();
                    }
                });
            }
        };
        scheduledExecutorService.execute(getWarningsForLocationRunnable);
        if (weatherWarnings!=null){
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    displayMap();
                }
            });
        }
    }

    public static class PlotPoint{
        float x;
        float y;
    }

    public PlotPoint getPlotPoint(float lon, float lat){
        PlotPoint plotPoint = new PlotPoint();
        plotPoint.x = (float) mercatorProjectionTile.getXPixel(lon);
        plotPoint.y = (float) mercatorProjectionTile.getYPixel(lat);
        return plotPoint;
    }

    private float getXGeo(PlotPoint plotPoint){
        float xCoord = (float) mercatorProjectionTile.getXCoord(plotPoint.x);
        return xCoord;
    }

    private float getYGeo(PlotPoint plotPoint){
        float yCoord = (float) mercatorProjectionTile.getYCoord(plotPoint.y);
        return yCoord;
    }

    private void drawStrokedText(Canvas canvas, String text, float x, float y, Paint paint){
        Paint strokePaint = new Paint();
        strokePaint.setColor(Color.BLACK);
        strokePaint.setStyle(Paint.Style.FILL_AND_STROKE);
        strokePaint.setTypeface(Typeface.DEFAULT);
        strokePaint.setTextSize(paint.getTextSize());
        strokePaint.setAntiAlias(true);
        int shiftX = Math.max(2,germany.getWidth()/RadarMN2.getFixedRadarMapWidth(context));
        int shiftY = Math.max(2,germany.getHeight()/RadarMN2.getFixedRadarMapHeight(context));
        canvas.drawText(text,x-shiftX,y,strokePaint);
        canvas.drawText(text,x+shiftX,y,strokePaint);
        canvas.drawText(text,x,y-shiftY,strokePaint);
        canvas.drawText(text,x,y+shiftY,strokePaint);
        canvas.drawText(text,x,y,paint);
    }

    @SuppressLint("NewApi")
    private void showRainDescription(){
        // Determine the height of the info bar
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);
        int infoBarHeight = displayMetrics.heightPixels/12;
        int infoBarWidth  = Math.round(displayMetrics.widthPixels*(8f/20f)*(14f/20f));
        if (!deviceIsLandscape){
            infoBarWidth = Math.round(displayMetrics.widthPixels*(14f/20f));
            infoBarHeight = Math.round(displayMetrics.heightPixels*(1/28f)); // less than 30 to account for action bar & system elements at top of screen
        }
        // this is the result bitmap holding the color bar and the text legend
        Bitmap infoBitmap=Bitmap.createBitmap(Math.round(infoBarWidth),Math.round(infoBarHeight), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(infoBitmap);
        // this is the color bar, holding 50% of the space
        // original bitmap resolution of the info bar is 824x34 pixels
        float targetHeightColorBar   = infoBarHeight/2f;
        float targetWidthColorBar    = infoBarWidth;
        Bitmap radarinfobarResourceBitmap;
        radarinfobarResourceBitmap = Bitmap.createScaledBitmap(BitmapFactory.decodeResource(getResources(),WeatherIcons.getIconResource(getApplicationContext(),WeatherIcons.RADARINFOBAR)),Math.round(targetWidthColorBar),Math.round(targetHeightColorBar),true);
        Paint rpaint = new Paint();
        rpaint.setStyle(Paint.Style.FILL);
        canvas.drawBitmap(radarinfobarResourceBitmap,0,infoBitmap.getHeight()-radarinfobarResourceBitmap.getHeight(),rpaint);
        Paint radarTextPaint = new Paint();
        radarTextPaint.setTypeface(Typeface.DEFAULT);
        radarTextPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        radarTextPaint.setAntiAlias(true);
        radarTextPaint.setFakeBoldText(true);
        int textsize = infoBarHeight/2;
        radarTextPaint.setTextSize(textsize);
        // fix the textsize to prevent overlapping of labels if the total width available is small
        String rainRadarLabelText1 = getResources().getString(R.string.radar_rain1);
        String rainRadarLabelText2 = getResources().getString(R.string.radar_rain2);
        String rainRadarLabelText3 = getResources().getString(R.string.radar_rain3);
        String rainRadarLabelText4 = getResources().getString(R.string.radar_rain4);
        int widestRadarLabel = 1; String widestRadarLabelText = rainRadarLabelText1;
        if (radarTextPaint.measureText(rainRadarLabelText2)>radarTextPaint.measureText(rainRadarLabelText1)){
            widestRadarLabel = 2; widestRadarLabelText = rainRadarLabelText2;
        }
        if (radarTextPaint.measureText(rainRadarLabelText3)>radarTextPaint.measureText(widestRadarLabelText)){
            widestRadarLabel = 3; widestRadarLabelText = rainRadarLabelText3;
        }
        if (radarTextPaint.measureText(rainRadarLabelText4)>radarTextPaint.measureText(widestRadarLabelText)){
            widestRadarLabel = 4; widestRadarLabelText = rainRadarLabelText4;
        }
        int widestLabelWidth = Math.round(radarTextPaint.measureText(widestRadarLabelText));
        while ((textsize>6) && (infoBarWidth-(radarTextPaint.measureText(widestRadarLabelText)*6)<1)){
            textsize--;
            radarTextPaint.setTextSize(textsize);
        }
        radarTextPaint.setColor(Color.WHITE);
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        Date rainSlideDate = new Date(rainSlidesStartTime+(nextRainSlide)*APIReaders.RadarMNSetGeoserverRunnable.TIMESTEP_5MINUTES);
        String radartime = simpleDateFormat.format(rainSlideDate);
        float ff=1.1f;
        if (validSlideSetObtained) {
            rainSlideTime.setTextColor(Color.WHITE);
            if (Calendar.getInstance().getTimeInMillis() > rainSlidesStartTime + +1000*60*60*1.5f){
                rainSlideTime.setTextColor(0xfffa7712);
            }
        } else {
            rainSlideTime.setTextColor(Color.YELLOW);
        }
        rainSlideTime.setText(radartime);
        radarTextPaint.setColor(Radarmap.RAINCOLORS[2]);
        drawStrokedText(canvas,getResources().getString(R.string.radar_rain1),infoBarWidth*0.1f,infoBitmap.getHeight()-radarinfobarResourceBitmap.getHeight()*ff,radarTextPaint);
        radarTextPaint.setColor(Radarmap.RAINCOLORS[7]);
        drawStrokedText(canvas,getResources().getString(R.string.radar_rain2),infoBarWidth*0.3f,infoBitmap.getHeight()-radarinfobarResourceBitmap.getHeight(),radarTextPaint);
        radarTextPaint.setColor(Radarmap.RAINCOLORS[11]);
        drawStrokedText(canvas,getResources().getString(R.string.radar_rain3),infoBarWidth*0.6f,infoBitmap.getHeight()-radarinfobarResourceBitmap.getHeight(),radarTextPaint);
        radarTextPaint.setColor(Radarmap.RAINCOLORS[16]);
        drawStrokedText(canvas,getResources().getString(R.string.radar_rain4),infoBarWidth*0.8f,infoBitmap.getHeight()-radarinfobarResourceBitmap.getHeight(),radarTextPaint);
        if (rainDescription!=null){
            rainDescription.setImageBitmap(infoBitmap);
        }
    }

    private void clearRainDescription(){
        if (rainDescription==null){
            rainDescription = (ImageView) findViewById(R.id.warningactivity_mapinfo);
        }
        if (rainDescription!=null){
            rainDescription.setImageDrawable(null);
        }
    }

    private Bitmap loadBitmapMap(int res_id){
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        Bitmap bitmap = Bitmap.createScaledBitmap(BitmapFactory.decodeResource(getResources(),res_id),RadarMN2.getFixedRadarMapWidth(context),RadarMN2.getFixedRadarMapHeight(context),false);
        return bitmap;
    }

    private void updateRainSlideProgressBar(final int progress){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                rainSlideProgressBar.setVisibility(View.VISIBLE);
                rainSlideProgressBar.setProgress(progress);
                rainSlideProgressBar.invalidate();
                rainSlideProgressBarText.setVisibility(View.VISIBLE);
                rainSlideProgressBarText.setText(Math.round((float) progress/(float) (APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE-1)*100f)+"%");
                rainSlideProgressBarText.invalidate();
            }
        });
    }

    private void drawRadarSlide(final int count){
        if (APIReaders.RadarMNSetGeoserverRunnable.radarCacheFileValid(context,count)) {
            radarBitmap.eraseColor(Color.TRANSPARENT);
            Canvas radarCanvas = new Canvas(radarBitmap);
            Bitmap slideBitmap = RadarMN2.getScaledBitmap(context,count);
            final Paint radarPaint = new Paint();
            radarPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
            if (slideBitmap!=null){
                radarCanvas.drawBitmap(slideBitmap,0,0,radarPaint);
            }
            if (!hide_rain) {
                drawMapBitmap();
            }
        } else {
            // do nothing
        }
        lastDrawnRainSlide=count;
    }

    private void drawMapBitmap(){
        visibleBitmap = germanyBitmap.copy(Bitmap.Config.ARGB_8888,true);
        Canvas canvas = new Canvas(visibleBitmap);
        final Paint cp = new Paint();
        cp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
        canvas.drawBitmap(warningsBitmap,0,0,cp);
        if ((!hide_admin)){
            if (administrativeBitmap==null){
                administrativeBitmap = getAdministrativeBitmap(context,germanyBitmap.getWidth(),germanyBitmap.getHeight(),WeatherSettings.getAreaTypeArray(context));
            }
            cp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
            canvas.drawBitmap(administrativeBitmap,0,0,cp);
        }
        if ((!hide_rain) && (radarBitmap!=null)){
            cp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
            canvas.drawBitmap(radarBitmap, 0,0,cp);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showRainDescription();
                }
            });
        } else {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    clearRainDescription();
                }
            });
        }
        if (WeatherSettings.displayWindDistance(context)){
            drawAdvancedWindStencil(canvas);
        }
        mapZoomable.updateBitmap(visibleBitmap);
        visibleBitmap.recycle();
    }

    private void drawWindIcon(){
        final Context context = getApplicationContext();
        final ImageView windicon = (ImageView) findViewById(R.id.warningactivity_windicon);
        Runnable windRunnable = new Runnable() {
            @Override
            public void run() {
                final RelativeLayout windiconContainer = (RelativeLayout) findViewById(R.id.warningactivity_windicon_container);
                if (windiconContainer!=null){
                    if (WeatherSettings.displayWindInRadar(getApplicationContext())){
                        windiconContainer.setVisibility(View.VISIBLE);
                        final ImageView windiconBackground = (ImageView) findViewById(R.id.warningactivity_windicon_background);
                        CurrentWeatherInfo currentWeatherInfo = Weather.getCurrentWeatherInfo(context);
                        if (currentWeatherInfo!=null){
                            final Bitmap windiconBitmap = currentWeatherInfo.currentWeather.getWindSymbol(getApplicationContext(),WeatherSettings.getWindDisplayType(getApplicationContext()),false);
                            ThemePicker.applyColor(context,windiconBitmap,false);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    windiconBackground.setImageResource(R.drawable.blue);
                                    windiconBackground.setColorFilter(ThemePicker.getColorPrimary(context),PorterDuff.Mode.SRC_IN);
                                    windicon.setImageBitmap(windiconBitmap);
                                }
                            });
                        }
                    } else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                windiconContainer.setVisibility(View.INVISIBLE);
                            }
                        });

                    }
                }
            }
        };
        scheduledExecutorService.execute(windRunnable);
    }

    private void drawAdvancedWindStencil(Canvas canvas){
        int windPathTime = WeatherSettings.getWindDistanceHours(context)*60;
        int arcSize = WeatherSettings.getWindDistanceArc(context)/2;
        Paint windLinePaint =  new Paint();
        windLinePaint.setStyle(Paint.Style.STROKE);
        windLinePaint.setStrokeWidth(3);
        windLinePaint.setColor(Color.BLACK);
        if (mapZoomable!=null){
            windLinePaint.setStrokeWidth(mapZoomable.scaleFactor*3f);
        }
        float positionX = (float) mercatorProjectionTile.getXPixel(ownLocation.longitude);
        float positionY = (float) mercatorProjectionTile.getYPixel(ownLocation.latitude);
        // testing enhanced wind arrow
        CurrentWeatherInfo currentWeatherInfo = Weather.getCurrentWeatherInfo(context);
        ArrayList<Weather.WindData> windDataList = currentWeatherInfo.getWindForecast(windPathTime/60+1);
        final int windPathLength = windPathTime/15; // number of arcs drawn, corresponding to 15 mins rain progress
        final double ARCSTEPS = 15;
        final DashPathEffect dashPathEffect = new DashPathEffect(new float[]{1f,2f},0f);
        float[] windPathX = new float[windPathLength+1];
        float[] windPathY = new float[windPathLength+1];
        windPathX[0] = positionX; windPathY[0] = positionY;
        double distance = 0; // in km from the current location
        for (int i=1; i<=windPathLength; i++){
            windLinePaint.setStrokeWidth(1f);
            if (i % 4 == 0){
                windLinePaint.setPathEffect(null);
            } else {
                windLinePaint.setPathEffect(dashPathEffect);
            }
            Weather.WindData windData = windDataList.get(i/4);
            double windDirection = windData.getDirection()+180;
            if (windDirection>360){
                windDirection = windDirection - 360;
            }
            double windSpeed = windData.getSpeedKmh(); // in km/h
            double windWay15MinInKm = windSpeed / 4;
            distance = distance + windWay15MinInKm;
            double[] pathTarget = RadarMN2.getDestinationCoordinates((float) ownLocation.longitude,ownLocation.latitude,windDirection,distance);
            windPathX[i] = (float) mercatorProjectionTile.getXPixel(pathTarget[0]); windPathY[i] = (float) mercatorProjectionTile.getYPixel(pathTarget[1]);
            double[] coordinates = RadarMN2.getDestinationCoordinates((float) ownLocation.longitude,ownLocation.latitude,windDirection - arcSize,distance);
            float x1 = (float) mercatorProjectionTile.getXPixel(coordinates[0]);
            float y1 = (float) mercatorProjectionTile.getYPixel(coordinates[1]);
            for (double arcAngle = windDirection - arcSize; arcAngle <= windDirection + arcSize; arcAngle = arcAngle + ARCSTEPS){
                coordinates = RadarMN2.getDestinationCoordinates((float) ownLocation.longitude,ownLocation.latitude,arcAngle,distance);
                float x2 = (float) mercatorProjectionTile.getXPixel(coordinates[0]);
                float y2 = (float) mercatorProjectionTile.getYPixel(coordinates[1]);
                canvas.drawLine(x1,y1,x2,y2,windLinePaint);
                x1 = x2; y1 = y2;
            }
            windLinePaint.setPathEffect(null);
            canvas.drawLine(windPathX[i-1], windPathY[i-1],windPathX[i], windPathY[i],windLinePaint);
        }
    }

    private boolean hideMap(){
        germany.setVisibility(View.GONE);
        germany.invalidate();
        LinearLayout.LayoutParams rllp = (LinearLayout.LayoutParams) mapcontainer.getLayoutParams();
        rllp.height=0;
        rllp.weight=0;
        mapcontainer.setLayoutParams(rllp);
        mapcontainer.invalidate();
        mapcontainer.setVisibility(View.GONE);
        LinearLayout.LayoutParams mclp = (LinearLayout.LayoutParams) map_collapsed_container.getLayoutParams();
        map_collapsed_container.setVisibility(View.VISIBLE);
        mclp.weight=1;
        map_collapsed_container.setLayoutParams(mclp);
        map_collapsed_container.invalidate();
        LinearLayout.LayoutParams lop = (LinearLayout.LayoutParams) weatherList.getLayoutParams();
        lop.weight=29;
        weatherList.setLayoutParams(lop);
        weatherList.invalidate();
        LinearLayout.LayoutParams infoContLop = (LinearLayout.LayoutParams) warningactivityMapinfoContainer.getLayoutParams();
        infoContLop.weight=0;
        return true;
    }

    private boolean showMap(){
        germany.setVisibility(View.VISIBLE);
        mapcontainer.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams rllp = (LinearLayout.LayoutParams) mapcontainer.getLayoutParams();
        rllp.height=0;
        rllp.weight=18;
        mapcontainer.setLayoutParams(rllp);
        mapcontainer.invalidate();
        LinearLayout.LayoutParams mclp = (LinearLayout.LayoutParams) map_collapsed_container.getLayoutParams();
        mclp.height=0;
        mclp.weight=0;
        map_collapsed_container.setLayoutParams(mclp);
        map_collapsed_container.invalidate();
        map_collapsed_container.setVisibility(View.GONE);
        LinearLayout.LayoutParams lop = (LinearLayout.LayoutParams) weatherList.getLayoutParams();
        lop.height=0;
        lop.weight=11;
        weatherList.setLayoutParams(lop);
        weatherList.invalidate();
        LinearLayout.LayoutParams infoContLop = (LinearLayout.LayoutParams) warningactivityMapinfoContainer.getLayoutParams();
        infoContLop.weight=1;
        return true;
    }
    
    private void displayMap(){
        germanyBitmap = loadBitmapMap(WeatherIcons.getIconResource(getApplicationContext(),WeatherIcons.GERMANY));
        warningsBitmap = Bitmap.createBitmap(germanyBitmap.getWidth(),germanyBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        warningsBitmap.eraseColor(Color.TRANSPARENT);
        radarBitmap = Bitmap.createBitmap(germanyBitmap.getWidth(),germanyBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        radarBitmap.eraseColor(Color.TRANSPARENT);
        MAP_PIXEL_HEIGHT = warningsBitmap.getHeight();
        MAP_PIXEL_WIDTH  = warningsBitmap.getWidth();
        polygoncache = new ArrayList<Polygon>();
        excluded_polygoncache = new ArrayList<Polygon>();
        final Canvas canvas = new Canvas(warningsBitmap);
        ArrayList<WeatherWarning> drawWarnings = (ArrayList<WeatherWarning>) weatherWarnings.clone();
        Collections.sort(drawWarnings);
        Collections.reverse(drawWarnings);
        for (int warning_counter=0; warning_counter<drawWarnings.size(); warning_counter++){
            WeatherWarning warning = drawWarnings.get(warning_counter);
            for (int polygon_counter=0; polygon_counter<warning.polygonlist.size(); polygon_counter++){
                float[] polygonX = warning.polygonlist.get(polygon_counter).polygonX;
                float[] polygonY = warning.polygonlist.get(polygon_counter).polygonY;
                // add polygon to cache
                polygoncache.add(new Polygon(polygonX,polygonY,warning.identifier));
                if (polygonX.length>0){
                    Path path = new Path();
                    PlotPoint plotPoint = getPlotPoint(polygonX[0],polygonY[0]);
                    path.moveTo(plotPoint.x, plotPoint.y);
                    for (int vertex_count=1; vertex_count<polygonX.length; vertex_count++){
                        plotPoint = getPlotPoint(polygonX[vertex_count],polygonY[vertex_count]);
                        path.lineTo(plotPoint.x, plotPoint.y);
                    }
                    Paint polypaint = new Paint();
                    polypaint.setColor(warning.getWarningColor());
                    polypaint.setAlpha(128);
                    polypaint.setStyle(Paint.Style.FILL_AND_STROKE);
                    canvas.drawPath(path,polypaint);
                }
            }
            // draw black excluded polygons after other polygons were drawn
            for (int polygon_counter=0; polygon_counter<warning.excluded_polygonlist.size(); polygon_counter++){
                float[] polygonX = warning.excluded_polygonlist.get(polygon_counter).polygonX;
                float[] polygonY = warning.excluded_polygonlist.get(polygon_counter).polygonY;
                // add excluded-polygon to cache
                excluded_polygoncache.add(new Polygon(polygonX,polygonY,warning.identifier));
                if (polygonX.length>0){
                    Path path = new Path();
                    PlotPoint plotPoint = getPlotPoint(polygonX[0],polygonY[0]);
                    path.moveTo(plotPoint.x,plotPoint.y);
                    for (int vertex_count=1; vertex_count<polygonX.length; vertex_count++){
                        plotPoint = getPlotPoint(polygonX[vertex_count],polygonY[vertex_count]);
                        path.lineTo(plotPoint.x,plotPoint.y);
                    }
                    Paint polypaint = new Paint();
                    Color color = new Color();
                    polypaint.setColor(Color.TRANSPARENT);
                    polypaint.setStyle(Paint.Style.FILL_AND_STROKE);
                    canvas.drawPath(path,polypaint);
                }
            }
        }
        float pinSize = WeatherSettings.getMapPinSize(context)/2f;
        int pinSizePixels = Math.round(18*this.getApplicationContext().getResources().getDisplayMetrics().density*pinSize);
        Bitmap pinBitmap = Bitmap.createScaledBitmap(BitmapFactory.decodeResource(getResources(),R.mipmap.pin),pinSizePixels,pinSizePixels,false);
        PlotPoint pinPoint = getPlotPoint((float) ownLocation.longitude, (float) ownLocation.latitude);
        float pinX = pinPoint.x; float pinY = pinPoint.y;
        // drawAdvancedWindStencil(canvas,145,300);
        drawWindIcon();
        // set close listener
        ImageView closeImageview = (ImageView) findViewById(R.id.closeicon_map);
        if (closeImageview != null){
            closeImageview.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent motionEvent) {
                    return hideMap();
                }
            });
        }
        // set listener
        germany = (ImageView) findViewById(R.id.warningactivity_map);
        gestureDetector = new GestureDetector(this,new MapGestureListener());
        //mapZoomable = new ZoomableImageView(getApplicationContext(),germany,germanyBitmap) {
        // testing
        mapZoomable = new ZoomableImageView(getApplicationContext(),germany,warningsBitmap){
            @Override
            public void onGestureFinished(float scaleFactor, float lastXtouch, float lastYtouch, float xFocus, float yFocus, float xFocusRelative, float yFocusRelative, RectF currentlyVisibleArea){
                final PlotPoint plotPoint = new PlotPoint();
                plotPoint.x = lastXtouch;
                plotPoint.y = lastYtouch;
                Runnable tapRunnable = new Runnable() {
                    @Override
                    public void run() {
                        checkForTapInPolygonWarning(getXGeo(plotPoint),getYGeo(plotPoint));
                    }
                };
                scheduledExecutorService.execute(tapRunnable);
            }
            @Override
            public void onLongPress(float scaleFactor, float lastXtouch, float lastYtouch, float xFocus, float yFocus, float xFocusRelative, float yFocusRelative, RectF currentlyVisibleArea) {
                // currently does nothing; reserved for a future long press event
            }
        };
        if (RadarMN2.getScaleFactor(context)>1){
            mapZoomable.setScaleRange(0.12f,1f);
        }
        if (zoomMapState!=null){
            PrivateLog.log(getApplicationContext(),PrivateLog.WARNINGS,PrivateLog.INFO,"Restoring map state...");
            mapZoomable.restoreZoomViewState(zoomMapState);
        } else {
            PrivateLog.log(getApplicationContext(),PrivateLog.WARNINGS,PrivateLog.INFO,"Not restoring map map state because data is null");
        }
        drawMapBitmap();
        // add the pin sprite
        if (pinSize>0){
            mapZoomable.addSpite(pinBitmap,pinX,pinY-pinBitmap.getHeight(),ZoomableImageView.SPRITEFIXPOINT.BOTTOM_LEFT,null);
            //
        }
        mapZoomable.redrawBitmap();
        mapTouchListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                mapZoomable.onTouchEvent(motionEvent);
                return true;
            };
        };
        germany.setOnTouchListener(mapTouchListener);

    }

    class MapGestureListener extends GestureDetector.SimpleOnGestureListener{
        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            return super.onSingleTapUp(e);
        }

        @Override
        public void onLongPress(MotionEvent e) {
            super.onLongPress(e);
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            return super.onScroll(e1, e2, distanceX, distanceY);
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            return super.onFling(e1, e2, velocityX, velocityY);
        }

        @Override
        public void onShowPress(MotionEvent e) {
            super.onShowPress(e);
        }

        @Override
        public boolean onDown(MotionEvent e)
        {
            // catch collapse-button-press prior to map-selection
            // perform this only if NOT in landscape layout
            if ((!deviceIsLandscape) && (map_collapsed_container!=null) && (germany!=null) && (weatherList!=null)){
                float button_border_right  = (float) (germany.getMeasuredWidth() * 0.127427184);
                float button_border_bottom = (float) (germany.getMeasuredHeight() * 0.10041841);
                if ((e.getX()<button_border_right) && (e.getY()<button_border_bottom)){
                    return hideMap();
                }
            }
            return false;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            return super.onDoubleTap(e);
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            return super.onDoubleTapEvent(e);
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            return super.onSingleTapConfirmed(e);
        }

        @Override
        public boolean onContextClick(MotionEvent e) {
            return super.onContextClick(e);
        }
    }

    private void checkForTapInPolygonWarning(float x_geo, float y_geo){

        if (polygoncache!=null){
            int position = 0;
            // first check if pointer is in excluded polygon; it is more efficient to do this first.
            if (excluded_polygoncache!=null){
                while (position<excluded_polygoncache.size()){
                    if (excluded_polygoncache.get(position).isInPolygon(x_geo,y_geo)){
                        // break (& do nothing) if pointer is in excluded polygon.
                        return;
                    }
                    position++;
                }
            }
            position = 0;
            while (position<polygoncache.size()){
                if (polygoncache.get(position).isInPolygon(x_geo,y_geo)){
                    jumpListViewToSelection(polygoncache.get(position));
                    return;
                }
                position++;
            }
        }
    }

    private void jumpListViewToSelection(Polygon polygon){
        int position=0;
        while (position<weatherWarnings.size()){
            if (weatherWarnings.get(position).identifier.equals(polygon.identifier_link)){
                break;
            }
            position++;
        }
        if (weatherList != null){
            final int jumpPosition = position;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    weatherList.setSelection(jumpPosition);
                }
            });
        }
    }

    private boolean isDeviceLandscape(){
        Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        int orientation = display.getRotation();
        if ((orientation==Surface.ROTATION_0)|| (orientation==Surface.ROTATION_180)){
            return false;
        }
        return true;
    }

    private void showProgressBar(){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ProgressBar progressBar = (ProgressBar) findViewById(R.id.warningactivity_progressbar);
                if (progressBar!=null){
                    progressBar.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void hideProgressBar(){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ProgressBar progressBar = (ProgressBar) findViewById(R.id.warningactivity_progressbar);
                if (progressBar!=null){
                    progressBar.setVisibility(View.INVISIBLE);
                }
            }
        });
    }

    private void registerForBroadcast(){
        IntentFilter filter = new IntentFilter();
        filter.addAction(WEATHER_WARNINGS_UPDATE);
        filter.addAction(MainActivity.MAINAPP_HIDE_PROGRESS);
        filter.addAction(ACTION_RAINRADAR_UPDATE);
        registerReceiver(receiver,filter);
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Override
    public void onRequestPermissionsResult(int permRequestCode, String perms[], int[] grantRes){
        Boolean hasLocationPermission = false;
        for (int i=0; i<grantRes.length; i++){
            if ((perms[i].equals(Manifest.permission.ACCESS_FINE_LOCATION)) && (grantRes[i]== PackageManager.PERMISSION_GRANTED)){
                hasLocationPermission = true;
            }
        }
        if (permRequestCode == WeatherLocationManager.PERMISSION_CALLBACK_LOCATION){
            if (hasLocationPermission){
                if (weatherLocationManager!=null){
                    weatherLocationManager.checkLocation();
                }
            } else {
                if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)){
                    showLocationPermissionsRationale();
                }
            }
        }
    }

    private void showSimpleLocationAlert(String text){
        AlertDialog.Builder builder = new AlertDialog.Builder(this,0);
        builder.setTitle(getApplicationContext().getResources().getString(R.string.geoinput_title));
        Drawable drawable = new BitmapDrawable(getResources(),WeatherIcons.getIconBitmap(context,WeatherIcons.IC_GPS_FIXED,false));
        builder.setIcon(drawable);
        builder.setMessage(text);
        builder.setPositiveButton(R.string.alertdialog_ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
            }
        });
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
    }

    private void showLocationPermissionsRationale(){
        showSimpleLocationAlert(getApplicationContext().getResources().getString(R.string.geoinput_rationale));
    }

    public Bitmap getAdministrativeBitmap(Context context, int targetWidth, int targetHeight, int[] types){
        Bitmap resultBitmap = Bitmap.createBitmap(targetWidth,targetHeight, Bitmap.Config.ARGB_8888);
        resultBitmap.eraseColor(Color.TRANSPARENT);
        Canvas canvas = new Canvas(resultBitmap);
        for (int type=0; type<types.length; type++){
            ArrayList<Areas.Area> allAreas = Areas.getAreas(context, types[type]);
            Paint areaPaint = new Paint();
            areaPaint.setColor(Color.BLACK);
            areaPaint.setAlpha(96);
            areaPaint.setStyle(Paint.Style.STROKE);
            if (types[type]==Areas.Area.Type.SEE){
                areaPaint.setColor(Color.CYAN);
            }
            if (types[type]==Areas.Area.Type.KUESTE){
                areaPaint.setColor(Color.YELLOW);
            }
            if (types[type]==Areas.Area.Type.GEMEINDE){
                areaPaint.setColor(Color.GRAY);
            }
            if (types[type]==Areas.Area.Type.BUNDESLAND){
                areaPaint.setColor(Color.BLUE);
                areaPaint.setStrokeWidth(2);
            }
            for (int i=0; i<allAreas.size(); i++){
                Areas.Area cellArea = allAreas.get(i);
                ArrayList<Polygon> areaPolygons = cellArea.polygons;
                for (int p=0; p<areaPolygons.size(); p++){
                    Polygon areaPolygon = areaPolygons.get(p);
                    Path path = new Path();
                     PlotPoint plotPoint = getPlotPoint(areaPolygon.polygonX[0],areaPolygon.polygonY[0]);
                    path.moveTo(plotPoint.x, plotPoint.y);
                    for (int v=0; v<areaPolygon.polygonX.length; v++){
                        plotPoint = getPlotPoint(areaPolygon.polygonX[v],areaPolygon.polygonY[v]);
                        path.lineTo(plotPoint.x, plotPoint.y);
                    }
                    canvas.drawPath(path,areaPaint);
                }
            }
        }
        return resultBitmap;
    }

    public void popupHint(){
        final int[] hintTimes = {20,3,6,9};
        final int count = WeatherSettings.getHintCounter2(context);
        if ((count==hintTimes[1]) || (count==hintTimes[2]) || (count==hintTimes[3])){
            final RelativeLayout anchorView = (RelativeLayout) findViewById(R.id.warningactivity_main_relative_container);
            if (anchorView!=null){
                anchorView.post(new Runnable() {
                    @Override
                    public void run() {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                DisplayMetrics displayMetrics = new DisplayMetrics();
                                getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                                int displayWidth  = Math.round(displayMetrics.widthPixels);
                                int displayHeight = Math.round(displayMetrics.heightPixels);
                                final boolean isLandscape = displayWidth>displayHeight;
                                LayoutInflater layoutInflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                                final View popupView = layoutInflater.inflate(R.layout.popup_hint1,null);
                                // set correct theme textcolors
                                TextView textView1 = (TextView) popupView.findViewById(R.id.hint1_text);
                                textView1.setTextColor(Color.WHITE);
                                // register click callbacks
                                Button bottonOk = (Button) popupView.findViewById(R.id.hint1_button);
                                bottonOk.setTextColor(Color.WHITE);
                                bottonOk.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View view) {
                                        if (hintPopupWindow!=null){
                                            hintPopupWindow.dismiss();
                                        }
                                    }
                                });
                                CheckBox checkNo = (CheckBox) popupView.findViewById(R.id.hint1_checkbox);
                                checkNo.setTextColor(Color.WHITE);
                                checkNo.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                                    @Override
                                    public void onCheckedChanged(CompoundButton compoundButton, boolean checked) {
                                        if (checked){
                                            WeatherSettings.setHintCounter1(context,hintTimes[0]);
                                            WeatherSettings.setHintCounter2(context,hintTimes[0]);
                                        } else {
                                            WeatherSettings.setHintCounter1(context,0);
                                            WeatherSettings.setHintCounter2(context,0);
                                        }
                                    }
                                });
                                int width  = Math.round(displayWidth * 0.8f);
                                int height = Math.round(displayHeight * 0.26f);
                                if (isLandscape){
                                    height = Math.round(displayHeight * 0.4f);
                                }
                                ImageView imageView = (ImageView) popupView.findViewById(R.id.hint1_image);
                                if (count==hintTimes[1]){
                                    textView1.setText(context.getResources().getString(R.string.hint_1));
                                    imageView.setImageResource(R.drawable.radar_hint);
                                    height = Math.round(displayHeight * 0.47f);
                                    if (isLandscape){
                                        height = Math.round(displayHeight * 0.6f);
                                    }
                                }
                                if (count==hintTimes[2]){
                                    textView1.setText(context.getResources().getString(R.string.welcome_s3_text1));
                                    imageView.setImageResource(R.drawable.collapse_hint);
                                }
                                if (count==hintTimes[3]){
                                    textView1.setText(context.getResources().getString(R.string.welcome_s3_text2));
                                    imageView.setImageResource(R.drawable.expand_hint);
                                }
                                hintPopupWindow = new PopupWindow(popupView,width,height,true);
                                hintPopupWindow.showAtLocation(anchorView,Gravity.CENTER,0,0);
                                hintPopupWindow.setOnDismissListener(new PopupWindow.OnDismissListener() {
                                    @Override
                                    public void onDismiss() {
                                        displayOsmNotice();
                                    }
                                });
                            }
                        });
                    }
                });
            }
        }
        if (count<hintTimes[0]){
            int newCount = count + 1;
            WeatherSettings.setHintCounter2(context,newCount);
        }
    }

    public void displayOsmNotice(){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (germany==null){
                    germany = (ImageView) findViewById(R.id.warningactivity_map);
                }
                // this is to display the osm notice properly in the bottom-right map corner as a textview aligned to the
                // right border of the bitmap inside the imageview.
                germany.post(new Runnable() {
                    @Override
                    public void run() {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // get the scale matrix from the imageview to calculate the bitmap size inside the imageview
                                float[] matrix = new float[9];
                                germany.getImageMatrix().getValues(matrix);
                                float scaledMapWidth=germany.getDrawable().getIntrinsicWidth()*matrix[Matrix.MSCALE_X];
                                float scaledMapHeight=germany.getDrawable().getIntrinsicHeight()*matrix[Matrix.MSCALE_Y];
                                WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                                DisplayMetrics displayMetrics = new DisplayMetrics();
                                windowManager.getDefaultDisplay().getMetrics(displayMetrics);
                                // calculate the offset of the textview
                                int marginRight = Math.round((displayMetrics.widthPixels-scaledMapWidth)/2);
                                // tweak offset for landscape mode
                                if (deviceIsLandscape){
                                    RelativeLayout warningactivityLeftcontainer = (RelativeLayout) findViewById(R.id.warningactivity_leftcontainer);
                                    marginRight = Math.round((warningactivityLeftcontainer.getWidth() - scaledMapWidth)/2);
                                }
                                // create the textview
                                TextView newTextView = new TextView(context);
                                // underline the text without the (c)
                                String osmString = context.getResources().getString(R.string.map_attribution);
                                if (WeatherSettings.appReleaseIsUserdebug()){
                                    osmString = osmString + " " +context.getResources().getString(R.string.map_attribution_link);
                                }
                                SpannableString spannableString = new SpannableString(osmString);
                                spannableString.setSpan(new UnderlineSpan(),2,spannableString.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                                newTextView.setText(spannableString);
                                newTextView.setAutoLinkMask(1);
                                newTextView.setTextSize(12);
                                if (!WeatherSettings.appReleaseIsUserdebug()){
                                    newTextView.setTextSize(10);
                                }
                                newTextView.setVisibility(View.VISIBLE);
                                newTextView.setTextColor(ThemePicker.getColor(context,ThemePicker.ThemeColor.CYAN));
                                newTextView.setBackgroundColor(ThemePicker.getColor(context,ThemePicker.ThemeColor.PRIMARYLIGHT));
                                newTextView.setPadding(2,1,2,1);
                                RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                                layoutParams.setMargins(2,1,marginRight,1);
                                layoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                                layoutParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                                newTextView.setLayoutParams(layoutParams);
                                RelativeLayout warningactivity_mapcontainer = (RelativeLayout) findViewById(R.id.warningactivity_mapcontainer);
                                final int newTextViewId = View.generateViewId();
                                newTextView.setId(newTextViewId);
                                warningactivity_mapcontainer.addView(newTextView);
                                // make the textview clickable to open the license link
                                newTextView.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        final TextView newTV = (TextView) findViewById(newTextViewId);
                                        newTV.setOnClickListener(new View.OnClickListener() {
                                            @Override
                                            public void onClick(View view) {
                                                String osmUri = "https://openstreetmap.org/copyright";
                                                Intent openLicenseIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(osmUri));
                                                try {
                                                    startActivity(openLicenseIntent);
                                                } catch (ActivityNotFoundException e){
                                                    Toast.makeText(context,osmUri,Toast.LENGTH_LONG).show();
                                                }
                                            }
                                        });
                                        // make the textview disappear after 7 sec;
                                        // in debug builds, the textview does not disappear
                                        if (!WeatherSettings.appReleaseIsUserdebug()){
                                            newTV.postDelayed(new Runnable() {
                                                @Override
                                                public void run() {
                                                    runOnUiThread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            newTV.setVisibility(View.GONE);
                                                            newTV.setOnClickListener(null);
                                                        }
                                                    });
                                                }
                                            },CREDENTIALS_FADE_TIME_DELAY);
                                        }
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });
    }

}
