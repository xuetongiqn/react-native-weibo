package cn.reactnative.modules.weibo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;

import com.facebook.common.executors.UiThreadImmediateExecutorService;
import com.facebook.common.internal.Closeables;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.memory.PooledByteBuffer;
import com.facebook.common.memory.PooledByteBufferInputStream;
import com.facebook.common.references.CloseableReference;
import com.facebook.common.util.UriUtil;
import com.facebook.datasource.BaseDataSubscriber;
import com.facebook.datasource.DataSource;
import com.facebook.datasource.DataSubscriber;
import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.drawable.OrientedDrawable;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.core.ImagePipeline;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.image.CloseableStaticBitmap;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.RCTNativeAppEventEmitter;
import com.sina.weibo.sdk.api.ImageObject;
import com.sina.weibo.sdk.api.MusicObject;
import com.sina.weibo.sdk.api.TextObject;
import com.sina.weibo.sdk.api.VideoObject;
import com.sina.weibo.sdk.api.WebpageObject;
import com.sina.weibo.sdk.api.WeiboMultiMessage;
import com.sina.weibo.sdk.api.share.BaseResponse;
import com.sina.weibo.sdk.api.share.IWeiboHandler;
import com.sina.weibo.sdk.api.share.IWeiboShareAPI;
import com.sina.weibo.sdk.api.share.SendMultiMessageToWeiboRequest;
import com.sina.weibo.sdk.api.share.WeiboShareSDK;
import com.sina.weibo.sdk.auth.AuthInfo;
import com.sina.weibo.sdk.auth.Oauth2AccessToken;
import com.sina.weibo.sdk.auth.WeiboAuthListener;
import com.sina.weibo.sdk.auth.sso.SsoHandler;
import com.sina.weibo.sdk.exception.WeiboException;
import com.sina.weibo.sdk.utils.Utility;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.annotation.Nullable;

/**
 * Created by lvbingru on 12/22/15.
 */
public class WeiboModule extends ReactContextBaseJavaModule implements ActivityEventListener {

    private static final String RCTWBEventName = "Weibo_Resp";

    private SsoHandler mSinaSsoHandler;
    private IWeiboShareAPI mSinaShareAPI;
    private String appId;

    private static final String RCTWBShareTypeNews = "news";
    private static final String RCTWBShareTypeImage = "image";
    private static final String RCTWBShareTypeText = "text";
    private static final String RCTWBShareTypeVideo = "video";
    private static final String RCTWBShareTypeAudio = "audio";

    private static final String RCTWBShareType = "type";
    private static final String RCTWBShareText = "text";
    private static final String RCTWBShareTitle = "title";
    private static final String RCTWBShareDescription = "description";
    private static final String RCTWBShareWebpageUrl = "webpageUrl";
    private static final String RCTWBShareImageUrl = "imageUrl";
    private static final String RCTWBShareAccessToken = "accessToken";

    private static WeiboModule gModule = null;

    WeiboModule(ReactApplicationContext reactContext) {
        super(reactContext);
        ApplicationInfo appInfo = null;
        try {
            appInfo = reactContext.getPackageManager().getApplicationInfo(reactContext.getPackageName(), PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            throw new Error(e);
        }
        if (!appInfo.metaData.containsKey("WB_APPID")) {
            throw new Error("meta-data WB_APPID not found in AndroidManifest.xml");
        }
        this.appId = appInfo.metaData.getString("WB_APPID");
        this.appId = this.appId != null ? this.appId.substring(2) : null;
    }

    @Override
    public void initialize() {
        super.initialize();
        gModule = this;
        getReactApplicationContext().addActivityEventListener(this);
    }

    @Override
    public void onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy();
        gModule = null;
        getReactApplicationContext().removeActivityEventListener(this);
    }

    @Override
    public String getName() {
        return "RCTWeiboAPI";
    }

    private IWeiboShareAPI registerShare() {
        if (mSinaShareAPI == null) {
            mSinaShareAPI = WeiboShareSDK.createWeiboAPI(getReactApplicationContext(), this.appId);
            mSinaShareAPI.registerApp();
        }
        return mSinaShareAPI;
    }


    @ReactMethod
    public void login(final ReadableMap config, final Callback callback) {

        AuthInfo sinaAuthInfo = this._genAuthInfo(config);
        mSinaSsoHandler = new SsoHandler(getCurrentActivity(), sinaAuthInfo);
        mSinaSsoHandler.authorize(this.genWeiboAuthListener());
        callback.invoke();
    }

    @ReactMethod
    public void shareToWeibo(final ReadableMap data, Callback callback) {

        if (data.hasKey(RCTWBShareImageUrl)) {
            String imageUrl = data.getString(RCTWBShareImageUrl);

            DataSubscriber<CloseableReference<PooledByteBuffer>> dataSubscriber =
                new BaseDataSubscriber<CloseableReference<PooledByteBuffer>>() {

                    @Override
                    protected void onNewResultImpl(DataSource<CloseableReference<PooledByteBuffer>> dataSource) {
                        // isFinished must be obtained before image, otherwise we might set intermediate result
                        // as final image.
                        boolean isFinished = dataSource.isFinished();
                        CloseableReference<PooledByteBuffer> image = dataSource.getResult();
                        if (image != null) {
                            Preconditions.checkState(CloseableReference.isValid(image));
                            PooledByteBuffer result = image.get();
                            InputStream inputStream = new PooledByteBufferInputStream(result);
                            try {
                                _shareBytes(data, getBytes(inputStream));
                            } catch (IOException e) {
                                e.printStackTrace();
                            } finally {
                                Closeables.closeQuietly(inputStream);
                            }
                        } else if (isFinished) {
                            _share(data);
                        }
                        dataSource.close();
                    }

                    @Override
                    protected void onFailureImpl(DataSource<CloseableReference<PooledByteBuffer>> dataSource) {
                        dataSource.close();
                        _share(data);
                    }
                };

            ResizeOptions resizeOptions = null;
            if (!data.hasKey(RCTWBShareType) || !data.getString(RCTWBShareType).equals(RCTWBShareTypeImage)) {
                resizeOptions = new ResizeOptions(80, 80);
            }

            this._downloadImage(imageUrl, resizeOptions, dataSubscriber);
        } else {
            this._share(data);
        }

        callback.invoke();
    }

    private byte[] getBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
        int bufferSize = 1024;
        byte[] buffer = new byte[bufferSize];

        int len = 0;
        while ((len = inputStream.read(buffer)) != -1) {
            byteBuffer.write(buffer, 0, len);
        }
        return byteBuffer.toByteArray();
    }

    private void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (mSinaSsoHandler != null) {
            mSinaSsoHandler.authorizeCallBack(requestCode, resultCode, data);
            mSinaSsoHandler = null;
        }
    }

    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        this.onActivityResult(requestCode, resultCode, data);
    }

    public void onNewIntent(Intent intent) {

    }

    private WeiboAuthListener genWeiboAuthListener() {
        return new WeiboAuthListener() {
            @Override
            public void onComplete(Bundle bundle) {
                final Oauth2AccessToken token = Oauth2AccessToken.parseAccessToken(bundle);
                WritableMap event = Arguments.createMap();
                if (token.isSessionValid()) {
                    event.putString("accessToken", token.getToken());
                    event.putDouble("expirationDate", token.getExpiresTime());
                    event.putString("userID", token.getUid());
                    event.putString("refreshToken", token.getRefreshToken());
                    event.putInt("errCode", 0);
                } else {
                    event.putInt("errCode", -1);
                    event.putString("errMsg", "token invalid");
                }
                event.putString("type", "WBAuthorizeResponse");
                getReactApplicationContext().getJSModule(RCTNativeAppEventEmitter.class).emit(RCTWBEventName, event);
            }

            @Override
            public void onWeiboException(WeiboException e) {
                WritableMap event = Arguments.createMap();
                event.putString("type", "WBAuthorizeResponse");
                event.putString("errMsg", e.getMessage());
                event.putInt("errCode", -1);
                getReactApplicationContext().getJSModule(RCTNativeAppEventEmitter.class).emit(RCTWBEventName, event);
            }

            @Override
            public void onCancel() {
                WritableMap event = Arguments.createMap();
                event.putString("type", "WBAuthorizeResponse");
                event.putString("errMsg", "Cancel");
                event.putInt("errCode", -1);
                getReactApplicationContext().getJSModule(RCTNativeAppEventEmitter.class).emit(RCTWBEventName, event);
            }
        };
    }

    private void _shareBitmap(ReadableMap data, Bitmap bitmap) {
        this._share(data, bitmap, null);
    }

    private void _shareBytes(ReadableMap data, byte[] imageData) {
        this._share(data, null, imageData);
    }

    private void _share(ReadableMap data) {
        this._share(data, null, null);
    }

    private void _share(ReadableMap data, Bitmap bitmap, byte[] imageData) {
        this.registerShare();
        WeiboMultiMessage weiboMessage = new WeiboMultiMessage();//初始化微博的分享消息

        String type = RCTWBShareTypeNews;
        if (data.hasKey(RCTWBShareType)) {
            type = data.getString(RCTWBShareType);
        }

        if (type.equals(RCTWBShareTypeText)) {
            TextObject textObject = new TextObject();
            if (data.hasKey(RCTWBShareText)) {
                textObject.text = data.getString(RCTWBShareText);
            }
            weiboMessage.textObject = textObject;
        } else if (type.equals(RCTWBShareTypeImage)) {
            ImageObject imageObject = null;
            if (imageData != null) {
                Parcel parcel = Parcel.obtain();
                parcel.writeByteArray(imageData);
                parcel.setDataPosition(0);
                imageObject = new ImageObject(parcel);
                parcel.recycle();
            } else if (bitmap != null) {
                imageObject = new ImageObject();
                imageObject.setImageObject(bitmap);
            }
            weiboMessage.imageObject = imageObject;
        } else {
            if (type.equals(RCTWBShareTypeNews)) {
                WebpageObject webpageObject = new WebpageObject();
                if (data.hasKey(RCTWBShareWebpageUrl)) {
                    webpageObject.actionUrl = data.getString(RCTWBShareWebpageUrl);
                }
                weiboMessage.mediaObject = webpageObject;
            } else if (type.equals(RCTWBShareTypeVideo)) {
                VideoObject videoObject = new VideoObject();
                if (data.hasKey(RCTWBShareWebpageUrl)) {
                    videoObject.dataUrl = data.getString(RCTWBShareWebpageUrl);
                }
                weiboMessage.mediaObject = videoObject;
            } else if (type.equals(RCTWBShareTypeAudio)) {
                MusicObject musicObject = new MusicObject();
                if (data.hasKey(RCTWBShareWebpageUrl)) {
                    musicObject.dataUrl = data.getString(RCTWBShareWebpageUrl);
                }
                weiboMessage.mediaObject = musicObject;
            }
            if (data.hasKey(RCTWBShareDescription)) {
                weiboMessage.mediaObject.description = data.getString(RCTWBShareDescription);
            }
            if (data.hasKey(RCTWBShareTitle)) {
                weiboMessage.mediaObject.title = data.getString(RCTWBShareTitle);
            }
            if (bitmap != null) {
                weiboMessage.mediaObject.setThumbImage(bitmap);
            }
            weiboMessage.mediaObject.identify = Utility.generateGUID();
        }

        SendMultiMessageToWeiboRequest request = new SendMultiMessageToWeiboRequest();
        request.transaction = String.valueOf(System.currentTimeMillis());
        request.multiMessage = weiboMessage;

        String accessToken = null;
        if (data.hasKey(RCTWBShareAccessToken)) {
            accessToken = data.getString(RCTWBShareAccessToken);
        }
        boolean success = mSinaShareAPI.sendRequest(getCurrentActivity(), request, null, accessToken, genWeiboAuthListener());

        if (!success) {
            WritableMap event = Arguments.createMap();
            event.putString("type", "WBAuthorizeResponse");
            event.putString("errMsg", "WeiBo API invoke returns false.");
            event.putInt("errCode", -1);
            getReactApplicationContext().getJSModule(RCTNativeAppEventEmitter.class).emit(RCTWBEventName, event);
        }
    }

    private static boolean handleWeiboResponse(Intent intent, IWeiboHandler.Response response) {
        gModule.registerShare();
        return gModule.mSinaShareAPI.handleWeiboResponse(intent, response);
    }

    private static void onShareResponse(BaseResponse baseResponse) {
        WritableMap map = Arguments.createMap();
        map.putInt("errCode", baseResponse.errCode);
        map.putString("errMsg", baseResponse.errMsg);
        map.putString("type", "WBSendMessageToWeiboResponse");
        gModule.getReactApplicationContext()
            .getJSModule(RCTNativeAppEventEmitter.class)
            .emit(RCTWBEventName, map);
    }

    static public class SinaEntryActivity extends Activity implements IWeiboHandler.Response {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            WeiboModule.handleWeiboResponse(getIntent(), this);
        }

        @Override
        public void onResponse(BaseResponse baseResponse) {
            WeiboModule.onShareResponse(baseResponse);
            this.finish();
        }
    }

    private AuthInfo _genAuthInfo(ReadableMap config) {
        String redirectURI = "";
        if (config.hasKey("redirectURI")) {
            redirectURI = config.getString("redirectURI");
        }
        String scope = "";
        if (config.hasKey("scope")) {
            scope = config.getString("scope");
        }
        return new AuthInfo(getReactApplicationContext(), this.appId, redirectURI, scope);
    }

    private void _downloadImage(String imageUrl, ResizeOptions resizeOptions, DataSubscriber<CloseableReference<PooledByteBuffer>> dataSubscriber) {
        Uri uri = null;
        try {
            uri = Uri.parse(imageUrl);
            // Verify scheme is set, so that relative uri (used by static resources) are not handled.
            if (uri.getScheme() == null) {
                uri = null;
            }
        } catch (Exception e) {
            // ignore malformed uri, then attempt to extract resource ID.
        }
        if (uri == null) {
            uri = _getResourceDrawableUri(getReactApplicationContext(), imageUrl);
        }

        ImageRequestBuilder builder = ImageRequestBuilder.newBuilderWithSource(uri);
        if (resizeOptions != null) {
            builder.setResizeOptions(resizeOptions);
        }
        ImageRequest imageRequest = builder.build();

        ImagePipeline imagePipeline = Fresco.getImagePipeline();
        DataSource<CloseableReference<PooledByteBuffer>> dataSource = imagePipeline.fetchEncodedImage(imageRequest, getReactApplicationContext());
        dataSource.subscribe(dataSubscriber, UiThreadImmediateExecutorService.getInstance());
    }

    private static
    @Nullable
    Uri _getResourceDrawableUri(Context context, @Nullable String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        name = name.toLowerCase().replace("-", "_");
        int resId = context.getResources().getIdentifier(
            name,
            "drawable",
            context.getPackageName());
        return new Uri.Builder()
            .scheme(UriUtil.LOCAL_RESOURCE_SCHEME)
            .path(String.valueOf(resId))
            .build();
    }

    private Drawable _createDrawable(CloseableReference<CloseableImage> image) {
        Preconditions.checkState(CloseableReference.isValid(image));
        CloseableImage closeableImage = image.get();
        if (closeableImage instanceof CloseableStaticBitmap) {
            CloseableStaticBitmap closeableStaticBitmap = (CloseableStaticBitmap) closeableImage;
            BitmapDrawable bitmapDrawable = new BitmapDrawable(
                getReactApplicationContext().getResources(),
                closeableStaticBitmap.getUnderlyingBitmap());
            if (closeableStaticBitmap.getRotationAngle() == 0 ||
                closeableStaticBitmap.getRotationAngle() == EncodedImage.UNKNOWN_ROTATION_ANGLE) {
                return bitmapDrawable;
            } else {
                return new OrientedDrawable(bitmapDrawable, closeableStaticBitmap.getRotationAngle());
            }
        } else {
            throw new UnsupportedOperationException("Unrecognized image class: " + closeableImage);
        }
    }

    private Bitmap _drawable2Bitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        } else if (drawable instanceof NinePatchDrawable) {
            Bitmap bitmap = Bitmap
                .createBitmap(
                    drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight(),
                    drawable.getOpacity() != PixelFormat.OPAQUE ? Bitmap.Config.ARGB_8888
                        : Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight());
            drawable.draw(canvas);
            return bitmap;
        } else {
            return null;
        }
    }
}
