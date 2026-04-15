package com.zcshou.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.baidu.mapapi.model.LatLng;
import com.elvishew.xlog.XLog;
import com.zcshou.gogogo.MainActivity;
import com.zcshou.gogogo.R;
import com.zcshou.joystick.JoyStick;
import com.zcshou.utils.MapUtils;

public class ServiceGo extends Service {
    // 定位相关变量
    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 55.0D;
    public static final float DEFAULT_BEA = 0.0F;
    private double mCurLat = DEFAULT_LAT;
    private double mCurLng = DEFAULT_LNG;
    private double mCurAlt = DEFAULT_ALT;
    private float mCurBea = DEFAULT_BEA;
    private double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
    private static final int HANDLER_MSG_ID = 0;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";
    private LocationManager mLocManager;
    private HandlerThread mLocHandlerThread;
    private Handler mLocHandler;
    private boolean isStop = false;
    // 通知栏消息
    private static final int SERVICE_GO_NOTE_ID = 1;
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = "ShowJoyStick";
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = "HideJoyStick";
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private static final String SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE";
    private NoteActionReceiver mActReceiver;
    // 摇杆相关
    private JoyStick mJoyStick;
    // 轨迹相关
    private List<LatLng> mTrajectoryPoints = new ArrayList<>(); // 轨迹点列表
    private boolean isTrajectoryMode = false; // 是否为轨迹移动模式
    private boolean isLoopTrajectory = true; // 是否循环轨迹
    private int mCurrentPointIndex = 0; // 当前轨迹点索引
    private double mTrajectoryProgress = 0.0; // 轨迹点间的进度
    // 随机偏移相关
    private boolean isRandomOffset = false; // 是否启用随机偏移
    private double maxLonOffset = 10.0; // 经度最大偏移距离（米）
    private double maxLatOffset = 10.0; // 纬度最大偏移距离（米）
    // 速度随机偏移相关
    private boolean isSpeedRandomOffset = false; // 是否启用速度随机偏移
    private double mBaseSpeed = 1.2; // 基础速度（用于随机偏移）
    private int mSpeedOffsetCounter = 0; // 速度偏移计数器
    private boolean mIsSpeedIncreasing = true; // 速度是否正在增加

    private final ServiceGoBinder mBinder = new ServiceGoBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mLocManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        removeTestProviderNetwork();
        addTestProviderNetwork();

        removeTestProviderGPS();
        addTestProviderGPS();

        initGoLocation();

        initNotification();

        initJoyStick();

        // 初始化随机偏移设置
        initRandomOffset();
    }

    // 初始化随机偏移设置
    private void initRandomOffset() {
        android.content.SharedPreferences sharedPreferences = android.preference.PreferenceManager.getDefaultSharedPreferences(this);
        isRandomOffset = sharedPreferences.getBoolean("setting_random_offset", false);
        isSpeedRandomOffset = sharedPreferences.getBoolean("setting_speed_random_offset", false);
        String max_offset_default = getResources().getString(R.string.setting_random_offset_default);
        maxLonOffset = Double.parseDouble(Objects.requireNonNull(sharedPreferences.getString("setting_lon_max_offset", max_offset_default)));
        maxLatOffset = Double.parseDouble(Objects.requireNonNull(sharedPreferences.getString("setting_lat_max_offset", max_offset_default)));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mCurLng = intent.getDoubleExtra(MainActivity.LNG_MSG_ID, DEFAULT_LNG);
        mCurLat = intent.getDoubleExtra(MainActivity.LAT_MSG_ID, DEFAULT_LAT);
        mCurAlt = intent.getDoubleExtra(MainActivity.ALT_MSG_ID, DEFAULT_ALT);

        mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        isStop = true;
        mLocHandler.removeMessages(HANDLER_MSG_ID);
        mLocHandlerThread.quit();

        mJoyStick.destroy();

        removeTestProviderNetwork();
        removeTestProviderGPS();

        unregisterReceiver(mActReceiver);
        stopForeground(STOP_FOREGROUND_REMOVE);

        super.onDestroy();
    }

    private void initNotification() {
        mActReceiver = new NoteActionReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        registerReceiver(mActReceiver, filter);

        NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, SERVICE_GO_NOTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (notificationManager != null) {
            notificationManager.createNotificationChannel(mChannel);
        }

        //准备intent
        Intent clickIntent = new Intent(this, MainActivity.class);
        PendingIntent clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent showIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        PendingIntent showPendingPI = PendingIntent.getBroadcast(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent hideIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        PendingIntent hidePendingPI = PendingIntent.getBroadcast(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(getResources().getString(R.string.app_service_tips))
                .setContentIntent(clickPI)
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_show), showPendingPI))
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_hide), hidePendingPI))
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();

        startForeground(SERVICE_GO_NOTE_ID, notification);
    }

    private void initJoyStick() {
        mJoyStick = new JoyStick(this);
        mJoyStick.setListener(new JoyStick.JoyStickClickListener() {
            @Override
            public void onMoveInfo(double speed, double disLng, double disLat, double angle) {
                // 只有在非轨迹模式下才处理摇杆移动
                if (!isTrajectoryMode) {
                    mSpeed = speed;
                    mBaseSpeed = speed;
                    // 根据当前的经纬度和距离，计算下一个经纬度
                    // Latitude: 1 deg = 110.574 km // 纬度的每度的距离大约为 110.574km
                    // Longitude: 1 deg = 111.320*cos(latitude) km  // 经度的每度的距离从0km到111km不等
                    // 具体见：http://wp.mlab.tw/?p=2200
                    mCurLng += disLng / (111.320 * Math.cos(Math.abs(mCurLat) * Math.PI / 180));
                    mCurLat += disLat / 110.574;
                    mCurBea = (float) angle;
                } else {
                    // 轨迹模式下更新速度和基础速度
                    mSpeed = speed;
                    mBaseSpeed = speed;
                }
            }

            @Override
            public void onPositionInfo(double lng, double lat, double alt) {
                // 只有在非轨迹模式下才处理位置更新
                if (!isTrajectoryMode) {
                    mCurLng = lng;
                    mCurLat = lat;
                    mCurAlt = alt;
                }
            }
        });
        mJoyStick.show();
    }

    private void initGoLocation() {
        // 创建 HandlerThread 实例，第一个参数是线程的名字
        mLocHandlerThread = new HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        // 启动 HandlerThread 线程
        mLocHandlerThread.start();
        // Handler 对象与 HandlerThread 的 Looper 对象的绑定
        mLocHandler = new Handler(mLocHandlerThread.getLooper()) {
            // 这里的Handler对象可以看作是绑定在HandlerThread子线程中，所以handlerMessage里的操作是在子线程中运行的
            @Override
            public void handleMessage(@NonNull Message msg) {
                try {
                    Thread.sleep(100);

                    if (!isStop) {
                        if (isTrajectoryMode && !mTrajectoryPoints.isEmpty()) {
                            updateTrajectoryPosition();
                        }
                        
                        setLocationNetwork();
                        setLocationGPS();

                        sendEmptyMessage(HANDLER_MSG_ID);
                    }
                } catch (InterruptedException e) {
                    XLog.e("SERVICEGO: ERROR - handleMessage");
                    Thread.currentThread().interrupt();
                }
            }
        };

        mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
    }

    private void removeTestProviderGPS() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderGPS");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderGPS() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实GPS参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderGPS");
        }
    }

    private void setLocationGPS() {
        try {
            // 尽可能模拟真实的 GPS 数据
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_FINE);    // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            
            // 应用随机偏移
            double[] offsetCoords = applyRandomOffset(mCurLat, mCurLng);
            loc.setLatitude(offsetCoords[0]);                   // 纬度（度）
            loc.setLongitude(offsetCoords[1]);                  // 经度（度）
            
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            Bundle bundle = new Bundle();
            bundle.putInt("satellites", 7);
            loc.setExtras(bundle);

            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationGPS");
        }
    }

    private void removeTestProviderNetwork() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderNetwork");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderNetwork() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实NETWORK参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
            } else {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }
        } catch (SecurityException e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderNetwork");
        }
    }

    private void setLocationNetwork() {
        try {
            // 尽可能模拟真实的 NETWORK 数据
            Location loc = new Location(LocationManager.NETWORK_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_COARSE);  // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            
            // 应用随机偏移
            double[] offsetCoords = applyRandomOffset(mCurLat, mCurLng);
            loc.setLatitude(offsetCoords[0]);                   // 纬度（度）
            loc.setLongitude(offsetCoords[1]);                  // 经度（度）
            
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationNetwork");
        }
    }

    // 应用随机偏移
    private double[] applyRandomOffset(double lat, double lon) {
        if (!isRandomOffset) {
            return new double[]{lat, lon};
        }

        // 计算随机偏移
        double randomLonOffset = (Math.random() * 2 - 1) * maxLonOffset;  // 经度偏移（米）
        double randomLatOffset = (Math.random() * 2 - 1) * maxLatOffset;  // 纬度偏移（米）

        // 转换为经纬度偏移
        lon += randomLonOffset / 111320;    // 米转换为经度
        lat += randomLatOffset / 110574;    // 米转换为纬度

        return new double[]{lat, lon};
    }

    public class NoteActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW)) {
                    mJoyStick.show();
                }

                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE)) {
                    mJoyStick.hide();
                }
            }
        }
    }

    // 更新轨迹位置
    private void updateTrajectoryPosition() {
        if (mTrajectoryPoints.size() < 2) {
            return;
        }

        // 应用速度随机偏移（仅在轨迹模式下）
        if (isTrajectoryMode && isSpeedRandomOffset) {
            // 伪随机速度变化：使用计数器实现固定的速度变化模式
            mSpeedOffsetCounter++;
            
            // 每10次更新改变一次速度方向
            if (mSpeedOffsetCounter % 10 == 0) {
                mIsSpeedIncreasing = !mIsSpeedIncreasing;
            }
            
            // 计算速度偏移量（±20%范围内）
            double offsetFactor = 0.2 * (mSpeedOffsetCounter % 10) / 10.0;
            if (!mIsSpeedIncreasing) {
                offsetFactor = -offsetFactor;
            }
            
            // 应用速度偏移
            mSpeed = mBaseSpeed * (1 + offsetFactor);
        }

        // 获取当前点和下一个点
        LatLng currentPoint = mTrajectoryPoints.get(mCurrentPointIndex);
        LatLng nextPoint = mTrajectoryPoints.get((mCurrentPointIndex + 1) % mTrajectoryPoints.size());

        // 计算两点之间的距离（单位：米）
        double distance = calculateDistance(currentPoint, nextPoint);

        // 计算每100ms应该移动的距离
        double moveDistance = mSpeed * 0.1; // 100ms = 0.1秒

        // 更新进度
        mTrajectoryProgress += moveDistance / distance;

        if (mTrajectoryProgress >= 1.0) {
            // 到达当前段的终点，移动到下一个点
            mCurrentPointIndex = (mCurrentPointIndex + 1) % mTrajectoryPoints.size();
            mTrajectoryProgress = 0.0;

            // 如果不是循环轨迹且到达最后一个点，停止轨迹移动
            if (!isLoopTrajectory && mCurrentPointIndex == 0) {
                isTrajectoryMode = false;
                return;
            }

            // 更新当前点和下一个点
            currentPoint = mTrajectoryPoints.get(mCurrentPointIndex);
            nextPoint = mTrajectoryPoints.get((mCurrentPointIndex + 1) % mTrajectoryPoints.size());
        }

        // 计算当前位置（线性插值）
        double ratio = mTrajectoryProgress;
        double newLat = currentPoint.latitude + (nextPoint.latitude - currentPoint.latitude) * ratio;
        double newLng = currentPoint.longitude + (nextPoint.longitude - currentPoint.longitude) * ratio;

        // 转换为WGS84坐标系
        double[] wgs84Coords = MapUtils.bd2wgs(newLng, newLat);
        mCurLng = wgs84Coords[0];
        mCurLat = wgs84Coords[1];

        // 计算方向（角度）
        double deltaLng = nextPoint.longitude - currentPoint.longitude;
        double deltaLat = nextPoint.latitude - currentPoint.latitude;
        double angle = Math.atan2(deltaLng, deltaLat) * 180 / Math.PI;
        if (angle < 0) {
            angle += 360;
        }
        mCurBea = (float) angle;
    }

    // 计算两点之间的距离（单位：米）
    private double calculateDistance(LatLng point1, LatLng point2) {
        final double R = 6371000; // 地球半径（米）
        double lat1 = Math.toRadians(point1.latitude);
        double lat2 = Math.toRadians(point2.latitude);
        double deltaLat = Math.toRadians(point2.latitude - point1.latitude);
        double deltaLng = Math.toRadians(point2.longitude - point1.longitude);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    public class ServiceGoBinder extends Binder {
        public void setPosition(double lng, double lat, double alt) {
            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mCurLng = lng;
            mCurLat = lat;
            mCurAlt = alt;
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
            // 非轨迹模式下显示完整摇杆
            isTrajectoryMode = false;
            mJoyStick.showJoystick();
        }

        public void setTrajectory(List<LatLng> points, boolean loop) {
            mTrajectoryPoints = points;
            isLoopTrajectory = loop;
            mCurrentPointIndex = 0;
            mTrajectoryProgress = 0.0;
            isTrajectoryMode = true;
            // 轨迹模式下显示速度选择器
            mJoyStick.showSpeedSelector();
        }

        public void setTrajectoryMode(boolean mode) {
            isTrajectoryMode = mode;
        }

        public void setLoopTrajectory(boolean loop) {
            isLoopTrajectory = loop;
        }
    }
}


