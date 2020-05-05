# VideoFilter
Asynchronous filtering of a video file with OpenGL

## Description
The app filters the first video track of a video file using MediaCodec and OpenGL. The audio track is ignored and not added to the output file.

## Asynchronous filtering
MediaCodec for both decoder and encoder is used in asynchronous mode. This makes processing much faster. At the same time it makes the implementation more difficult.

The reason for this is that decoding, encoding and OpenGL rendering must all be done in different threads. At the same time, even though the processing entities (encoder, decoder, opengl renderer) are asynchronous, synchronization between them is needed.

First, the decoder must be synchronized with OpenGL rendering so that it does not produce frames faster than the renderer can handle. Otherwise, some decoded frames would be dropped.

In addition to that, the encoder must be synchronized with OpenGL rendering so that rendering is not faster than the encoding. Otherwise, some rendered frames would not be encoded

## Decoder - Encoder threads
The decoder and encoder must be on separate threads. Otherwise, the app will freeze. What happens is the following
1. The decoder will receive an output buffer and release it for rendering to the OpenGL thread.
2. It will then wait on the synchronization condition
3. The OpenGL renderer will eventually render and signal the above condition, freeing the decoder. However, at some point,for no apparent reason, OpenGL freezes, does not finish rendering and does not signal the decoder. In fact, it seems that the whole app has frozen.

I am not entirely sure why this happens but it is fixed when the encoder, which does not seem to play any part in the above scenario, is moved to a separate thread. The reason why this fixes it, must be the following warning:

** Warning: If the video encoder locks up and blocks a dequeueing buffer the app becomes unresponsive.

which can be found at [https://source.android.com/devices/graphics/arch-st](https://source.android.com/devices/graphics/arch-st) .
Since the encoder and decoder are on the same thread, while the decoder sleeps on the wait condition, the encoder sleeps too and cannot proceed.

