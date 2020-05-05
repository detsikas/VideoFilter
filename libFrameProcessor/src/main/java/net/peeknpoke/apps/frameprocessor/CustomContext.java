package net.peeknpoke.apps.frameprocessor;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.opengl.Matrix;
import android.view.Surface;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

class CustomContext implements SurfaceTexture.OnFrameAvailableListener, ObserverSubject<CustomContextObserver> {
    private static final String TAG = CustomContext.class.getSimpleName();
    private EGLContext mCtx;
    private EGLDisplay mDpy;
    private EGLSurface mSurf;
    private TextureHandler mTextureHandler;
    private Renderer mRenderer;
    private int mImageWidth;
    private int mImageHeight;
    private SurfaceTexture mSurfaceTexture;
    private Surface mSurface;
    private float[] mTransformMatrix = new float[16];
    final Object syncWithDecoder = new Object();
    boolean frameRendered = false;
    final Object syncWithEncoder = new Object();
    boolean frameEncoded = true;
    long frameTime;
    private List<WeakReference<CustomContextObserver>> mObservers = new ArrayList<>();

    CustomContext(int imageWidth, int imageHeight)
    {
        Matrix.setIdentityM(mTransformMatrix, 0);
        mImageWidth = imageWidth;
        mImageHeight = imageHeight;
    }

    void setupRenderingContext(Context context, Surface encoderInputSurface)
    {
        createEGLContext(encoderInputSurface);
        mTextureHandler = new TextureHandler();
        mRenderer = new Renderer(context);
        mSurfaceTexture = new SurfaceTexture(mTextureHandler.getTexture());
        mSurface = new Surface(mSurfaceTexture);
        mSurfaceTexture.setOnFrameAvailableListener(this);
        notifySetupComplete();
    }

    private void createEGLContext(Surface encoderInputSurface)
    {
        mDpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(mDpy, version, 0, version, 1);

        int[] configAttr = {
                EGL14.EGL_COLOR_BUFFER_TYPE, EGL14.EGL_RGB_BUFFER,
                EGL14.EGL_LEVEL, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfig = new int[1];
        EGL14.eglChooseConfig(mDpy, configAttr, 0,
                configs, 0, 1, numConfig, 0);

        EGLConfig config = configs[0];

        int[] surfAttr = {
                EGL14.EGL_NONE
        };

        mSurf = EGL14.eglCreateWindowSurface(mDpy, config, encoderInputSurface, surfAttr, 0);
        int[] ctxAttrib = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        mCtx = EGL14.eglCreateContext(mDpy, config, EGL14.EGL_NO_CONTEXT, ctxAttrib, 0);
        EGL14.eglMakeCurrent(mDpy, mSurf, mSurf, mCtx);
        GLES30.glClearColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private void onDrawFrame()
    {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        if (mRenderer!=null)
        {
            mRenderer.onDrawFrame(mTransformMatrix, mTextureHandler.getTexture(), mImageWidth, mImageHeight);
        }
    }

    void release()
    {
        cleanup();
        mTextureHandler.cleanup();
        mSurfaceTexture.release();

        EGL14.eglMakeCurrent(mDpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(mDpy, mSurf);
        EGL14.eglDestroyContext(mDpy, mCtx);
        EGL14.eglReleaseThread();
        EGL14.eglTerminate(mDpy);
    }

    Surface getSurface()
    {
        return mSurface;
    }

    private void cleanup()
    {
        if (mRenderer!=null)
            mRenderer.cleanup();

        mRenderer = null;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        synchronized (syncWithEncoder)
        {
            while(!frameEncoded) {
                try {
                    syncWithEncoder.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            frameEncoded = false;
        }

        EGLExt.eglPresentationTimeANDROID(mDpy, mSurf,
                frameTime * 1000);
        mSurfaceTexture.updateTexImage();
        onDrawFrame();
        swapSurfaces();

        synchronized (syncWithDecoder)
        {
            frameRendered = true;
            syncWithDecoder.notify();
        }
    }

    private void swapSurfaces()
    {
        //EGLExt.eglPresentationTimeANDROID(mDpy, mSurf,
          //      bufferInfo.presentationTimeUs * 1000)

        EGL14.eglSwapBuffers(mDpy, mSurf);
    }

    private WeakReference<CustomContextObserver> findWeakReference(CustomContextObserver observer)
    {
        WeakReference<CustomContextObserver> weakReference = null;
        for(WeakReference<CustomContextObserver> ref : mObservers) {
            if (ref.get() == observer) {
                weakReference = ref;
            }
        }
        return weakReference;
    }

    @Override
    public void registerObserver(CustomContextObserver observer) {
        WeakReference<CustomContextObserver> weakReference = findWeakReference(observer);
        if (weakReference==null)
            mObservers.add(new WeakReference<>(observer));

    }

    @Override
    public void removeObserver(CustomContextObserver observer) {
        WeakReference<CustomContextObserver> weakReference = findWeakReference(observer);
        if (weakReference != null) {
            mObservers.remove(weakReference);
        }
    }

    private void notifySetupComplete()
    {
        for (WeakReference<CustomContextObserver> co:mObservers){
            CustomContextObserver observer = co.get();
            if (observer!=null)
                observer.setupComplete();
        }
    }
}
