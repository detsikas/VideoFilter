package net.peeknpoke.apps.frameprocessor;

import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class FrameProcessor implements ObserverSubject<FrameProcessorObserver>, CustomContextObserver {
    private static final String TAG = FrameProcessor.class.getSimpleName();
    private CustomContext mRenderingContext;
    private MediaCodec mMediaCodecDecoder;
    private MediaCodec mMediaCodecEncoder;
    private MediaExtractor mMediaExtractor;
    private MediaMuxer mMediaMuxer;
    private Surface mEncoderInputSurface;
    private ArrayList<WeakReference<FrameProcessorObserver>> mObservers = new ArrayList<>();
    private int mMuxerVideoTrackIndex = -1;
    private File mOutputVideoFile;
    private Handler mMainHandler;
    private MediaFormat mMediaFormat;
    private HandlerThread mEncoderThread;
    private Handler mEncoderHandler;

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public FrameProcessor(final Context context, Uri uri, final String appName) throws IOException {
        mMainHandler = new Handler(context.getMainLooper());
        mMediaExtractor = new MediaExtractor();

        final Handler renderingHandler = createRenderingThread();
        mEncoderHandler = createEncoderThread();

        mMediaExtractor.setDataSource(context, uri, null);
        int videoTrackIndex = getVideoTrackIndex(mMediaExtractor);
        if (videoTrackIndex <0)
        {
            Log.e(TAG, "No video track");
            return;
        }

        // Get media format
        mMediaExtractor.selectTrack(videoTrackIndex);
        mMediaFormat = mMediaExtractor.getTrackFormat(videoTrackIndex);
        final int width = mMediaFormat.getInteger(MediaFormat.KEY_WIDTH);
        final int height = mMediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int rotation = mMediaFormat.getInteger(MediaFormat.KEY_ROTATION);
        final String mimeType = mMediaFormat.getString(MediaFormat.KEY_MIME);

        // Create media muxer
        File folder = FileOperations.getAppMediaFolder(appName);
        if (folder!=null) {
            mOutputVideoFile = FileOperations.createMediaFile(folder, "output", MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO);
            mMediaMuxer = new MediaMuxer(mOutputVideoFile.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            mMediaMuxer.setOrientationHint(rotation);
        }
        mRenderingContext = new CustomContext(width, height);
        mRenderingContext.registerObserver(this);

        // Create media encoder. Create this first as it has no dependencies on decoder and muxer
        mEncoderHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    createMediaEncoder(context, mimeType, width, height);
                    renderingHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mRenderingContext.setupRenderingContext(context, mEncoderInputSurface);
                        }
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        // Create opengl rendering context
    }

    private Handler createEncoderThread()
    {
        HandlerThread renderingThread = new HandlerThread("Encoder");
        renderingThread.start();
        Looper looper = renderingThread.getLooper();
        return new Handler(looper);
    }

    private Handler createRenderingThread()
    {
        HandlerThread renderingThread = new HandlerThread("CustomContext");
        renderingThread.start();
        Looper looper = renderingThread.getLooper();
        return new Handler(looper);
    }

    private void start()
    {
        mEncoderHandler.post(new Runnable() {
            @Override
            public void run() {
                mMediaCodecEncoder.start();
            }
        });
        mMediaCodecDecoder.start();
    }

    private void stop()
    {
        if (mMediaCodecDecoder != null)
        {
            mMediaCodecDecoder.stop();
            mMediaCodecDecoder.release();
            mMediaCodecDecoder = null;
        }

        if (mMediaCodecEncoder != null)
        {
            mMediaCodecEncoder.stop();
            mMediaCodecEncoder.release();
            mMediaCodecEncoder = null;
        }
        if (mMediaMuxer != null)
        {
            mMediaMuxer.stop();
            mMediaMuxer.release();
            mMediaMuxer = null;
        }

        if (mMediaExtractor != null)
        {
            mMediaExtractor.release();
            mMediaExtractor = null;
        }
    }

    private void createMediaDecoder() throws IOException
    {
        String mimeType = mMediaFormat.getString(MediaFormat.KEY_MIME);
        if (mimeType==null)
        {
            Log.e(TAG, "Could not read mime type");
            return;
        }
        mMediaCodecDecoder = MediaCodec.createDecoderByType(mimeType);
        mMediaCodecDecoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                ByteBuffer inputBuffer = codec.getInputBuffer(index);
                Log.d(TAG, "Decoder filling buffer: "+index);
                fillInputBuffer(inputBuffer, index);
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                Log.d(TAG, "Decoder processing output buffer "+index+" size: "+info.size+" flags:"+info.flags);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0)
                {
                    Log.d(TAG, "video decoder: codec config buffer");
                    codec.releaseOutputBuffer(index, false);
                }
                else
                    processOutputBuffer(info, index);
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "Decoder media codec error - "+e.getMessage()+" - "+e.getErrorCode());
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {

            }
        });

        mMediaCodecDecoder.configure(mMediaFormat, mRenderingContext.getSurface(), null, 0);
    }

    private void createMediaEncoder(final Context context, String mimeType, int width, int height) throws IOException
    {
        MediaFormat mediaFormat = MediaFormat.createVideoFormat(mimeType, width, height);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 2000000);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
        mMediaCodecEncoder = MediaCodec.createEncoderByType(mimeType);
        mMediaCodecEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoderInputSurface = mMediaCodecEncoder.createInputSurface();
        mMediaCodecEncoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                // When using an input surface, there are no input buffers
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                Log.d(TAG, "Encoder processing output buffer "+index+" size: "+info.size);
                ByteBuffer outputBuffer = mMediaCodecEncoder.getOutputBuffer(index);
                mMediaMuxer.writeSampleData(mMuxerVideoTrackIndex, outputBuffer, info);
                mMediaCodecEncoder.releaseOutputBuffer(index, false);
                synchronized (mRenderingContext.syncWithEncoder)
                {
                    mRenderingContext.frameEncoded = true;
                    mRenderingContext.syncWithEncoder.notify();
                }

                if (info.size==0)
                {
                    stopConverting();
                    if (mOutputVideoFile!=null)
                    {
                        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        mediaScanIntent.setData(Uri.fromFile(mOutputVideoFile));
                        context.sendBroadcast(mediaScanIntent);
                    }
                }
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "Encoder media codec error - "+e.getMessage()+" - "+e.getErrorCode());
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                Log.e(TAG, "Encoder output format changed");
                mMuxerVideoTrackIndex = mMediaMuxer.addTrack(format);
                mMediaMuxer.start();
            }
        });
    }

    private void fillInputBuffer(ByteBuffer inputBuffer, int index)
    {
        int sampleSize = mMediaExtractor.readSampleData(inputBuffer, 0);
        Log.d(TAG, "sample size: "+sampleSize+" time: "+mMediaExtractor.getSampleTime());
        if (sampleSize < 0)
        {
            // End of input data reached
            mMediaCodecDecoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            Log.d(TAG, "input EOS");
        }
        else
        {
            mMediaCodecDecoder.queueInputBuffer(index, 0, sampleSize,
            mMediaExtractor.getSampleTime(), 0);
            mMediaExtractor.advance();
        }
    }

    private void processOutputBuffer(MediaCodec.BufferInfo info, int index)
    {
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            Log.d(TAG, "output EOS");
            mMediaCodecEncoder.signalEndOfInputStream();
        }

        mRenderingContext.frameTime = info.presentationTimeUs;
        mMediaCodecDecoder.releaseOutputBuffer(index, info.size != 0);
        if (info.size!=0)
        {
            synchronized (mRenderingContext.syncWithDecoder)
            {
                try {
                    while(!mRenderingContext.frameRendered)
                        mRenderingContext.syncWithDecoder.wait(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                mRenderingContext.frameRendered = false;
            }
        }
    }

    private int getVideoTrackIndex(MediaExtractor extractor)
    {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime!=null && mime.startsWith("video/"))
                return i;
        }

        return -1;
    }

    public void release()
    {
        stop();
        mRenderingContext.removeObserver(this);
        mRenderingContext.release();
        mEncoderInputSurface.release();
    }

    private void stopConverting() {
        stop();
        notifyObserversDoneProcessing();
    }

    private WeakReference<FrameProcessorObserver> findWeakReference(FrameProcessorObserver rendererObserver)
    {
        WeakReference<FrameProcessorObserver> weakReference = null;
        for(WeakReference<FrameProcessorObserver> ref : mObservers) {
            if (ref.get() == rendererObserver) {
                weakReference = ref;
            }
        }
        return weakReference;
    }

    @Override
    public void registerObserver(FrameProcessorObserver observer) {
        WeakReference<FrameProcessorObserver> weakReference = findWeakReference(observer);
        if (weakReference==null)
            mObservers.add(new WeakReference<>(observer));
    }

    @Override
    public void removeObserver(FrameProcessorObserver observer) {
        WeakReference<FrameProcessorObserver> weakReference = findWeakReference(observer);
        if (weakReference != null) {
            mObservers.remove(weakReference);
        }
    }

    private void notifyObserversDoneProcessing() {
        for (WeakReference<FrameProcessorObserver> co:mObservers){
            FrameProcessorObserver observer = co.get();
            if (observer!=null)
                observer.doneProcessing();
        }
    }

    private void renderingSurfaceCreated() {
        // Create media decoder
        // Note: this needs the surface created in CustomContext. So order cannot change
        try {
            createMediaDecoder();
            start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void setupComplete() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                renderingSurfaceCreated();
            }
        });
    }
}
